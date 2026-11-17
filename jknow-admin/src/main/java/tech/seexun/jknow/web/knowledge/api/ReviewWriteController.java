package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.governance.GovernanceHooks;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.ErrataService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.ImplicationRulesService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.ReviewWriteMapper;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 审核队列 HTTP 写面（M4 任务 2——对齐旧仓库 review_routes.rs 的 19 个 POST，权限/
 * 请求响应形状/错误码逐字；读面四 GET 在 {@link ReviewController}）。每个端点同一套
 * 编排三件套：<b>前置快照 → store 动作 → audit 台账 → governance 钩子 → SSE emit</b>。
 *
 * <p>快照永远在动作之前取（review_routes.rs:26 头注）：reject 之后事实从图里消失、
 * merge 之后 source 一侧的名字已不在原处——决策台账必须自包含展示文本。台账失败不
 * 影响业务（AuditService 内吞）；emit 一样（AppEventBus 无订阅者即 no-op）。
 *
 * <p>两个 202 端点（decide_alignment_phrase/rule）语义：<b>判定已收货照办，重活异步</b>
 * ——判定和它的重算任务一次提交，答 job id；不编一个 typed:{added:0} 出来，那不是
 * 这次请求知道的事。批量裁决逐条独立：一条失败不拖累其余的，结果逐条带回。
 */
@RestController
public class ReviewWriteController {

    /** review_routes.rs:512 BATCH_MAX：一批 id 最多 500——够一屏全选，挡住一次请求把库锁上几分钟。 */
    private static final int BATCH_MAX = 500;

    private static final List<String> WORLD_PRECISIONS =
            List.of("year", "month", "day", "hour", "minute", "second");

    private final KbAccess rbac;
    private final ReviewWriteMapper snaps;
    private final ResolutionService resolution;
    private final PendingService pending;
    private final GraphWriteService graph;
    private final TemporalService temporal;
    private final ReasoningService reasoning;
    private final MappingsService mappings;
    private final ErrataService errata;
    private final PhraseBindingsService phraseBindings;
    private final TypeBindingsService typeBindings;
    private final ImplicationRulesService implicationRules;
    private final OntologyService ontology;
    private final JobsService jobs;
    private final AuditService audit;
    private final AppEventBus events;
    private final GovernanceHooks governance;
    private final tech.seexun.jknow.store.GovernanceService governanceStore;
    private final ObjectMapper mapper;

    public ReviewWriteController(KbAccess rbac, ReviewWriteMapper snaps,
                                 ResolutionService resolution, PendingService pending,
                                 GraphWriteService graph, TemporalService temporal,
                                 ReasoningService reasoning, MappingsService mappings,
                                 ErrataService errata, PhraseBindingsService phraseBindings,
                                 TypeBindingsService typeBindings,
                                 ImplicationRulesService implicationRules,
                                 OntologyService ontology, JobsService jobs, AuditService audit,
                                 AppEventBus events, GovernanceHooks governance,
                                 tech.seexun.jknow.store.GovernanceService governanceStore,
                                 ObjectMapper mapper) {
        this.rbac = rbac;
        this.snaps = snaps;
        this.resolution = resolution;
        this.pending = pending;
        this.graph = graph;
        this.temporal = temporal;
        this.reasoning = reasoning;
        this.mappings = mappings;
        this.errata = errata;
        this.phraseBindings = phraseBindings;
        this.typeBindings = typeBindings;
        this.implicationRules = implicationRules;
        this.ontology = ontology;
        this.jobs = jobs;
        this.audit = audit;
        this.events = events;
        this.governance = governance;
        this.governanceStore = governanceStore;
        this.mapper = mapper;
    }

    // ---- 公共响应形状 ----

    record OkResponse(boolean ok) {
    }

    record MergeIdResponse(UUID mergeId) {
    }

    // ---- 请求体（Rust #[serde(default)] 的可缺省键沿用 JobsController 的自定义反序列化先例：
    //      全局 fail-on-missing-creator-properties 对记录组件不豁免可缺省项） ----

    private static JsonNode tree(com.fasterxml.jackson.core.JsonParser p) throws java.io.IOException {
        return p.getCodec().readTree(p);
    }

    private static String text(JsonNode n, String f) {
        return n.hasNonNull(f) && n.get(f).isTextual() ? n.get(f).asText() : null;
    }

