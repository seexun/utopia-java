package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.ErrataHeldItem;
import tech.seexun.jknow.store.mapper.ErrataMapper;
import tech.seexun.jknow.store.mapper.ErrataMapper.Candidate;
import tech.seexun.jknow.store.mapper.ErrataMapper.HeldRow;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;
import tech.seexun.jknow.store.temporal.WorldTime;
import tech.seexun.jknow.store.temporal.WorldTime.Temporal;

/**
 * 勘误（errata.rs 850 行对等，0044 决定 7 第六刀）：抽取之后按文档复审类型化图谱的
 * agent 在库里留下的东西。结构先报（domain/range/name_absent/no_date）；每一条看过的
 * 事实记一笔（keep 也记）；撤、改、加记成动作，带着文档的原话。动作走 0027 的闸门：
 * 撤的事实有派生靠着、主语在回答里被认过，或者写的事实会让一个只许一个值的谓词有两个
 * 值——留给人，agent 不动手。
 *
 * <p><b>撤销要站得住（心脏不变量）</b>：物化下一轮会把活着的陈述再算成同一条类型化行。
 * 所以动作记着（陈述, 属性），物化与蕴含都跳过被勘误撤过的那一对——
 * {@code TypedMaterializeService} 的 due/隐含查询里各有一句 {@code errata_actions}
 * NOT EXISTS。别的文档说了同一件事照样算：勘误看的是这份文档，撤的是这份文档产生的那条。
 *
 * <p>errata_review 任务本体（LLM 复审循环）属 M4——本服务是它的账本侧与执行侧。
 */
@Service
public class ErrataService {

    /** errata.rs:25 排队的任务名（一个库一份）。 */
    public static final String JOB_KIND = "errata_review";

    private final ErrataMapper mapper;
    private final ResolutionService resolution;
    private final ImplicationRulesService implicationRules;
    private final GraphWriteService writes;
    private final TemporalService temporal;
    private final ExecutionGateService gate;
    private final TransactionTemplate tx;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ErrataService(ErrataMapper mapper, ResolutionService resolution,
                         ImplicationRulesService implicationRules, GraphWriteService writes,
                         TemporalService temporal, ExecutionGateService gate,
                         PlatformTransactionManager txManager,
                         org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.resolution = resolution;
        this.implicationRules = implicationRules;
        this.writes = writes;
        this.temporal = temporal;
        this.gate = gate;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
    }

    /** errata.rs:116-126 这份文档产生的、活着的、还没看过的类型化事实，结构报了的在前。 */
    public List<Candidate> candidates(UUID kbId, UUID documentId) {
        return mapper.selectCandidates(kbId, documentId);
    }

    /** errata.rs:129-138 文档当前版本的正文，按分块顺序接起来。 */
    public String documentText(UUID documentId) {
        String text = jdbc.query(
                "SELECT string_agg(text, ' ' ORDER BY seq) FROM chunks WHERE document_id = ? AND superseded_at IS NULL",
                rs -> rs.next() ? rs.getString(1) : null, documentId);
        return text == null ? "" : text;
    }

    /** errata.rs:143-165 该看的文档。 */
    public List<UUID> documentsDue(UUID kbId, long limit) {
        return mapper.selectDocumentsDue(kbId, limit);
    }

    /** errata.rs:168-174 这份文档看过几次。 */
    public long runsOf(UUID documentId) {
        return mapper.runsOf(documentId);
    }

    /** errata.rs:178-197 start_run：一次复审开账。 */
    public UUID startRun(UUID kbId, UUID documentId, int flagged, int sampled) {
        UUID id = Uuids.nowV7();
        mapper.insertRun(id, kbId, documentId, flagged, sampled);
        return id;
    }

    /** errata.rs:200-218 finish_run：结账。 */
    public void finishRun(UUID runId, int requests, Long promptTokens, Long completionTokens) {
        mapper.finishRun(runId, requests, promptTokens, completionTokens);
    }

    /** 内部两形态结果（Rust Result 的最小等价，仅 resolve 链内部使用）。 */
    static final class Result<V, E> {
        private final V value;
        private final E error;

        private Result(V value, E error) {
            this.value = value;
            this.error = error;
        }

