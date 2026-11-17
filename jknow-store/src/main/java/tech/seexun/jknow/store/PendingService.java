package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.PendingFactView;
import tech.seexun.jknow.store.mapper.PendingMapper;
import tech.seexun.jknow.store.mapper.ReviewMapper;
import tech.seexun.jknow.store.temporal.Side;
import tech.seexun.jknow.store.temporal.TemporalService;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 等人点头的事实（pending.rs 头注，决定 0015/表迁移 0018）——一句记忆抽出来的三元组
 * 先落 {@code pending_facts}，<b>不进 facts</b>：不上图、不参与检索、不进推理。
 *
 * <p><b>为什么另一张表而不是 facts 加一列</b>：五十多处查询按 {@code invalidated_at
 * IS NULL} 捞活事实，逐个补过滤漏一处就有一条没人点头的事实混进图里——而这张表存在的
 * 全部理由就是防这件事。分开之后忘了读它的后果是「看不见待确认队列」，方向对了。
 *
 * <p><b>只拦交互式的单条写入</b>：批量摄入仍旧乐观写入 + 事后审阅——那条路不经这里。
 *
 * <p><b>两种形状</b>（0044 第一刀，迁移 0062）：phrase 为空是老的带本体形状（谓词、
 * 算出来的 valid_*），点头走 {@code insert_fact} + 时态对账；phrase 非空是一条开放
 * 陈述——文档自己的关系短语、按角色词记的属性、照抄的时间词、引文在块里的位置——
 * 点头走 {@code insert_open_statement}，不问本体、不碰世界轴。
 *
 * <p><b>不包事务</b>（Rust 原样，pending.rs:293-294）：{@code insert_fact} 按
 * (主语, 谓词, 宾语) 对活事实去重，中途断掉再点一次只会补证据、不会落第二条，删行在
 * 最后——幂等靡有余悸。
 */
