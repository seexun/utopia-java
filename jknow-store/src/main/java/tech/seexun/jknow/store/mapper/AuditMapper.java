package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.AuditEventView;

/** 审计 mapper（对齐旧仓库 utopia-store/src/audit.rs，SQL 见 resources/mapper/）。 */
@Mapper
public interface AuditMapper {

    /** 身份快照（audit.rs:65-68）：用户日后被删台账仍认得出是谁；此刻查是可靠的。 */
    String selectActorLabel(@Param("actorId") UUID actorId);

    /** audit.rs:135-168 list_for_kb：一个库的台账（分页+四筛选，action 前缀匹配）。 */
    List<AuditEventView> selectKbAuditEvents(@Param("kbId") UUID kbId,
                                             @Param("action") String action,
                                             @Param("actor") UUID actor,
                                             @Param("since") OffsetDateTime since,
                                             @Param("until") OffsetDateTime until,
                                             @Param("limit") long limit,
                                             @Param("offset") long offset);

    /** audit.rs:170-177 list_for_kb 的总数（同一 WHERE，分页器用）。 */
    long countKbAuditEvents(@Param("kbId") UUID kbId,
                            @Param("action") String action,
                            @Param("actor") UUID actor,
                            @Param("since") OffsetDateTime since,
                            @Param("until") OffsetDateTime until);

    /** audit.rs:183-190 actions_for_kb：筛选下拉按这个库实际发生过的动作填。 */
    List<String> selectKbActions(@Param("kbId") UUID kbId);


    /**
     * 写一条审计事件（audit.rs:73-82 INSERT 逐字；detail 为 JSON 文本经 ::jsonb 转 jsonb，
     * 占位符与类型转换是 JDBC 方言差异，列集与值序不动）。
     * 审计失败绝不影响业务——调用方一律吞异常（Rust 调用点全部 {@code let _ =}）。
     */
    int insertAuditEvent(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("actorId") UUID actorId,
                         @Param("action") String action, @Param("targetKind") String targetKind,
                         @Param("targetId") UUID targetId, @Param("detail") String detail,
                         @Param("clientIp") String clientIp, @Param("userAgent") String userAgent,
                         @Param("actorLabel") String actorLabel);
}
