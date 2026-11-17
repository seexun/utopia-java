package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.reason.Expressions;
import tech.seexun.jknow.reason.Expressions.Expr;
import tech.seexun.jknow.reason.Rules;
import tech.seexun.jknow.reason.Rules.Op;
import tech.seexun.jknow.reason.Rules.Side;
import tech.seexun.jknow.store.mapper.ReasoningMapper;

/**
 * 业务规则的建/改/列/命中（business_rules.rs 全族对等——SQL 逐字，校验语义逐条）。
 * <b>校验在这里做完</b>：条件的 op 与操作数形状、谓词必须是属性、结论的几种形状各自
 * 完整——库里的 CHECK 是最后一道，报错信息却是给人看的（#494 的教训：一条读不懂的
 * 条件让整条规则被静默跳过，而规则页上它看着好好的）。
 */
@Service
public class BusinessRulesService {

    /** 内建 is_a 的 key（business_rules.rs:14 IS_A）。 */
    public static final String IS_A = "is_a";

    /** 条件，界面与 API 共用的形状（business_rules.rs:85-99）。 */
    public record ConditionInput(int group, UUID predicateId, String op, JsonNode operand,
                                 String side) {

        public static ConditionInput of(int group, UUID predicateId, String op, JsonNode operand,
                                        String side) {
            return new ConditionInput(group, predicateId, op, operand, side == null ? "x" : side);
        }
    }

    /** 结论的一次改写（business_rules.rs:209-219）：两种形状各自完整地给，不做部分更新。 */
    public record ConclusionInput(String kind, UUID typeId, UUID predicateId, JsonNode value,
                                  JsonNode expr, UUID joinPredicateId) {
    }

    private final ReasoningMapper mapper;
    private final TransactionTemplate tx;