@Service
public class PendingService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PendingMapper pending;
    private final ReviewMapper review;
    private final GraphWriteService writes;
    private final TemporalService temporal;
    private final TimeMentionsService timeMentions;

    public PendingService(PendingMapper pending, ReviewMapper review, GraphWriteService writes,
                          TemporalService temporal, TimeMentionsService timeMentions) {
        this.pending = pending;
        this.review = review;
        this.writes = writes;
        this.temporal = temporal;
        this.timeMentions = timeMentions;
    }

    /**
     * 抽取器交过来的一条提议（pending.rs:28-54 Proposal）。字段与 insert_fact /
     * insert_value_fact 对齐，多出 proposed_predicate（模型原话）、chunk_id（那句记忆）、
     * proposed_by（谁说的）、proposed_token（MCP 令牌，0026）。
     */
    public record Proposal(UUID kbId,
                           UUID subjectId,
                           /** null = 本体里没有对应的关系（0010）——人要看见的正是这个空。 */
                           UUID predicateId,
                           UUID objectId,
                           JsonNode objectValue,
                           String proposedPredicate,
                           Validity validity,
                           float confidence,
                           UUID chunkId,
                           UUID proposedBy,
                           UUID proposedToken,
                           /** 非 null = 一条开放陈述（0044）：文档自己的关系短语，照写。 */
                           String phrase,
                           /** 按文档角色词记的属性 [{role,value|entity_id}]（开放陈述专用）。 */
                           JsonNode qualifiers,
                           /** 照抄的时间词 [{text,char_start[,role]}]（开放陈述专用）。 */
                           JsonNode timeWords,
                           /** 引文在块里的字符偏移 (start,end)，服务端搜文本算出。 */
                           int[] quoteSpan) {
    }

    /**
     * 提议的去向（pending.rs:58-67 Outcome）。三种「没提」都不是错误——重抽一句记忆
     * 会再次算出同样的三元组，不挡住就等于每抽一次都把人的决定抹掉一次。
     */
    public record Outcome(Kind kind, UUID id) {

        public enum Kind { PROPOSED, ALREADY_ASSERTED, ALREADY_PENDING, REJECTED }

        public static Outcome proposed(UUID id) {
            return new Outcome(Kind.PROPOSED, id);
        }

        public static Outcome alreadyAsserted() {
            return new Outcome(Kind.ALREADY_ASSERTED, null);
        }

        public static Outcome alreadyPending() {
            return new Outcome(Kind.ALREADY_PENDING, null);
        }

        public static Outcome rejected() {
            return new Outcome(Kind.REJECTED, null);
        }

        /** Rust {@code matches!(o, Outcome::Proposed(_))} 的等价点。 */
        public boolean isProposed() {
            return kind == Kind.PROPOSED;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Outcome that && kind == that.kind && Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(kind, id);
        }

        @Override
        public String toString() {
            return id == null ? kind.name() : kind.name() + "(" + id + ")";
        }
    }

    /**
     * pending.rs:69-199 {@code propose}：前置三查 + 落队。
     *
     * <p>三查的顺序与语义（recon §1.3）：①活开放行/活事实同键 → AlreadyAsserted；
     * ②pending_facts 同键 → AlreadyPending；③rejected_facts 按 (主语, 谓词/短语,
     * 宾语实体) → Rejected——<b>字面值不查拒收表</b>：按 (主语, 谓词) 挡会把「拒过
     * 薪水 28000」扩大成「薪水属性永不再提」，宁可多问一次，不替人拒掉一个新值。
     */
    public Outcome propose(Proposal p) {
        // 开放陈述的短语照写但去掉首尾空白——insert_open_statement 也这么做，空短语在
        // 这里就挡下，别让一条点头时才会失败的行进队列（pending.rs:70-78）
        String phrase = p.phrase() == null ? null : p.phrase().trim();
        if (phrase != null && phrase.isEmpty()) {
            throw AppException.invalid("phrase_missing",
                    "an open statement keeps the document's relation phrase");
        }
        Integer asserted = phrase != null
                ? pending.selectAssertedOpen(p.kbId(), p.subjectId(), phrase, p.objectId(),
                        p.objectValue())
                : pending.selectAssertedTyped(p.kbId(), p.subjectId(), p.predicateId(),
                        p.objectId(), p.objectValue());
        if (asserted != null) {
            return Outcome.alreadyAsserted();
        }
        if (pending.selectPendingDup(p.kbId(), p.subjectId(), p.predicateId(), p.objectId(),
                p.objectValue(), phrase) != null) {
            return Outcome.alreadyPending();
        }
        if (p.objectId() != null
                && pending.selectRejected(p.kbId(), p.subjectId(), p.predicateId(),
                        p.objectId(), phrase) != null) {
            return Outcome.rejected();
        }
        UUID id = Uuids.nowV7();
        pending.insertPending(id, p.kbId(), p.subjectId(), p.predicateId(), p.objectId(),
                p.objectValue(), p.proposedPredicate(), p.validity().from,
                p.validity().fromPrecision, p.validity().to, p.validity().toPrecision,
                p.confidence(), p.chunkId(), p.proposedBy(), p.proposedToken(), phrase,
                p.qualifiers(), p.timeWords(),
                p.quoteSpan() == null ? null : p.quoteSpan()[0],
                p.quoteSpan() == null ? null : p.quoteSpan()[1]);
        return Outcome.proposed(id);
    }

    /** pending.rs:219-233 list（读面落 ReviewMapper，M1 已有——此处仅转发保持域内完整）。 */
    public List<PendingFactView> list(UUID kbId, long limit, long offset) {
        return review.selectPendingFacts(kbId, limit, offset);
    }

    /** pending.rs:236-248 for_chunk：一句记忆抽出的全部待确认项——对话里那张卡片按这个取。 */
    public List<PendingFactView> forChunk(UUID kbId, UUID chunkId) {
        return review.selectPendingForChunk(kbId, chunkId);
    }

    /** 决策台账要的自包含快照（pending.rs:259-276）：行删了之后台账上还读得出当时
     * 确认或拒绝的是什么。 */
    private static ObjectNode snapshot(PendingFactView v, ObjectMapper json) {
        ObjectNode out = json.createObjectNode();
        out.set("subject", json.valueToTree(v.subjectName()));
        out.set("predicate", json.valueToTree(v.predicateLabel()));
        out.set("proposed_predicate", json.valueToTree(v.proposedPredicate()));
        out.set("phrase", json.valueToTree(v.phrase()));
        out.set("object", json.valueToTree(v.objectName()));
        out.set("object_value", v.objectValue());
        out.set("qualifiers", v.qualifiers());
        out.set("time_words", v.timeWords());
        // serde 的 DateTime 序列化成 RFC3339 文本——OffsetDateTime.toString() 同形
        out.set("valid_from", v.validFrom() == null ? json.nullNode()
                : com.fasterxml.jackson.databind.node.TextNode.valueOf(v.validFrom().toString()));
        out.set("valid_to", v.validTo() == null ? json.nullNode()
                : com.fasterxml.jackson.databind.node.TextNode.valueOf(v.validTo().toString()));
        out.put("confidence", v.confidence());
        out.set("quote", json.valueToTree(v.quote()));
        out.set("proposed_by", json.valueToTree(v.proposedByName()));
        return out;
    }

    /** pending.rs:278-287 Confirmed：点头之后的回报。 */
    public record Confirmed(UUID factId,
                            /** 那句记忆所在的文档：点头之后按它重排时间解析（0045）。 */
                            UUID documentId,
                            /** false = 图上已有同一条活事实，这次只补了证据。 */
                            boolean created,
                            /** 时态对账拿不准、进了 fact_conflicts 的条数；开放陈述不对账，恒为 0。 */
                            int conflicts,
                            JsonNode snapshot) {
    }

    /**
     * pending.rs:295-407 {@code confirm}：人点头——按抽取那条路进账本（落事实、挂证据、
     * 时态对账），然后从队列里拿掉。<b>置信度不动</b>：人的态度不用浮点数表达（0011 的
     * 教训），它在审计台账里（HTTP 端点 M4 落）。开放陈述走 {@link #confirmOpen}。
     */
    public Confirmed confirm(UUID kbId, UUID id) {
        PendingFactView v = get(kbId, id);
        if (v.phrase() != null) {
            return confirmOpen(kbId, v, v.phrase());
        }
        // 锚点是那句记忆所在文档的日期（0022）——与抽取那条路同一个来源。没起点的
        // 事实从有证据的那一刻起成立，而证据就是这句话
        OffsetDateTime attestedAt = pending.selectChunkDocTime(v.chunkId());
        Validity validity = new Validity(v.validFrom(), v.validFromPrecision(), v.validTo(),
                v.validToPrecision(), attestedAt,
                // 待定的事实是人或代理提的，日期是直接给的，没经过时间解析
                null);
        if (v.objectId() == null && v.objectValue() == null) {
            throw noObject();
        }
        GraphWriteService.FactWrite written = v.objectId() != null
                ? writes.insertFact(kbId, v.subjectId(), v.predicateId(), v.objectId(),
                        validity, v.confidence())
                : writes.insertValueFact(kbId, v.subjectId(), v.predicateId(), v.objectValue(),
                        validity, v.confidence());
        // 证据指回那句记忆。引句就是整句——一条 episode 本来就只有一句
        writes.addEvidence(written.id(), v.chunkId(), v.quote(), v.proposedPredicate());

        // 与抽取相同的时态对账：带唯一性约束的状态关系，新事实闭合旧事实。这正是记忆
        // 该有的行为——「Mira 交给 Devin 了」说完，Mira 那条就该闭合。并进已有断言的
        // 也对：多了一次观察，时间线的形状可能跟着变（#679）
        int conflicts = 0;
        if (v.predicateId() != null) {
            PendingMapper.RelationMeta meta = pending.selectRelationMeta(v.predicateId());
            if (meta != null && "state".equals(meta.temporal())) {
                List<Side> directions = new ArrayList<>();
                if (meta.functional()) {
                    directions.add(Side.SUBJECT_SIDE);
                }
                // 宾语侧唯一只对实体宾语有意义；字面值没有「谁被指着」
                if (meta.inverseFunctional() && v.objectId() != null) {
                    directions.add(Side.OBJECT_SIDE);
                }
                for (Side dir : directions) {
                    conflicts += temporal.reconcileNewFact(kbId, written.id(), v.subjectId(),
                            v.predicateId(), v.objectId(), dir).conflicts;
                }
            }
        }
        pending.deletePending(id);
        return new Confirmed(written.id(), pending.selectDocumentOfChunk(v.chunkId()),
                written.created(), conflicts, snapshot(v, JSON));
    }

    /**
     * pending.rs:418-534 {@code confirm_open}：人对一条开放陈述点头（0044）——按开放
     * 抽取那条路进账本（insert_open_statement、带偏移的证据、角色词属性、时间词），
     * 然后从队列里拿掉。<b>不问本体、不做时态对账</b>：开放陈述没有谓词，
     * relation_types 上没有它的 functional/temporal 可查；它提到的时间是照抄的字，
     * 在世界轴上还没有位置，没有 valid_* 可以拿去闭合谁（0045 后面几刀的事）。
     */
    private Confirmed confirmOpen(UUID kbId, PendingFactView v, String phrase) {
        // 记录轴的锚点只认文档自己说的日期（#714）：记忆文档是 'none'，这里为空，落成
        // 此刻——人此刻点了头的话，人就是证据。抽取那条路没有这个缺省（0064 决定 5）：
        // 那里没有人在场，文档没说的就是没有
        OffsetDateTime attestedAt = pending.selectChunkDocTimeStated(v.chunkId());
        if (attestedAt == null) {
            attestedAt = OffsetDateTime.now();
        }
        if (v.objectId() == null && v.objectValue() == null) {
            throw noObject();
        }
        GraphWriteService.FactWrite written = v.objectId() != null
                ? writes.insertOpenStatement(kbId, v.subjectId(), phrase, v.objectId(), null,
                        attestedAt, v.confidence())
                : writes.insertOpenStatement(kbId, v.subjectId(), phrase, null, v.objectValue(),
                        attestedAt, v.confidence());
        // 证据指回那句记忆，带着引文在块里的位置；proposed_predicate = phrase，于是所有
        // 已经容得下空谓词的读路径不改一字就按短语显示它。引文是**那句话**，不是整块：
        // 待确认项上的 quote 是块的全文（带时间戳前缀），有偏移时按偏移截出这一句，
        // 偏移才和引文说同一件事（证据表的 quote 只留 120 字）
        Integer start = v.quoteStart();
        Integer end = v.quoteEnd();
        int[] span = start != null && end != null ? new int[]{start, end} : null;
        String sentence = span != null && start >= 0 && end > start
                ? sliceByCodePoints(v.quote(), start, end)
                : v.quote();
        writes.addEvidenceLocated(written.id(), v.chunkId(), sentence, phrase, span);
        for (JsonNode q : jsonItems(v.qualifiers())) {
            String role = q.get("role") == null || !q.get("role").isTextual() ? null
                    : q.get("role").asText();
            if (role == null) {
                throw AppException.invalid("qualifier_role",
                        "a statement qualifier needs the document's role word");
            }
            // 带 entity_id 的指向一个实体；否则 value 就是文档里的那个值，原样落成 JSON
            String entityIdRaw = q.get("entity_id") != null && q.get("entity_id").isTextual()
                    ? q.get("entity_id").asText() : null;
            UUID entityId;
            try {
                entityId = entityIdRaw == null ? null : UUID.fromString(entityIdRaw);
            } catch (IllegalArgumentException e) {
                throw AppException.invalid("qualifier_entity",
                        "a statement qualifier's entity_id must be a uuid");
            }
            writes.addStatementQualifier(written.id(), role,
                    entityId != null ? null : q.get("value"), entityId);
        }
        for (JsonNode t : jsonItems(v.timeWords())) {
            JsonNode textNode = t.get("text");
            JsonNode startNode = t.get("char_start");
            if (textNode == null || !textNode.isTextual() || startNode == null
                    || !startNode.canConvertToInt()) {
                throw AppException.invalid("time_word_shape",
                        "a time word is the document's text and its char offset in the chunk");
            }
            // 来自陈述的哪个槽；0062 之前记下的项没有这一字段，它们都是 when
            String role = t.get("role") != null && t.get("role").isTextual()
                    ? t.get("role").asText() : "when";
            timeMentions.record(kbId, written.id(), v.chunkId(), textNode.asText(),
                    startNode.asInt(), role);
        }
        pending.deletePending(v.id());
        return new Confirmed(written.id(), pending.selectDocumentOfChunk(v.chunkId()),
                written.created(), 0, snapshot(v, JSON));
    }

    /** pending.rs:250-257 get：单条（拿不到 = NotFound）。 */
    public PendingFactView get(UUID kbId, UUID id) {
        PendingFactView v = pending.selectById(kbId, id);
        if (v == null) {
            throw AppException.NOT_FOUND;
        }
        return v;
    }

    /**
     * pending.rs:564-588 {@code reject}：人拒绝——记进 rejected_facts（下一轮重抽先查
     * 它），从队列里拿掉。那句记忆本身留着：人确实说过那句话，只是没抽出可用的事实。
     * 开放陈述连短语一起记：拒的是「这句话」，不是这一对实体之间的一切。
     */
    public JsonNode reject(UUID kbId, UUID id, UUID rejectedBy) {
        PendingFactView v = get(kbId, id);
        pending.insertRejected(kbId, v.subjectId(), v.predicateId(), v.objectId(), rejectedBy,
                v.phrase());
        pending.deletePending(id);
        return snapshot(v, JSON);
    }

    private static AppException noObject() {
        return AppException.invalid("pending_fact_has_no_object",
                "a pending fact must have an object entity or a literal value");
    }

    /** pending.rs:544-549 json_items：一列 JSON 对象；不是数组（或没有）就是空。 */
    private static Iterable<JsonNode> jsonItems(JsonNode v) {
        return v != null && v.isArray() ? (ArrayNode) v : List.<JsonNode>of();
    }

    /**
     * Rust {@code v.quote.chars().skip(start).take(len).collect()} 的等价点——按
     * Unicode 码点截（chars() 是码点迭代），不是按 char/字节。
     */
    private static String sliceByCodePoints(String s, int start, int end) {
        StringBuilder out = new StringBuilder();
        int idx = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            if (idx >= start && idx < end) {
                out.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
            idx++;
        }
        return out.toString();
    }
}
