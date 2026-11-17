package tech.seexun.jknow.web.knowledge.auth;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.AuditMapper;

/**
 * 审计写入（对齐旧仓库 utopia-store/src/audit.rs record/record_opt：身份快照 + INSERT，
 * 调用点一律 {@code let _ =}——记录失败绝不影响业务操作）。actor_label 是身份快照：
 * 此刻查是可靠的（操作正在发生，人还在）。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditMapper audit;

    public AuditService(AuditMapper audit) {
        this.audit = audit;
    }

    /**
     * 有 actor（auth.login/register/logout/password_changed）。kbId 对齐 audit.rs:9-16 的
     * 第二参：库级事件（document 与 source 域、库内 jobs.requeued）传真实 kb_id——M1 遗留的
     * 硬编码 null 让来源/文档域审计事件在 /kbs/&#123;id&#125;/audit 台账里不可见（任务 4
     * review 挂账）；账号/系统级事件（auth 域、系统级 jobs.requeued）照 Rust 传 null。
     */
    public void record(ClientContext ctx, UUID kbId, UUID actorId, String action, String targetKind,
                       UUID targetId, String detailJson) {
        recordOpt(ctx, kbId, actorId, action, targetKind, targetId, detailJson);
    }

    /** 无人类操作者（auth.login_failed / 来源同步复活：actor NULL，详情进 detail）。 */
    public void recordOpt(ClientContext ctx, UUID kbId, UUID actorId, String action, String targetKind,
                          UUID targetId, String detailJson) {
        try {
            String actorLabel = actorId == null ? null : audit.selectActorLabel(actorId);
            audit.insertAuditEvent(Uuids.nowV7(), kbId, actorId, action, targetKind, targetId,
                    detailJson, ctx == null ? null : ctx.ip(), ctx == null ? null : ctx.userAgent(),
                    actorLabel);
        } catch (Exception e) {
            log.warn("audit record failed (action={}, actor={}): {}", action, actorId, e.getMessage());
        }
    }
}