    public BusinessRulesService(ReasoningMapper mapper, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    // ===================== 建与改 =====================

    private static String validateName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > 80) {
            throw AppException.invalid("bad_rule_name",
                    "A rule needs a name of 1-80 characters.");
        }
        return trimmed;
    }

    /**
     * 建一条规则（business_rules.rs:119-201）。空合取恒真会把整个类归进去——挡在入口
     * 比在求值器里默默不推更早。
     */
    public UUID create(UUID kbId, String name, String description, UUID subjectTypeId,
                       String conclusion, UUID concludeTypeId, UUID concludePredicateId,
                       JsonNode concludeValue, JsonNode concludeExpr, UUID joinPredicateId,
                       List<ConditionInput> conditions) {
        String trimmed = validateName(name);
        if (conditions == null || conditions.isEmpty()) {
            throw AppException.invalid("no_conditions",
                    "A rule needs at least one condition; without one it would conclude for every"
                            + " entity of the class.");
        }
        validateConditions(kbId, conditions, "relation".equals(conclusion));
        validateConclusion(kbId, new ConclusionInput(conclusion, concludeTypeId,
                concludePredicateId, concludeValue, concludeExpr, joinPredicateId));
        validateJoinShape(conclusion, joinPredicateId);
        exists(kbId, "entity_types", subjectTypeId, "unknown_class",
                "That class is not in this base.");
        UUID id = Uuids.nowV7();
        return tx.execute(status -> {
            try {
                mapper.insertAttributeRule(id, kbId, trimmed,
                        description == null ? "" : description.strip(), subjectTypeId, conclusion,
                        concludeTypeId, concludePredicateId, concludeValue, concludeExpr,
                        joinPredicateId);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw AppException.invalid("duplicate_rule",
                        "A rule with that name already exists here.");
            }
            insertConditions(id, conditions);
            recordVersion(kbId, id);
            return id;
        });
    }

    /**
     * 改一条规则（business_rules.rs:222-331）：条件整组替换（没有「改到一半」的中间状态）；
     * 结论三格互相定义、整组换。只改结论、不带条件的更新会清掉连接而把 y 侧条件留下
     * （#1109）——终检在持有规则行的事务内做，并发更新绕不过。
     */
    public void update(UUID kbId, UUID ruleId, String name, String description, Boolean enabled,
                       List<ConditionInput> conditions, ConclusionInput conclusion) {
        String trimmedName = name == null ? null : validateName(name);
        if (conditions != null && !conditions.isEmpty()) {
            ReasoningMapper.RuleDefRow old = mapper.selectRuleDef(kbId, ruleId);
            if (old == null) {
                throw AppException.NOT_FOUND;
            }
            boolean joined = conclusion != null ? "relation".equals(conclusion.kind())
                    : "relation".equals(old.conclusion());
            validateConditions(kbId, conditions, joined);
            UUID join = joined
                    ? (conclusion != null && conclusion.joinPredicateId() != null
                            ? conclusion.joinPredicateId() : old.joinPredicateId())
                    : null;
            validateJoinShape(joined ? "relation" : "other", join);
        }
        if (conclusion != null) {
            validateConclusion(kbId, conclusion);
            validateJoinShape(conclusion.kind(), conclusion.joinPredicateId());
        }
        tx.executeWithoutResult(status -> {
            int rows = mapper.updateAttributeRule(kbId, ruleId, trimmedName,
                    description == null ? null : description.strip(), enabled,
                    conclusion == null ? null : conclusion.kind(),
                    conclusion == null ? null : conclusion.typeId(),
                    conclusion == null ? null : conclusion.predicateId(),
                    conclusion == null ? null : conclusion.value(),
                    conclusion == null ? null : conclusion.expr(),
                    conclusion == null ? null : conclusion.joinPredicateId());
            if (rows == 0) {
                throw AppException.NOT_FOUND;
            }
            if (conditions != null) {
                mapper.deleteRuleConditions(ruleId);
                insertConditions(ruleId, conditions);
            }
            if (conditions != null || conclusion != null) {
                // 在持有规则行锁的事务内校验最终条件：保留条件和并发更新都不能绕过
                if (mapper.selectReadsYWithoutJoin(kbId, ruleId)) {
                    throw AppException.invalid("condition_side_without_join",
                            "Only a joined rule can read the other side of an edge.");
                }
            }
            // 定义变了才开新版本；改名、改描述、开关不算（0060）
            recordVersion(kbId, ruleId);
        });
    }

    /** 删一条规则（business_rules.rs:493-503）：派生行随 CASCADE 走——规则没了结论没有依据。 */
    public void delete(UUID kbId, UUID ruleId) {
        if (mapper.deleteAttributeRule(kbId, ruleId) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    /** 条件 INSERT（business_rules.rs:780-800）：seq 在组内编号，两组各自从 0 起。 */
    private void insertConditions(UUID ruleId, List<ConditionInput> conditions) {
        Map<Integer, Integer> next = new HashMap<>();
        for (ConditionInput c : conditions) {
            int seq = next.getOrDefault(c.group(), 0);
            mapper.insertRuleCondition(Uuids.nowV7(), ruleId, c.group(), seq, c.predicateId(),
                    c.op(), c.operand(), c.side());
            next.put(c.group(), seq + 1);
        }
    }

    /**
     * 定义变了就开新版本（business_rules.rs:355-397）：关掉当前的、序号加一。没变什么都
     * 不做。在写规则的同一个事务里跑：定义和它的版本要么一起落，要么一起不落。
     */
    private UUID recordVersion(UUID kbId, UUID ruleId) {
        JsonNode now = mapper.selectRuleDefinition(ruleId);
        ReasoningMapper.VersionRow current = mapper.selectCurrentVersion(ruleId);
        int seq;
        if (current != null && current.definition().equals(now)) {
            return current.id();
        }
        if (current != null) {
            mapper.supersedeVersion(current.id());
            seq = current.seq() + 1;
        } else {
            seq = 1;
        }
        UUID id = Uuids.nowV7();
        mapper.insertVersion(id, kbId, ruleId, seq, now);
        return id;
    }

    // ===================== 校验 =====================

    /** 结论的校验（business_rules.rs:837-903）：建与改共用这一份。 */
    private void validateConclusion(UUID kbId, ConclusionInput c) {
        switch (c.kind()) {
            case "typing" -> {
                if (c.typeId() == null) {
                    throw AppException.invalid("no_class",
                            "A typing rule needs the class it concludes.");
                }
                exists(kbId, "entity_types", c.typeId(), "unknown_class",
                        "That class is not in this base.");
                // 归类结论落在内建 is_a 上，第一次写这种规则时把它建出来
                ensureIsA(kbId);
            }
            case "attribute" -> {
                if (c.predicateId() == null) {
                    throw AppException.invalid("no_predicate",
                            "An attribute rule needs the attribute it sets.");
                }
                if (c.value() == null) {
                    throw AppException.invalid("no_value",
                            "An attribute rule needs the value it sets.");
                }
                attributePredicate(kbId, c.predicateId());
            }
            case "computed" -> {
                if (c.predicateId() == null) {
                    throw AppException.invalid("no_predicate",
                            "A computed rule needs the attribute it sets.");
                }
                if (c.expr() == null) {
                    throw AppException.invalid("no_expression",
                            "A computed rule needs the expression it computes.");
                }
                // 这几条挡在入口而不是留给求值器（#494）：读不懂的树在求值时只会
                // 「这条规则什么都不推」，写它的人看不见任何理由
                List<UUID> reads = validateExpr(c.expr());
                if (reads.isEmpty()) {
                    throw AppException.invalid("constant_expression",
                            "A computed rule that reads no attribute is a constant; set the value"
                                    + " directly.");
                }
                for (UUID r : reads) {
                    attributePredicate(kbId, r);
                }
                attributePredicate(kbId, c.predicateId());
            }
            case "relation" -> {
                if (c.predicateId() == null) {
                    throw AppException.invalid("no_predicate",
                            "A relation rule needs the relation it concludes.");
                }
                relationPredicate(kbId, c.predicateId());
            }
            default -> throw AppException.invalid("bad_conclusion",
                    "A conclusion is a typing, an attribute, a computed attribute or a relation.");
        }
    }

    /** 一棵算式树的校验，返回它读到的谓词（business_rules.rs:905-920）。 */
    private static List<UUID> validateExpr(JsonNode raw) {
        Expr expr;
        try {
            expr = Expressions.fromJson(raw);
        } catch (Expressions.ExprErrorException e) {
            throw AppException.invalid(e.error().code(), e.error().message());
        }
        List<UUID> reads = new ArrayList<>();
        Expressions.predicates(expr, reads);
        return reads;
    }

    /** 条件的校验（business_rules.rs:922-984）：报错要说人话。 */
    private void validateConditions(UUID kbId, List<ConditionInput> conditions, boolean joined) {
        for (ConditionInput c : conditions) {
            Op op = Op.parse(c.op());
            if (op == null) {
                throw AppException.invalid("bad_op",
                        "A condition compares with >, >=, <, <=, a range, a set (in or not in),"
                                + " or presence.");
            }
            Side side = Side.parse(c.side());
            if (side == null) {
                throw AppException.invalid("bad_condition_side",
                        "A condition reads x (the rule's subject) or y (the joined entity).");
            }
            if (!joined && side != Side.X) {
                throw AppException.invalid("condition_side_without_join",
                        "Only a joined rule can read the other side of an edge.");
            }
            attributePredicate(kbId, c.predicateId());
            // 0032 起门槛可以是表达式：与 computed 同一棵 AST、同一库内属性引用
            if ((op == Op.GT || op == Op.GTE || op == Op.LT || op == Op.LTE)
                    && c.operand() != null && c.operand().isObject()) {
                for (UUID predicate : validateExpr(c.operand())) {
                    attributePredicate(kbId, predicate);
                }
                continue;
            }
            boolean shaped;
            switch (op) {
                case PRESENT -> shaped = c.operand() == null || c.operand().isNull();
                case NOT_IN -> shaped = c.operand() != null && c.operand().isArray()
                        && !c.operand().isEmpty();
                // between 元素必须是真 JSON 数（business_rules.rs:946-950 的 as_f64()——
                // 数字字符串不收，审查 M-6）
                case BETWEEN -> shaped = c.operand() != null && c.operand().isArray()
                        && c.operand().size() == 2
                        && c.operand().get(0).isNumber()
                        && c.operand().get(1).isNumber();
                case IN -> shaped = c.operand() != null && c.operand().isArray()
                        && !c.operand().isEmpty();
                default -> shaped = c.operand() != null && Rules.num(c.operand()) != null;
            }
            if (!shaped) {
                throw AppException.invalid("bad_operand",
                        "That comparison does not match the value it was given: a threshold needs"
                                + " a number, a range needs two, a set needs at least one entry,"
                                + " and presence takes none.");
            }
        }
    }

    /** 条件与属性结论只能落在 kind='attribute' 的谓词上（0021 决策 3）。 */
    private void attributePredicate(UUID kbId, UUID id) {
        String kind = mapper.selectPredicateKind(kbId, id);
        if ("attribute".equals(kind)) {
            return;
        }
        if (kind == null) {
            throw AppException.invalid("unknown_predicate", "That attribute is not in this base.");
        }
        throw AppException.invalid("not_an_attribute",
                "A rule reads this entity's own attributes; a relation to another entity cannot be"
                        + " a condition.");
    }

    private void relationPredicate(UUID kbId, UUID id) {
        String kind = mapper.selectPredicateKind(kbId, id);
        if ("relation".equals(kind)) {
            return;
        }
        if (kind == null) {
            throw AppException.invalid("unknown_predicate",
                    "That relation is not in this base.");
        }
        throw AppException.invalid("not_a_relation",
                "A join or relation conclusion names an edge between two entities.");
    }

    /** 结论与它的连接边是一个可替换的形状（business_rules.rs:957-973）。 */
    private static void validateJoinShape(String kind, UUID joinPredicateId) {
        boolean isRelation = "relation".equals(kind);
        if (isRelation == (joinPredicateId != null)) {
            return;
        }
        if (isRelation) {
            throw AppException.invalid("no_join_predicate",
                    "A relation rule needs the relation that connects X to Y.");
        }
        throw AppException.invalid("join_without_relation",
                "Only a relation conclusion can name a join predicate.");
    }

    private void exists(UUID kbId, String table, UUID id, String code, String message) {
        if (mapper.selectExistsIn(table, kbId, id) == null) {
            throw AppException.invalid(code, message);
        }
    }

    /**
     * 内建 is_a 谓词（business_rules.rs:16-44）：派生归类的结论落在它上面。
     * <b>按需建，不在建库时铺</b>——一个从不写规则的库不该多出一个它看不懂的谓词。
     */
    public UUID ensureIsA(UUID kbId) {
        UUID existing = mapper.selectPredicateIdByKey(kbId, IS_A);
        if (existing != null) {
            return existing;
        }
        mapper.insertIsAPredicate(Uuids.nowV7(), kbId, IS_A,
                "A class a rule concluded for this entity. Derived, and it never replaces the"
                        + " asserted type.");
        // ON CONFLICT 命中说明并发建过了，取回那一条
        return mapper.selectPredicateIdByKey(kbId, IS_A);
    }

    // ===================== 读侧 =====================

    /**
     * 列出规则，连同条件与「现在推出了多少条」（business_rules.rs:506-577）。
     * 返回 json! 同形状的 ObjectNode 列表。
     */
    public List<com.fasterxml.jackson.databind.node.ObjectNode> list(UUID kbId) {
        List<ReasoningMapper.RuleListRow> rules = mapper.selectRuleList(kbId);
        if (rules.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ReasoningMapper.RuleListConditionRow>> byRule = new HashMap<>();
        for (ReasoningMapper.RuleListConditionRow c : mapper.selectRuleListConditions(
                rules.stream().map(ReasoningMapper.RuleListRow::id).toList())) {
            byRule.computeIfAbsent(c.ruleId(), k -> new ArrayList<>()).add(c);
        }
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        List<com.fasterxml.jackson.databind.node.ObjectNode> out = new ArrayList<>(rules.size());
        for (ReasoningMapper.RuleListRow r : rules) {
            var o = json.objectNode();
            o.put("id", r.id().toString());
            o.put("name", r.name());
            o.put("description", r.description());
            o.put("subject_type_id", r.subjectTypeId().toString());
            o.put("subject_label", r.subjectLabel());
            o.put("conclusion", r.conclusion());
            if (r.concludeTypeId() != null) {
                o.put("conclude_type_id", r.concludeTypeId().toString());
            } else {
                o.putNull("conclude_type_id");
            }
            o.put("conclude_type_label", r.concludeTypeLabel());
            if (r.concludePredicateId() != null) {
                o.put("conclude_predicate_id", r.concludePredicateId().toString());
            } else {
                o.putNull("conclude_predicate_id");
            }
            o.put("conclude_predicate_label", r.concludePredicateLabel());
            o.set("conclude_value", r.concludeValue());
            o.set("conclude_expr", r.concludeExpr());
            o.put("join_predicate_id", r.joinPredicateId() == null ? null
                    : r.joinPredicateId().toString());
            o.put("join_predicate_label", r.joinPredicateLabel());
            o.put("enabled", r.enabled());
            o.put("version", r.version() == null ? 1 : r.version());
            o.put("derived_count", r.derivedCount());
            o.put("capped", r.capped());
            var conditions = json.arrayNode();
            for (ReasoningMapper.RuleListConditionRow c : byRule.getOrDefault(r.id(), List.of())) {
                var cn = conditions.addObject();
                cn.put("group", c.groupSeq());
                cn.put("predicate_id", c.predicateId().toString());
                cn.put("predicate_label", c.predicateLabel());
                cn.put("op", c.op());
                cn.set("operand", c.operand());
                cn.put("side", c.subjectSide());
            }
            o.set("conditions", conditions);
            out.add(o);
        }
        return out;
    }

    /** 一条规则此刻标了哪些实体（business_rules.rs:628-765）：数字与列表是同一份事实。 */
    public Matches matches(UUID kbId, UUID ruleId, long limit, long offset) {
        long total = mapper.countMatches(kbId, ruleId);
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        List<com.fasterxml.jackson.databind.node.ObjectNode> rows = new ArrayList<>();
        for (ReasoningMapper.MatchRow m : mapper.selectMatches(kbId, ruleId, limit, offset)) {
            var o = json.objectNode();
            o.put("derived_id", m.derivedId().toString());
            o.put("entity_id", m.entityId().toString());
            o.put("entity", m.entity());
            o.put("object_id", m.objectId() == null ? null : m.objectId().toString());
            o.put("object_entity", m.objectEntity());
            o.put("concluded", m.concluded());
            o.put("relation_predicate", m.relationPredicate());
            if (m.validFrom() != null) {
                o.put("valid_from", m.validFrom().toString());
            } else {
                o.putNull("valid_from");
            }
            if (m.validTo() != null) {
                o.put("valid_to", m.validTo().toString());
            } else {
                o.putNull("valid_to");
            }
            o.put("valid_from_precision", m.validFromPrecision());
            o.put("valid_to_precision", m.validToPrecision());
            var premises = json.arrayNode();
            for (String p : m.premises()) {
                premises.add(p);
            }
            o.set("premises", premises);
            rows.add(o);
        }
        return new Matches(rows, total);
    }

    /** matches 的产出（行 + 总数）。 */
    public record Matches(List<com.fasterxml.jackson.databind.node.ObjectNode> rows, long total) {
    }

    /**
     * 一条规则的定义史（business_rules.rs:412-501，0060）：每一版说了什么、从什么时候到
     * 什么时候、此刻凭它成立几条。历史里的 id 可能已经改名甚至删掉，标签按现在能查到的
     * 给，查不到的界面显示 id——所以 labels 是「现在」的投影，同一份贴在每一版上。
     * 规则不存在或不在这个库：两者对调用方都是 404。
     */
    public List<com.fasterxml.jackson.databind.node.ObjectNode> versions(UUID kbId, UUID ruleId) {
        List<ReasoningMapper.RuleVersionRow> rows = mapper.selectRuleVersions(kbId, ruleId);
        if (rows.isEmpty()) {
            if (mapper.selectRuleIdInKb(kbId, ruleId) == null) {
                throw AppException.NOT_FOUND;
            }
            return List.of();
        }
        // 定义里提到的类与谓词收拢回查标签（uuid_at：字段缺失/非 UUID 形都跳过）
        LinkedHashSet<UUID> classes = new LinkedHashSet<>();
        LinkedHashSet<UUID> predicates = new LinkedHashSet<>();
        for (ReasoningMapper.RuleVersionRow r : rows) {
            collectUuid(r.definition(), "subject_type_id", classes);
            collectUuid(r.definition(), "conclude_type_id", classes);
            collectUuid(r.definition(), "conclude_predicate_id", predicates);
            collectUuid(r.definition(), "join_predicate_id", predicates);
            JsonNode conds = r.definition().get("conditions");
            if (conds != null && conds.isArray()) {
                for (JsonNode c : conds) {
                    collectUuid(c, "predicate_id", predicates);
                }
            }
        }
        var labels = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        appendLabels("entity_types", new ArrayList<>(classes), labels);
        appendLabels("relation_types", new ArrayList<>(predicates), labels);
        var json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance;
        List<com.fasterxml.jackson.databind.node.ObjectNode> out = new ArrayList<>(rows.size());
        for (ReasoningMapper.RuleVersionRow r : rows) {
            var o = json.objectNode();
            o.put("id", r.id().toString());
            o.put("seq", r.seq());
            o.set("definition", r.definition());
            o.put("recorded_at", r.recordedAt().toString());
            if (r.supersededAt() != null) {
                o.put("superseded_at", r.supersededAt().toString());
            } else {
                o.putNull("superseded_at");
            }
            o.put("derived_count", r.derivedCount());
            o.set("labels", labels);
            out.add(o);
        }
        return out;
    }

    /** definition JSON 里某键的 UUID（uuid_at 的 `?` 语义：缺失/坏形返回 null 由调用方跳过）。 */
    private static void collectUuid(JsonNode node, String key, LinkedHashSet<UUID> into) {
        JsonNode v = node == null ? null : node.get(key);
        if (v == null || !v.isTextual()) {
            return;
        }
        try {
            into.add(UUID.fromString(v.asText()));
        } catch (IllegalArgumentException ignored) {
            // 历史里的 id 可能是任意文本（老版本形状），回查不到就留给界面显示原文
        }
    }

    /** labels 回查（business_rules.rs:462-463）：查得到的进 map，查不到的不进。 */
    private void appendLabels(String table, List<UUID> ids,
                              com.fasterxml.jackson.databind.node.ObjectNode labels) {
        if (ids.isEmpty()) {
            return;
        }
        for (ReasoningMapper.IdLabel l : mapper.selectIdLabels(table, ids)) {
            labels.put(l.id().toString(), l.label());
        }
    }
}
