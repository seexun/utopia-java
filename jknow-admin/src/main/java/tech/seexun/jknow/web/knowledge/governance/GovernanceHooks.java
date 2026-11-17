package tech.seexun.jknow.web.knowledge.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.mapper.AlertsMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 治理钩子（M4 任务 2 壳——crate/utopia-server/src/governance.rs:912-1017 的编排层）：
 * 人裁/人撤回之后，先例与队列要跟上。三个入口全部<b>尽力而为</b>——失败只 warn 不抛，
 * 人的动作本身已经落账，钩子是锦上添花的那一层（Rust 逐处 {@code tracing::warn!}）。
 *
 * <p><b>壳的范围（任务 2）</b>：{@link #afterHumanDecision} 的 answer_open +
 * supersede_siblings、排 govern（入队侧——ExtractionJob 收尾同款，dispatcher 侧是
 * NOOP_STUB）与 {@link #afterRevert}→{@link #fuse} 链全部照 Rust 落；govern 的
 * <b>agent 本体</b>（簇/第二眼/五工具循环）属任务 4，届时只换 dispatcher 注册表条目，
 * 这里的钩子不用动。
 */
@Component
public class GovernanceHooks {

    private static final Logger log = LoggerFactory.getLogger(GovernanceHooks.class);

    /** alerts.rs:60 常量。 */
    public static final String GOVERNANCE_TRIPPED = "governance.tripped";

    private final GovernanceService governance;
    private final KbMapper kbs;
    private final AlertsMapper alerts;
    private final AuditService audit;
    private final AppEventBus events;
    private final JobsService jobs;
    private final ObjectMapper json = new ObjectMapper();

    public GovernanceHooks(GovernanceService governance, KbMapper kbs, AlertsMapper alerts,
                           AuditService audit, AppEventBus events, JobsService jobs) {
        this.governance = governance;
        this.kbs = kbs;
        this.alerts = alerts;
        this.audit = audit;
        this.events = events;
        this.jobs = jobs;
    }

    /**
     * governance.rs:986-1017 {@code after_human_decision}：人裁了一对之后——这一对上
     * 开着的建议就是被回答了（与建议相同是接受，不同是改判）；同名或同实体的对上的
     * 建议过时、那些对回到队列；开关开着就排一轮（#428 批量后 agent 顺着簇办剩下的）。
     *
     * <p>{@code action=null} 时不答本对的建议（Agent 队列那条路自己已经答过了）。
     * 排下的 govern 此刻由 JobDispatcher 的 NOOP_STUB 消费（记日志即 done）——agent
     * 真身（簇/第二眼/五工具）是 M4 任务 4，落地即无缝接管这条入队。
     */
    public void afterHumanDecision(UUID kbId, List<UUID> reviewIds, String action, UUID userId) {
        for (UUID id : reviewIds) {
            if (action != null) {
                try {
                    governance.answerOpen(kbId, id, action, userId);
                } catch (RuntimeException e) {
                    log.warn("治理：建议回答失败 kb={} review={} error={}", kbId, id, e.getMessage());
                }
            }
            try {
                governance.supersedeSiblings(kbId, id);
            } catch (RuntimeException e) {
                log.warn("治理：建议作废失败 kb={} review={} error={}", kbId, id, e.getMessage());
            }
        }
        var kb = kbs.selectById(kbId);
        if (kb != null && kb.governance()) {
            try {
                jobs.enqueueUnlessQueued("govern",
                        json.createObjectNode().put("kb_id", kbId.toString()));
            } catch (RuntimeException e) {
                log.warn("治理任务入队失败 kb={} error={}", kbId, e.getMessage());
            }
        }
    }

    /**
     * governance.rs:974-980 {@code after_revert}：人从合并历史撤回了一条合并——是
     * agent 自己裁的就把那一行记成 reverted，然后看保险丝。
     */
    public void afterRevert(UUID kbId, UUID mergeId, UUID userId) {
        try {
            if (governance.settleByMerge(kbId, mergeId, userId) != null) {
                fuse(kbId);
            }
        } catch (RuntimeException e) {
            log.warn("治理：记撤回失败 kb={} merge={} error={}", kbId, mergeId, e.getMessage());
        }
    }

    /**
     * governance.rs:915-971 {@code fuse} 保险丝（0025 决定 9）：这次打开以来、七天之内，
     * 人撤回 agent 的自动合并到了两次，开关自动关掉，发一条告警，台账记一笔。它替人
     * 做主做错了两回，该停下来等人再开；人再打开时 governance_since 更新，之前的撤回
     * 不再算。trip 只在开关真开着时关掉并返回 true——告警只发这一次。
     */
    public void fuse(UUID kbId) {
        var kb = kbs.selectById(kbId);
        if (kb == null || !kb.governance()) {
            return;
        }
        OffsetDateTime window = OffsetDateTime.now().minusDays(GovernanceService.FUSE_WINDOW_DAYS);
        OffsetDateTime since = kb.governanceSince() == null
                ? window
                : kb.governanceSince().isAfter(window) ? kb.governanceSince() : window;
        long reverts;
        try {
            reverts = governance.revertsSince(kbId, since);
        } catch (RuntimeException e) {
            log.warn("治理：数撤回失败 kb={} error={}", kbId, e.getMessage());
            return;
        }
        if (reverts < GovernanceService.FUSE_REVERTS) {
            return;
        }
        boolean tripped;
        try {
            tripped = governance.trip(kbId);
        } catch (RuntimeException e) {
            log.warn("治理：关开关失败 kb={} error={}", kbId, e.getMessage());
            return;
        }
        if (!tripped) {
            return;
        }
        log.warn("治理：保险丝跳了，开关已关 kb={} reverts={}", kbId, reverts);
        try {
            ObjectNode detail = json.createObjectNode();
            detail.put("kb", kb.name());
            detail.put("reverts", reverts);
            detail.put("window_days", GovernanceService.FUSE_WINDOW_DAYS);
            alerts.insertAlert(Uuids.nowV7(), kbId, "warning", GOVERNANCE_TRIPPED, "editor",
                    "kb", kbId, detail);
            // 不 emitAlert（审查 M-1）：Rust fuse 只 emit_review——alerts::raise 本身不发事件，
            // 事件由 alerting.rs 各 raise 站点单发；跳闸告警不在其列
        } catch (RuntimeException e) {
            log.warn("上报 governance.tripped 失败 kb={} error={}", kbId, e.getMessage());
        }
        audit.recordOpt(null, kbId, null, "kb.updated", "kb", kbId,
                json.createObjectNode().put("governance", false).put("via", "fuse")
                        .put("reverts", reverts).toString());
        events.emitReview(kbId);
    }
}