        static <V, E> Result<V, E> ok(V value) {
            return new Result<>(value, null);
        }

        static <V, E> Result<V, E> err(E error) {
            return new Result<>(null, error);
        }

        boolean isOk() {
            return error == null;
        }

        V value() {
            return value;
        }

        E error() {
            return error;
        }

        <W> Result<W, E> map(java.util.function.Function<V, W> f) {
            return isOk() ? ok(f.apply(value)) : err(error);
        }
    }

    /** errata.rs:221-246 agent 对一条事实的说法，或它想加的一条。 */
    public sealed interface Proposed permits Keep, Retract, Revise, Add {
        default String action() {
            if (this instanceof Keep) {
                return "keep";
            }
            if (this instanceof Retract) {
                return "retract";
            }
            if (this instanceof Revise) {
                return "revise";
            }
            return "add";
        }
    }

    /** keep。 */
    public record Keep() implements Proposed {
    }

    /** retract。 */
    public record Retract() implements Proposed {
    }

    /** 换属性（键）、换宾语（名字或值），至少一样。 */
    public record Revise(String property, String object) implements Proposed {
    }

    /** 加一条。 */
    public record Add(String subject, String property, String object) implements Proposed {
    }

    /** errata.rs:248-258 ActionInput。 */
    public record ActionInput(UUID runId, UUID documentId, Candidate candidate, Proposed proposed,
                              String reason, String quote, String documentText) {
    }

    /** errata.rs:261-268 一笔记下来的动作：落了地、留给人了、还是没过验证。 */
    public record Recorded(UUID id, String status, String detail, UUID newFactId) {
    }

    /**
     * errata.rs:271-281 {@code quote_in}：引文是不是文档的原话——折掉大小写与空白后的
     * 连续一段。空引文不算。
     */
    public static boolean quoteIn(String document, String quote) {
        String q = squash(quote);
        return !q.isEmpty() && squash(document).contains(q);
    }

    private static String squash(String s) {
        return s == null ? "" : String.join(" ", s.trim().split("\\s+")).toLowerCase();
    }