    private static OffsetDateTime time(com.fasterxml.jackson.core.JsonParser p,
                                       com.fasterxml.jackson.databind.DeserializationContext c,
                                       JsonNode n, String f) throws java.io.IOException {
        String s = text(n, f);
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(OffsetDateTime.class,
                    "Cannot deserialize value of type `java.time.OffsetDateTime` from String "
                            + "\"" + s + "\"");
        }
    }

    /** 必填键缺失 → serde 对等文案（AppExceptionHandler 译 "missing field `x`"）。 */
    private static String required(com.fasterxml.jackson.core.JsonParser p,
                                   com.fasterxml.jackson.databind.DeserializationContext c,
                                   JsonNode n, String f) throws java.io.IOException {
        String v = text(n, f);
        if (v == null && !n.hasNonNull(f)) {
            throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(String.class,
                    "Missing required creator property '" + f + "'");
        }
        return v;
    }

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = DecideBody.Deser.class)
    record DecideBody(String action, String rationale) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<DecideBody> {
            @Override
            public DecideBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                          com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                return new DecideBody(required(p, c, n, "action"), text(n, "rationale"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/{reviewId}")
    public OkResponse decide(@PathVariable UUID kbId, @PathVariable UUID reviewId,
                             @RequestBody DecideBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ReviewWriteMapper.DecideSnapshotRow snap = snaps.selectDecideSnapshot(reviewId, kbId);
        resolution.decideReview(kbId, reviewId, body.action(), user.id(), body.rationale());
        if (snap != null) {
            // 理由也进台账：先例是从这里读的，不是从 resolution_reviews
            audit.record(client(request), kbId, user.id(),
                    "merge".equals(body.action()) ? "review.merge" : "review.keep",
                    "review", reviewId, detail()
                            .put("left", snap.left())
                            .put("right", snap.right())
                            .put("score", snap.score())
                            .put("why", body.rationale())
                            .toString());
        }
        governance.afterHumanDecision(kbId, List.of(reviewId), body.action(), user.id());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 2. POST /kbs/{id}/review/batch（review_routes.rs:516-588 batch） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = BatchBody.Deser.class)
    record BatchBody(List<UUID> ids, String action, String rationale) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<BatchBody> {
            @Override
            public BatchBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                         com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                // ids 键缺失按 serde "missing field `ids`" 报（审查 M-3：Rust 422 无 code，
                // 不落 batch_size）；数组内非文本元素拒收而非静默丢（serde UUID 反序列化同口径）
                if (!n.has("ids") || !n.get("ids").isArray()) {
                    throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(
                            BatchBody.class, "missing field `ids`");
                }
                List<UUID> ids = new ArrayList<>();
                for (JsonNode x : n.get("ids")) {
                    if (!x.isTextual()) {
                        throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(
                                BatchBody.class, "ids 元素必须是 uuid 字符串");
                    }
                    ids.add(UUID.fromString(x.asText()));
                }
                return new BatchBody(ids, required(p, c, n, "action"), text(n, "rationale"));
            }
        }
    }

    record BatchResponse(int decided, List<ResolutionService.ReviewBatchOutcome> outcomes) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/batch")
    public BatchResponse batch(@PathVariable UUID kbId, @RequestBody BatchBody body,
                               HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        if (body.ids() == null || body.ids().isEmpty() || body.ids().size() > BATCH_MAX) {
            throw AppException.invalid("batch_size", "between 1 and " + BATCH_MAX + " ids per batch");
        }
        // 台账要的名字与分数在裁决前取——合并之后 source 一侧的名字已经不在原处了
        Map<UUID, ReviewWriteMapper.DecideSnapshotWithIdRow> byId = new HashMap<>();
        for (ReviewWriteMapper.DecideSnapshotWithIdRow s : snaps.selectBatchSnapshots(kbId,
                body.ids())) {
            byId.put(s.id(), s);
        }
        List<ResolutionService.ReviewBatchOutcome> outcomes =
                resolution.decideReviews(kbId, body.ids(), body.action(), user.id(),
                        body.rationale());
        String action = "merge".equals(body.action()) ? "review.merge" : "review.keep";
        List<UUID> decidedIds = new ArrayList<>();
        for (ResolutionService.ReviewBatchOutcome o : outcomes) {
            if (o.error() == null) {
                decidedIds.add(o.id());
                ReviewWriteMapper.DecideSnapshotWithIdRow s = byId.get(o.id());
                if (s != null) {
                    audit.record(client(request), kbId, user.id(), action, "review", o.id(),
                            detail()
                                    .put("left", s.left())
                                    .put("right", s.right())
                                    .put("score", s.score())
                                    .put("batch", body.ids().size())
                                    .put("why", body.rationale())
                                    .toString());
                }
            }
        }
        governance.afterHumanDecision(kbId, decidedIds, body.action(), user.id());
        events.emitReview(kbId);
        return new BatchResponse(decidedIds.size(), outcomes);
    }

    // ---- 3. POST /kbs/{id}/review/agent/{decision_id}（review_routes.rs:383-499 agent_answer） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = AgentAnswerBody.Deser.class)
    record AgentAnswerBody(String action, String rationale) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<AgentAnswerBody> {
            @Override
            public AgentAnswerBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                               com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                return new AgentAnswerBody(required(p, c, n, "action"), text(n, "rationale"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/agent/{decisionId}")
    public OkResponse agentAnswer(@PathVariable UUID kbId, @PathVariable UUID decisionId,
                                  @RequestBody AgentAnswerBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        var d = governanceStore.get(kbId, decisionId);
        String l = d.left() == null ? "" : d.left();
        String r = d.right() == null ? "" : d.right();
        String status = d.status() == null ? "" : d.status();
        String action = body.action() == null ? "" : body.action();
        if ("proposed".equals(status) && ("merge".equals(action) || "keep".equals(action))) {
            // 回答走的是人的裁决路径：decided_by 是这个人，台账记的是这个人——
            // 它就此成为下一轮的先例
            resolution.decideReview(kbId, d.targetId(), action, user.id(), body.rationale());
            governanceStore.settle(kbId, decisionId, action.equals(d.action())
                    ? "accepted" : "overridden", user.id());
            audit.record(client(request), kbId, user.id(),
                    "merge".equals(action) ? "review.merge" : "review.keep",
                    "review", d.targetId(), detail()
                            .put("left", l)
                            .put("right", r)
                            .put("agent_decision", decisionId.toString())
                            .put("agent_action", d.action())
                            .put("why", body.rationale())
                            .toString());
        } else if ("applied".equals(status) && "revert".equals(action)) {
            if (d.mergeId() == null) {
                throw AppException.invalid("not_a_merge",
                        "Only an applied merge can be reverted; a pair kept apart can be merged"
                                + " instead.");
            }
            resolution.revertMerge(kbId, d.mergeId());
            governanceStore.settle(kbId, decisionId, "reverted", user.id());
            audit.record(client(request), kbId, user.id(), "merge.revert", "merge", d.mergeId(),
                    detail()
                            .put("source", l)
                            .put("target", r)
                            .put("agent_decision", decisionId.toString())
                            .toString());
            // 撤回 agent 的合并到了两次：保险丝
            governance.fuse(kbId);
        } else if ("applied".equals(status) && "merge".equals(action)
                && "keep".equals(d.action())) {
            // merge 也能推翻一条自动分开
            ReviewWriteMapper.ReviewPairRow pair = snaps.selectReviewPair(d.targetId(), kbId);
            if (pair == null) {
                throw AppException.NOT_FOUND;
            }
            ResolutionService.MergeDirection dir =
                    resolution.mergeDirection(pair.leftId(), pair.rightId());
            UUID mergeId = resolution.mergeEntities(kbId, dir.source(), dir.target(), user.id(),
                    "overrode agent keep");
            governanceStore.settle(kbId, decisionId, "overridden", user.id());
            audit.record(client(request), kbId, user.id(), "merge.manual", "merge", mergeId,
                    detail()
                            .put("source", l)
                            .put("target", r)
                            .put("agent_decision", decisionId.toString())
                            .toString());
        } else {
            throw AppException.invalid("agent_answer",
                    "cannot " + action + " an agent decision that is " + status);
        }
        governance.afterHumanDecision(kbId, List.of(d.targetId()), null, user.id());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 4. POST /kbs/{id}/review/pending/{pending_id}（review_routes.rs:1109-1170） ----

    record DecidePendingBody(String action) {
    }

    record ConfirmPendingResponse(boolean ok, UUID factId, boolean created, int conflicts) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/pending/{pendingId}")
    public Object decidePending(@PathVariable UUID kbId, @PathVariable UUID pendingId,
                                @RequestBody DecidePendingBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String action = body.action() == null ? "" : body.action();
        switch (action) {
            case "confirm" -> {
                PendingService.Confirmed done = pending.confirm(kbId, pendingId);
                audit.record(client(request), kbId, user.id(), "fact.nod_confirmed", "fact",
                        done.factId(), done.snapshot().toString());
                // 点头落下的开放陈述带着时间词：按文档重排一次时间解析（0045），
                // 幂等，同一篇排着就不重复
                try {
                    jobs.enqueueUnlessQueued("resolve_time",
                            detail().put("document_id", done.documentId().toString()));
                } catch (RuntimeException e) {
                    // Rust `let _ =`：排队失败不拦点头
                }
                events.emitPending(kbId);
                events.emitReview(kbId);
                events.emitGraph(kbId);
                return new ConfirmPendingResponse(true, done.factId(), done.created(),
                        done.conflicts());
            }
            case "reject" -> {
                JsonNode snap = pending.reject(kbId, pendingId, user.id());
                audit.record(client(request), kbId, user.id(), "fact.nod_rejected",
                        "pending_fact", pendingId, snap.toString());
                events.emitPending(kbId);
                events.emitReview(kbId);
                return new OkResponse(true);
            }
            default -> throw AppException.invalid("unknown_action",
                    "action must be confirm or reject, got " + action);
        }
    }

    // ---- 5. POST /kbs/{id}/facts/{fact_id}/confirm（review_routes.rs:590-612） ----

    @PostMapping("/api/v1/kbs/{kbId}/facts/{factId}/confirm")
    public OkResponse confirmFact(@PathVariable UUID kbId, @PathVariable UUID factId,
                                  HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ObjectNode snap = factSnapshot(kbId, factId);
        graph.confirmFact(kbId, factId);
        if (snap != null) {
            audit.record(client(request), kbId, user.id(), "fact.confirm", "fact", factId,
                    snap.toString());
        }
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 6. POST /kbs/{id}/facts/{fact_id}/reject（review_routes.rs:614-636） ----

    @PostMapping("/api/v1/kbs/{kbId}/facts/{factId}/reject")
    public OkResponse rejectFact(@PathVariable UUID kbId, @PathVariable UUID factId,
                                 HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ObjectNode snap = factSnapshot(kbId, factId);
        graph.rejectFact(kbId, factId);
        if (snap != null) {
            audit.record(client(request), kbId, user.id(), "fact.reject", "fact", factId,
                    snap.toString());
        }
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 7. POST /kbs/{id}/facts/{fact_id}/close（review_routes.rs:185-225 close_fact） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = CloseFactBody.Deser.class)
    record CloseFactBody(OffsetDateTime validTo, String validToPrecision) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<CloseFactBody> {
            @Override
            public CloseFactBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                             com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                OffsetDateTime at = time(p, c, n, "valid_to");
                if (at == null) {
                    throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(CloseFactBody.class,
                            "Missing required creator property 'valid_to'");
                }
                return new CloseFactBody(at, text(n, "valid_to_precision"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/facts/{factId}/close")
    public OkResponse closeFact(@PathVariable UUID kbId, @PathVariable UUID factId,
                                @RequestBody CloseFactBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 归属与状态校验：本 KB 的、未作废、开放区间的事实才可闭合
        if (snaps.selectClosableFact(factId, kbId) == null) {
            throw AppException.NOT_FOUND;
        }
        String precision = worldPrecision(body.validToPrecision());
        ObjectNode snap = factSnapshot(kbId, factId);
        temporal.closeSuperseded(factId, body.validTo(), precision);
        if (snap != null) {
            snap.put("valid_to", PhraseBindingsService.rfc3339(body.validTo()));
            snap.put("valid_to_precision", precision);
            audit.record(client(request), kbId, user.id(), "fact.close", "fact", factId,
                    snap.toString());
        }
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 8. POST /kbs/{id}/conflicts/{conflict_id}（review_routes.rs:240-302） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ConflictBody.Deser.class)
    record ConflictBody(String action, OffsetDateTime closeAt, String closeAtPrecision) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<ConflictBody> {
            @Override
            public ConflictBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                            com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                return new ConflictBody(required(p, c, n, "action"), time(p, c, n, "close_at"),
                        text(n, "close_at_precision"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/conflicts/{conflictId}")
    public OkResponse resolveConflict(@PathVariable UUID kbId, @PathVariable UUID conflictId,
                                      @RequestBody ConflictBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 快照双方三元组：裁决会作废/改写事实，先抄后动
        ReviewWriteMapper.ConflictSnapshotRow snap = snaps.selectConflictSnapshot(conflictId,
                kbId);
        String closePrecision = worldPrecision(body.closeAtPrecision());
        temporal.resolveConflict(kbId, conflictId, body.action(), body.closeAt(), closePrecision);
        if (snap != null) {
            String action = switch (body.action() == null ? "" : body.action()) {
                case "close" -> "conflict.close_old";
                case "keep" -> "conflict.keep_both";
                default -> "conflict.reject_new";
            };
            audit.record(client(request), kbId, user.id(), action, "conflict", conflictId,
                    detail()
                            .put("predicate", snap.predicate())
                            .put("old_subject", snap.oldSubject())
                            .put("old_object", snap.oldObject())
                            .put("new_subject", snap.newSubject())
                            .put("new_object", snap.newObject())
                            .put("close_at", body.closeAt() == null ? null
                                    : PhraseBindingsService.rfc3339(body.closeAt()))
                            .put("close_at_precision",
                                    body.closeAt() == null ? null : closePrecision)
                            .toString());
        }
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 9. POST /kbs/{id}/merges/{merge_id}/revert（review_routes.rs:638-674） ----

    @PostMapping("/api/v1/kbs/{kbId}/merges/{mergeId}/revert")
    public OkResponse revertMerge(@PathVariable UUID kbId, @PathVariable UUID mergeId,
                                  HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ReviewWriteMapper.MergeSnapshotRow snap = snaps.selectMergeSnapshot(mergeId, kbId);
        resolution.revertMerge(kbId, mergeId);
        if (snap != null) {
            audit.record(client(request), kbId, user.id(), "merge.revert", "merge", mergeId,
                    detail().put("source", snap.source()).put("target", snap.target()).toString());
        }
        // 撤的是 agent 自己裁的合并：那一行记成 reverted，并看保险丝（0025）
        governance.afterRevert(kbId, mergeId, user.id());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 10. POST /kbs/{id}/entities/merge（review_routes.rs:686-732 manual_merge） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using = ManualMergeBody.Deser.class)
    record ManualMergeBody(UUID source, UUID target, String rationale) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<ManualMergeBody> {
            @Override
            public ManualMergeBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                               com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                String src = text(n, "source");
                String tgt = text(n, "target");
                if (src == null) {
                    throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(ManualMergeBody.class,
                            "Missing required creator property 'source'");
                }
                if (tgt == null) {
                    throw (com.fasterxml.jackson.databind.JsonMappingException) c.reportInputMismatch(ManualMergeBody.class,
                            "Missing required creator property 'target'");
                }
                return new ManualMergeBody(UUID.fromString(src), UUID.fromString(tgt),
                        text(n, "rationale"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/entities/merge")
    public MergeIdResponse manualMerge(@PathVariable UUID kbId, @RequestBody ManualMergeBody body,
                                       HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String pair = snaps.selectManualMergePair(body.source(), body.target());
        String why = body.rationale() == null ? null : body.rationale().trim();
        if (why != null && why.isEmpty()) {
            why = null;
        }
        UUID mergeId = resolution.mergeEntities(kbId, body.source(), body.target(), user.id(),
                why != null ? why : "manual merge");
        if (pair != null) {
            // Rust split_once(" → ")：拆不开就整串当 source、target 空
            int cut = pair.indexOf(" → ");
            String s = cut >= 0 ? pair.substring(0, cut) : pair;
            String t = cut >= 0 ? pair.substring(cut + " → ".length()) : "";
            audit.record(client(request), kbId, user.id(), "merge.manual", "merge", mergeId,
                    detail().put("source", s).put("target", t).put("why", why).toString());
        }
        events.emitReview(kbId);
        return new MergeIdResponse(mergeId);
    }

    // ---- 11. POST /kbs/{id}/review/mappings/{mapping_id}（review_routes.rs:784-811） ----

    record DecideMappingBody(String status) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/mappings/{mappingId}")
    public OkResponse decideMapping(@PathVariable UUID kbId, @PathVariable UUID mappingId,
                                    @RequestBody DecideMappingBody body,
                                    HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        if (!"confirmed".equals(body.status()) && !"rejected".equals(body.status())) {
            throw AppException.invalid("bad_status", "status must be confirmed or rejected");
        }
        // 改状态不删行：确认发生过、拒绝也发生过（拒绝留痕让 propose 不刷回待看）
        mappings.decide(kbId, mappingId, body.status(), user.id());
        audit.record(client(request), kbId, user.id(), "mapping.decided", "concept_mapping",
                mappingId, detail().put("status", body.status()).toString());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 12. POST /kbs/{id}/consistency/check（review_routes.rs:988-1032） ----

    /** 同步跑而不是排任务：纯计算，没有模型调用也没有网络——几万条事实的库是毫秒级。 */
    record ConsistencyResponse(int edges, int predicatesWithAxioms, int found, int inserted,
                               int cleared, int cyclesCapped, int classes, int defectsFound,
                               int defectsNew) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/consistency/check")
    public ConsistencyResponse runConsistencyCheck(@PathVariable UUID kbId,
                                                   HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        ReasoningService.Report report = reasoning.run(kbId);
        // 本体自洽性一并算：两者判据同源（都是本体声明的公理），分两个按钮只会让人点两次
        ReasoningService.OntologyReport onto = reasoning.checkOntology(kbId);
        audit.record(client(request), kbId, user.id(), "consistency.checked", "knowledge_base",
                kbId, detail()
                        .put("edges", report.edges())
                        .put("predicates_with_axioms", report.predicatesWithAxioms())
                        .put("found", report.found())
                        .put("inserted", report.inserted())
                        .put("cleared", report.cleared())
                        .put("cycles_capped", report.cyclesCapped())
                        .put("defects_found", onto.found())
                        .toString());
        events.emitReview(kbId);
        // defects_found 不加进 found：两个数不是一类东西，加起来之后「3 处矛盾」既可能是
        // 三条事实抵触，也可能是本体自己写反了三处
        return new ConsistencyResponse(report.edges(), report.predicatesWithAxioms(),
                report.found(), report.inserted(), report.cleared(), report.cyclesCapped(),
                onto.classes(), onto.found(), onto.inserted());
    }

    // ---- 13. POST /kbs/{id}/review/violations/{violation_id}（review_routes.rs:837-978） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = DecideViolationBody.Deser.class)
    record DecideViolationBody(String resolution, OffsetDateTime closeAt, String closeAtPrecision,
                               UUID factId) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<DecideViolationBody> {
            @Override
            public DecideViolationBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                   com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                String factId = text(n, "fact_id");
                return new DecideViolationBody(required(p, c, n, "resolution"), time(p, c, n, "close_at"),
                        text(n, "close_at_precision"),
                        factId == null ? null : UUID.fromString(factId));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/violations/{violationId}")
    public OkResponse decideViolation(@PathVariable UUID kbId, @PathVariable UUID violationId,
                                      @RequestBody DecideViolationBody body,
                                      HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        String resolution = body.resolution() == null ? "" : body.resolution();
        if (!List.of("fact_retracted", "fact_closed", "axiom_relaxed", "accepted")
                .contains(resolution)) {
            throw AppException.invalid("bad_resolution",
                    "resolution 只能是 fact_retracted、fact_closed、axiom_relaxed 或 accepted");
        }
        ReviewWriteMapper.ViolationRow row = snaps.selectOpenViolation(violationId, kbId);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        boolean repaired = "derived_contradiction".equals(row.kind());
        // 撤事实的那条路自己会把违规标成 resolved；其余出路在下面统一记
        boolean decided = false;
        if ("fact_retracted".equals(resolution)) {
            // 撤哪条由请求指名（fact_id），单事实的种类可省；只能撤违规自己列出的那几条
            UUID target = ReasoningService.pickRetraction(row.leftFact(), row.rightFact(),
                    row.path(), body.factId());
            if (target == null) {
                throw AppException.invalid("fact_required",
                        "这处违规涉及多条事实，要说撤哪一条，且只能是它列出的那几条");
            }
            ObjectNode snap = factSnapshot(kbId, target);
            reasoning.retractFromViolation(kbId, violationId, target, user.id());
            decided = true;
            if (snap != null) {
                audit.record(client(request), kbId, user.id(), "fact.reject", "fact", target,
                        snap.toString());
            }
        } else if ("fact_closed".equals(resolution)) {
            if (!repaired) {
                throw AppException.invalid("bad_resolution", "fact_closed 只用于 derived_contradiction");
            }
            if (body.closeAt() == null) {
                throw AppException.invalid("close_at_required", "fact_closed 要给出结束日期");
            }
            if (snaps.selectOpenFactForClose(row.leftFact()) == null) {
                throw AppException.invalid("not_open", "这条断言已有结束日期，或已被撤");
            }
            String precision = worldPrecision(body.closeAtPrecision());
            ObjectNode snap = factSnapshot(kbId, row.leftFact());
            temporal.closeSuperseded(row.leftFact(), body.closeAt(), precision);
            if (snap != null) {
                snap.put("valid_to", PhraseBindingsService.rfc3339(body.closeAt()));
                snap.put("valid_to_precision", precision);
                audit.record(client(request), kbId, user.id(), "fact.close", "fact",
                        row.leftFact(), snap.toString());
            }
        }
        if (!decided) {
            reasoning.decide(kbId, violationId, resolution, user.id());
        }
        // 路清了就让派生落地，人不必再去点一次「推一遍」。撤与闭合把断言挪开了，
        // 认可则在 materialize 里放行
        if (repaired) {
            reasoning.materialize(kbId);
        }
        // 撤掉的事实可能还挂在别的违规里：重算一遍，那些行随之清掉，队列不留死账
        if ("fact_retracted".equals(resolution)) {
            reasoning.run(kbId);
        }
        audit.record(client(request), kbId, user.id(), "violation.decided", "axiom_violation",
                violationId, detail().put("resolution", resolution).toString());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 14. POST /kbs/{id}/review/defects/{defect_id}（review_routes.rs:1043-1071） ----

    record DecideDefectBody(String resolution) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/defects/{defectId}")
    public OkResponse decideDefect(@PathVariable UUID kbId, @PathVariable UUID defectId,
                                   @RequestBody DecideDefectBody body, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 两个出路而不是三个：本体缺陷压根没看数据，没有「数据错了」这一条
        if (!"fixed".equals(body.resolution()) && !"accepted".equals(body.resolution())) {
            throw AppException.invalid("bad_resolution", "resolution 只能是 fixed 或 accepted");
        }
        reasoning.decideDefect(kbId, defectId, body.resolution(), user.id());
        audit.record(client(request), kbId, user.id(), "defect.decided", "ontology_defect",
                defectId, detail().put("resolution", body.resolution()).toString());
        events.emitReview(kbId);
        return new OkResponse(true);
    }

    // ---- 15. POST /kbs/{id}/inference/run（review_routes.rs:1172-1218） ----

    record InferenceResponse(int rules, int edges, int derived, int inserted, int invalidated,
                             int capped) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/inference/run")
    public InferenceResponse runInference(@PathVariable UUID kbId, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 受 materialize_inferences 开关约束（0001 判据 2「本体是引导不是执法」）：关着
        // 时不静默跳过——回一个明确的错，界面才说得出为什么没动
        Boolean on = snaps.selectMaterializeInferences(kbId);
        if (on == null) {
            throw AppException.db("kb missing", null);
        }
        if (!on) {
            throw AppException.invalid("inference_off",
                    "materialized inference is off for this knowledge base");
        }
        var report = reasoning.materialize(kbId);
        audit.record(client(request), kbId, user.id(), "inference.materialized",
                "knowledge_base", kbId, detail()
                        .put("rules", report.rules())
                        .put("edges", report.edges())
                        .put("derived", report.derived())
                        .put("inserted", report.inserted())
                        .put("invalidated", report.invalidated())
                        .put("capped", report.capped())
                        .toString());
        events.emitGraph(kbId);
        return new InferenceResponse(report.rules(), report.edges(), report.derived(),
                report.inserted(), report.invalidated(), report.capped());
    }

    // ---- 16. POST /kbs/{id}/review/alignment/phrases/{binding_id}（:1282-1386） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = DecideAlignmentPhraseBody.Deser.class)
    record DecideAlignmentPhraseBody(String property, String direction, String marks) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<DecideAlignmentPhraseBody> {
            @Override
            public DecideAlignmentPhraseBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                          com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                return new DecideAlignmentPhraseBody(text(n, "property"), text(n, "direction"),
                        text(n, "marks"));
            }
        }
    }

    record AcceptedResponse(boolean ok, long jobId, String status) {
    }

    /** 202：收下了，投影在路上。不编一个 typed: {added: 0} 出来——那不是这次请求知道的事。 */
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/kbs/{kbId}/review/alignment/phrases/{bindingId}")
    public AcceptedResponse decideAlignmentPhrase(@PathVariable UUID kbId,
                                                  @PathVariable UUID bindingId,
                                                  @RequestBody DecideAlignmentPhraseBody req,
                                                  HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        var sig = phraseBindings.signatureOf(kbId, bindingId);
        if (sig == null) {
            throw AppException.NOT_FOUND;
        }
        // 属性的键：空 = 没有属性对得上，陈述留在开放图谱
        String key = trimmed(req.property());
        UUID propertyId = null;
        boolean isState = false;
        if (key != null) {
            RelationTypeView found = null;
            for (RelationTypeView p : ontology.relationTypes(kbId)) {
                if (key.equals(p.key())) {
                    found = p;
                    break;
                }
            }
            if (found == null) {
                throw AppException.invalid("unknown_property", "no property with that key");
            }
            propertyId = found.id();
            isState = "state".equals(found.temporal());
        }
        // marks：状态属性下，带着某一刻的陈述那一刻标的是开始、结束，还是都不是（#966）。
        // 不给 = 未知：签名留在队列里等人补
        String marks = null;
        if (trimmed(req.marks()) != null) {
            if (propertyId == null || !isState) {
                throw AppException.invalid("marks_needs_state",
                        "only a state property says which end a moment marks");
            }
            marks = trimmed(req.marks());
            if (!"start".equals(marks) && !"end".equals(marks) && !"none".equals(marks)) {
                throw AppException.invalid("unknown_marks", "marks is one of start, end or none");
            }
        }
        // 给了属性就得给方向
        String direction = null;
        if (propertyId != null) {
            direction = req.direction();
            if (!"forward".equals(direction) && !"reverse".equals(direction)) {
                throw AppException.invalid("direction_required",
                        "a bound signature needs a direction: forward or reverse");
            }
        }
        ObjectNode votes = mapper.createObjectNode();
        ObjectNode person = votes.putObject("person");
        person.put("property", req.property());
        person.put("direction", direction);
        person.put("marks", marks);
        // 判定和它的重算任务一次提交（0051）：不再同步重算——等物化锁占的是池里的连接，
        // 一个 job 只在判定提交后可见，worker 读的是当前绑定
        Long jobId = phraseBindings.decideWithDelivery(kbId, sig,
                new PhraseBindingsService.Decision(propertyId, direction,
                        propertyId != null ? "bound" : "none", votes, "person", null, marks,
                        false));
        if (jobId == null) {
            throw AppException.conflict("the decision was not written");
        }
        audit.record(client(request), kbId, user.id(), "alignment.phrase_decided",
                "phrase_binding", bindingId, detail()
                        .put("phrase", sig.phrase())
                        .put("property", req.property())
                        .put("direction", direction)
                        .put("marks", marks)
                        .put("job_id", jobId)
                        .toString());
        events.emitReview(kbId);
        return new AcceptedResponse(true, jobId, "accepted");
    }

    // ---- 17. POST /kbs/{id}/review/alignment/kind-words/{kind_word}（:1398-1448） ----

    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = DecideAlignmentKindWordBody.Deser.class)
    record DecideAlignmentKindWordBody(String clazz) {
        static final class Deser
                extends com.fasterxml.jackson.databind.JsonDeserializer<DecideAlignmentKindWordBody> {
            @Override
            public DecideAlignmentKindWordBody deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                            com.fasterxml.jackson.databind.DeserializationContext c)
                    throws java.io.IOException {
                JsonNode n = tree(p);
                return new DecideAlignmentKindWordBody(text(n, "class"));
            }
        }
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/alignment/kind-words/{kindWord}")
    public OkResponse decideAlignmentKindWord(@PathVariable UUID kbId,
                                              @PathVariable String kindWord,
                                              @RequestBody DecideAlignmentKindWordBody req,
                                              HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 类的键：空 = 没有类对得上
        String key = trimmed(req.clazz());
        UUID classId = null;
        if (key != null) {
            var types = ontology.entityTypes(kbId);
            boolean found = false;
            for (var c : types) {
                if (key.equals(c.key())) {
                    classId = c.id();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw AppException.invalid("unknown_class", "no class with that key");
            }
        }
        ObjectNode votes = mapper.createObjectNode();
        votes.put("person", req.clazz());
        // 判定与实体投影、它引出的短语对齐任务同一事务（decide_and_apply_human）；
        // 行锁撞 lock_timeout → 409 alignment_busy（TypeBindingsService 翻译）
        boolean written = typeBindings.decideAndApplyHuman(kbId, kindWord, classId, votes);
        if (!written) {
            throw AppException.NOT_FOUND;
        }
        audit.record(client(request), kbId, user.id(), "alignment.kind_word_decided",
                "type_binding", null, detail()
                        .put("kind_word", kindWord)
                        .put("class", req.clazz())
                        .toString());
        events.emitReview(kbId);
        events.emitGraph(kbId);
        return new OkResponse(true);
    }

    // ---- 18. POST /kbs/{id}/review/alignment/rules/{rule_id}（:1244-1280） ----

    record DecideAlignmentRuleBody(boolean approve) {
    }

    /** 批准且要读数的先排 read_phrases（填缓存后自己排物化），否则直接排物化——驳回也要重算。 */
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PostMapping("/api/v1/kbs/{kbId}/review/alignment/rules/{ruleId}")
    public AcceptedResponse decideAlignmentRule(@PathVariable UUID kbId,
                                                @PathVariable UUID ruleId,
                                                @RequestBody DecideAlignmentRuleBody req,
                                                HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        var rule = implicationRules.get(kbId, ruleId);
        if (rule == null) {
            throw AppException.NOT_FOUND;
        }
        ObjectNode votes = mapper.createObjectNode();
        votes.put("person", req.approve() ? "approve" : "reject");
        Long jobId = implicationRules.decideWithDelivery(kbId, ruleId, req.approve(), votes);
        if (jobId == null) {
            throw AppException.NOT_FOUND;
        }
        audit.record(client(request), kbId, user.id(), "alignment.rule_decided",
                "implication_rule", ruleId, detail()
                        .put("trigger", rule.trigger())
                        .put("phrase", rule.phrase())
                        .put("reading", rule.reading())
                        .put("approve", req.approve())
                        .put("job_id", jobId)
                        .toString());
        events.emitReview(kbId);
        return new AcceptedResponse(true, jobId, "accepted");
    }

    // ---- 19. POST /kbs/{id}/review/errata/{action_id}（:1456-1482） ----

    record DecideErrataBody(boolean approve) {
    }

    @PostMapping("/api/v1/kbs/{kbId}/review/errata/{actionId}")
    public OkResponse decideErrata(@PathVariable UUID kbId, @PathVariable UUID actionId,
                                   @RequestBody DecideErrataBody req, HttpServletRequest request) {
        UserRow user = user(request);
        rbac.requireKb(user, kbId, Role.EDITOR);
        // 人答勘误 agent 留下的一笔（0044 决定 7）：批了就执行那个动作，否了只记一笔
        boolean found = errata.decideHeld(kbId, actionId, req.approve(), user.id());
        if (!found) {
            throw AppException.NOT_FOUND;
        }
        audit.record(client(request), kbId, user.id(), "errata.decided", "errata_action",
                actionId, detail().put("approve", req.approve()).toString());
        events.emitReview(kbId);
        events.emitGraph(kbId);
        return new OkResponse(true);
    }

    // ====================================================================
    // 工具

    /** review_routes.rs:169-181 world_precision：不给按日；写多少位就是多少精度。 */
    private static String worldPrecision(String p) {
        if (p == null) {
            return "day";
        }
        if (!WORLD_PRECISIONS.contains(p)) {
            throw AppException.invalid("bad_precision",
                    "A closing date's precision is year, month, day, hour, minute or second.");
        }
        return p;
    }

    /** review_routes.rs:27-50 fact_snapshot：一律在动作执行前取；拿不到回 null（跳过台账）。 */
    private ObjectNode factSnapshot(UUID kbId, UUID factId) {
        ReviewWriteMapper.FactSnapshotRow row = snaps.selectFactSnapshot(factId, kbId);
        if (row == null) {
            return null;
        }
        return detail()
                .put("subject", row.subject())
                .put("predicate", row.predicate())
                .put("object", row.object())
                .put("confidence", row.confidence());
    }

    private ObjectNode detail() {
        return mapper.createObjectNode();
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }

    private static ClientContext client(HttpServletRequest request) {
        return (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);
    }
}