    /** errata.rs:284-316 Target：动作指向的那条事实（名字给队列卡片看，id 给执行用）。 */
    record Target(UUID subjectId, UUID predicateId, UUID objectId, JsonNode objectValue,
                  String subject, String property, String object) {

        /** 宾语两形态：实体或值（恰好一个）。 */
        record Pair(UUID objectId, JsonNode objectValue) {
        }


        JsonNode json() {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            n.put("subject", subject);
            n.put("property", property);
            n.put("object", object);
            n.put("subject_id", subjectId.toString());
            n.put("predicate_id", predicateId.toString());
            if (objectId != null) {
                n.put("object_id", objectId.toString());
            } else {
                n.putNull("object_id");
            }
            if (objectValue != null) {
                n.set("object_value", objectValue);
            } else {
                n.putNull("object_value");
            }
            return n;
        }

        static Target fromJson(JsonNode v) {
            if (v == null || v.get("subject_id") == null || v.get("predicate_id") == null) {
                return null;
            }
            try {
                // Rust from_json（errata.rs:302-315）对缺失键一律容忍（get()?.as_str()? →
                // None）：held 行的 proposed 里没有值宾语的很常见——早先 get(...) 直接链
                // .isTextural()/.isNull() 对缺失键 NPE（M4 任务 2 的 held 勘误人答步抓出）
                JsonNode objectIdNode = v.get("object_id");
                UUID objectId = objectIdNode != null && objectIdNode.isTextual()
                        ? UUID.fromString(objectIdNode.asText()) : null;
                JsonNode objectValueNode = v.get("object_value");
                JsonNode objectValue =
                        objectValueNode == null || objectValueNode.isNull() ? null : objectValueNode;
                return new Target(UUID.fromString(v.get("subject_id").asText()),
                        UUID.fromString(v.get("predicate_id").asText()), objectId,
                        objectValue,
                        v.path("subject").asText(""), v.path("property").asText(""),
                        v.path("object").asText(""));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /** errata.rs:319-331 属性键在这个库里对应的行。 */
    ErrataMapper.PropertyRow propertyByKey(UUID kbId, String key) {
        return mapper.selectPropertyByKey(kbId, key == null ? "" : key.trim());
    }

    /**
     * errata.rs:336-354 {@code thing_named}：一个名字解成库里的东西——已有的按名字找；
     * 没有的，只要这个名字是文档的原话，就按 0041 建一个带名字事实的实体。不在文档里的
     * 名字不建：那是 agent 编的。
     */
    Result<UUID, String> thingNamed(UUID kbId, String name, String documentText) {
        UUID existing = resolution.existingByName(kbId, name);
        if (existing != null) {
            return Result.ok(existing);
        }
        if (quoteIn(documentText, name)) {
            return Result.ok(implicationRules.resolveOrCreateNamed(kbId, name));
        }
        return Result.err("no thing named \"" + name + "\" and the document does not name it");
    }

    /** errata.rs:357-375 宾语的文本按属性的种类解：关系要一样东西，属性落成值。 */
    Result<Target.Pair, String> objectOf(UUID kbId, String kind, String text,
                                         String documentText) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Result.err("empty object");
        }
        if ("relation".equals(kind)) {
            return thingNamed(kbId, trimmed, documentText)
                    .map(id -> new Target.Pair(id, null));
        }
        return Result.ok(new Target.Pair(null,
                JsonNodeFactory.instance.objectNode().put("value", trimmed)));
    }

    /**
     * errata.rs:379-601 {@code record}：记一笔并（能的话）执行。keep 只记；撤、改、加先验
     * 引文，再过闸门，过了才动图。没过验证的也记（refused）：这条事实算看过了，agent 的
     * 说法留着给人看，但不再问。结构没报过的事实，agent 单方面要撤或改：留给人（unflagged）。
     */
    public Recorded record(UUID kbId, ActionInput input) {
        UUID id = Uuids.nowV7();
        String action = input.proposed().action();
        Candidate c = input.candidate();
        if (!(input.proposed() instanceof Add) && c == null) {
            throw AppException.validation(action + " needs the fact it is about");
        }
        UUID factId = c != null ? c.factId() : null;
        UUID statementId = c != null ? c.statementId() : null;
        String flag = c != null ? c.flag() : null;
        // 动作指向的那条事实，与验证的结果
        Result<Target, String> resolved = resolveTarget(kbId, input, c);
        // 撤、改、加都得引文档的原话；不是原话的说法记下来，不执行
        if (resolved.isOk() && !(input.proposed() instanceof Keep)
                && !(input.quote() != null && quoteIn(input.documentText(), input.quote()))) {
            resolved = Result.err("the quote is not in the document");
        }
        Target target = resolved.isOk() ? resolved.value() : null;
        JsonNode targetJson = target != null ? target.json() : null;
        // 0027 的闸门：撤的那条有什么靠着它，写的那条会不会立刻开出违规
        ExecutionGate.Hold hold = null;
        if (target != null) {
            ExecutionGate.Impact impact = new ExecutionGate.Impact();
            if (factId != null && !(input.proposed() instanceof Keep)) {
                impact = gate.impactOfFact(kbId, factId);
            }
            if (!(input.proposed() instanceof Retract)) {
                UUID replacing = factId != null && input.proposed() instanceof Revise
                        ? factId : null;
                ExecutionGate.Impact w = gate.impactOfWrite(kbId, target.subjectId(),
                        target.predicateId(), target.objectId(), replacing);
                impact.contradictions.addAll(w.contradictions);
            }
            hold = ExecutionGate.hold(impact);
        }
        boolean unflagged = c != null && c.flag() == null
                && (input.proposed() instanceof Retract || input.proposed() instanceof Revise);
        String status;
        String detail;
        if (!resolved.isOk()) {
            status = "refused";
            detail = resolved.error();
        } else if (hold != null) {
            status = "held";
            detail = hold.toString();
        } else if (unflagged) {
            status = "held";
            detail = "unflagged";
        } else {
            status = "applied";
            detail = null;
        }
        mapper.insertAction(id, kbId, input.runId(), input.documentId(), factId, statementId,
                c != null ? c.predicateId() : null, flag, action, input.reason(), input.quote(),
                targetJson, status, detail);
        UUID newFactId = null;
        if ("applied".equals(status) && target != null) {
            newFactId = apply(kbId, action, factId, input.documentId(), target, input.quote());
            if (newFactId != null) {
                mapper.setNewFact(id, newFactId);
            }
        }
        return new Recorded(id, status, detail, newFactId);
    }

    private Result<Target, String> resolveTarget(UUID kbId, ActionInput input, Candidate c) {
        if (input.proposed() instanceof Keep) {
            return Result.ok(null);
        }
        if (input.proposed() instanceof Retract) {
            return Result.ok(new Target(c.subjectId(), c.predicateId(), c.objectId(), null,
                    c.subject(), c.property(), c.object()));
        }
        if (input.proposed() instanceof Revise r) {
            return resolveRevision(kbId, input, c, r);
        }
        if (input.proposed() instanceof Add a) {
            return resolveAddition(kbId, input, a);
        }
        return Result.err("unknown proposal");
    }

    /** errata.rs:407-470 Revise 的解析（换属性/换宾语至少一样；改与旧值一样不算改）。 */
    private Result<Target, String> resolveRevision(UUID kbId, ActionInput input, Candidate c,
                                                   Revise r) {
        UUID predicateId;
        String kind;
        if (r.property() == null || r.property().trim().isEmpty()) {
            predicateId = c.predicateId();
            kind = null;
        } else {
            String key = r.property().trim();
            ErrataMapper.PropertyRow p = propertyByKey(kbId, key);
            if (p == null) {
                return Result.err("no property with key \"" + key + "\"");
            }
            predicateId = p.id();
            kind = p.kind();
        }
        if (kind == null) {
            kind = mapper.selectPropertyKind(predicateId);
        }
        ErrataMapper.ObjectRow old = mapper.selectFactObject(c.factId());
        Result<Target.Pair, String> obj = r.object() != null
                ? objectOf(kbId, kind, r.object(), input.documentText())
                : Result.ok(new Target.Pair(old.objectId(), old.objectValue()));
        if (!obj.isOk()) {
            return Result.err(obj.error());
        }
        if (emptyStateRevision(c.factId(), predicateId)) {
            return Result.err("a state cannot take a time whose start and end are equal");
        }
        UUID objectId = obj.value().objectId();
        JsonNode objectValue = obj.value().objectValue();
        if (predicateId.equals(c.predicateId()) && java.util.Objects.equals(objectId, old.objectId())
                && java.util.Objects.equals(objectValue, old.objectValue())) {
            return Result.err("the revision changes nothing");
        }
        String property = r.property() != null ? r.property().trim() : c.property();
        return Result.ok(new Target(c.subjectId(), predicateId, objectId, objectValue,
                c.subject(), property,
                r.object() != null ? r.object() : c.object()));
    }

    /** errata.rs:471-495 Add 的解析。 */
    private Result<Target, String> resolveAddition(UUID kbId, ActionInput input, Add a) {
        Result<UUID, String> subjectId = thingNamed(kbId, a.subject().trim(), input.documentText());
        if (!subjectId.isOk()) {
            return Result.err(subjectId.error());
        }
        ErrataMapper.PropertyRow p = propertyByKey(kbId, a.property());
        if (p == null) {
            return Result.err("no property with key \"" + a.property().trim() + "\"");
        }
        Result<Target.Pair, String> obj = objectOf(kbId, p.kind(), a.object(), input.documentText());
        if (!obj.isOk()) {
            return Result.err(obj.error());
        }
        return Result.ok(new Target(subjectId.value(), p.id(), obj.value().objectId(),
                obj.value().objectValue(), a.subject().trim(), a.property().trim(),
                a.object().trim()));
    }

    /**
     * errata.rs:606-697 {@code apply}：动图。撤是作废（reject_fact，证据不动，台账留痕）；
     * 改是撤旧写新，新行接旧行的时间；加是写一行，锚在文档的日期上。新行的证据是引文
     * 所在的分块（引文跨块时退到文档的第一块）。已经不在的行（别处先撤了）不算错——
     * 要的结果已经在了。
     */
    UUID apply(UUID kbId, String action, UUID factId, UUID documentId, Target target,
               String quote) {
        ErrataMapper.ValidityRow v = null;
        if ("retract".equals(action) || "revise".equals(action)) {
            if (factId == null) {
                throw AppException.validation("no fact to retract");
            }
            v = mapper.selectValidityOf(factId);
            if ("revise".equals(action) && emptyStateRevision(factId, target.predicateId())) {
                throw WorldTime.emptyStateSpan();
            }
            try {
                writes.rejectFact(kbId, factId);
            } catch (AppException e) {
                // 已经不在的行不算错（graph.rs reject_fact 的 NotFound 分支）
                if (e != AppException.NOT_FOUND && e.kind() != AppException.Kind.NOT_FOUND) {
                    throw e;
                }
            }
        }
        if ("retract".equals(action)) {
            return null;
        }
        OffsetDateTime docTime = mapper.selectDocTime(documentId);
        Validity validity = new Validity(
                v != null ? v.validFrom() : null,
                v != null ? v.validFromPrecision() : null,
                v != null ? v.validTo() : null,
                v != null ? v.validToPrecision() : null,
                v != null && v.attestedFrom() != null ? v.attestedFrom() : docTime,
                v != null ? v.validFromGrade() : null);
        UUID newId;
        if (target.objectId() != null) {
            newId = writes.insertFact(kbId, target.subjectId(), target.predicateId(),
                    target.objectId(), validity, 0.9f).id();
        } else if (target.objectValue() != null) {
            newId = writes.insertValueFact(kbId, target.subjectId(), target.predicateId(),
                    target.objectValue(), validity, 0.9f).id();
        } else {
            throw AppException.validation("a fact needs an object");
        }
        int placed = mapper.insertEvidenceInChunk(newId, documentId,
                quote == null ? "" : quote, false);
        if (placed == 0) {
            mapper.insertEvidenceInChunk(newId, documentId, quote == null ? "" : quote, true);
        }
        return newId;
    }

    /**
     * errata.rs:702-713 {@code empty_state_revision}：改是撤旧写新，新行接旧行的时间。
     * 旧行两端相等（一刻的事件，或 #966 之前写下的状态）、新行的属性又声明成状态时，
     * 接过来的那段任何时刻都不成立：验证时就拒，执行时先查再撤——不会撤了旧行才发现
     * 新行写不进。
     */
    boolean emptyStateRevision(UUID factId, UUID predicateId) {
        Boolean point = mapper.selectIsMoment(factId);
        return Boolean.TRUE.equals(point)
                && temporal.predicateTemporal(predicateId) == Temporal.STATE;
    }

    /** errata.rs:741-755 held：闸门留给人的一笔。 */
    public List<ErrataHeldItem> held(UUID kbId, long limit, long offset) {
        return heldItems(mapper.selectHeld(kbId, limit, offset));
    }

    private List<ErrataHeldItem> heldItems(List<ErrataMapper.HeldItem> rows) {
        // entity.ErrataHeldItem 是 M2 立的视图形状；行到视图的搬运（proposed/reason 等）
        return rows.stream().<ErrataHeldItem>map(r -> new ErrataHeldItem(
                r.id(), r.documentId(), r.document(), r.action(), r.flag(), r.factId(),
                r.proposed(), r.reason(), r.quote(), r.detail(), r.createdAt())).toList();
    }

    /** errata.rs:758-765 waiting：等人的几笔，最老的从什么时候起等。 */
    public ErrataMapper.WaitingRow waiting(UUID kbId) {
        return mapper.selectWaiting(kbId);
    }

    /**
     * errata.rs:777-831 {@code decide_held}：人答留给人的一笔——批了就执行（held 时存下的
     * target），否了就记 rejected。不是 held 的（已经答过、别的库的）回 false。
     */
    public boolean decideHeld(UUID kbId, UUID actionId, boolean approve, UUID actor) {
        HeldRow row = mapper.selectHeldAction(actionId, kbId);
        if (row == null) {
            return false;
        }
        UUID newFactId = null;
        if (approve) {
            Target target = Target.fromJson(row.proposed());
            if (target == null) {
                throw AppException.validation("the held action has no target");
            }
            newFactId = apply(kbId, row.action(), row.factId(), row.documentId(), target,
                    row.quote());
        }
        mapper.decideHeld(actionId, kbId, approve ? "applied" : "rejected", newFactId, actor);
        return true;
    }
}
