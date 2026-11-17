package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.AlertGroup;

/**
 * 告警中心读 mapper（逐字搬迁自旧仓库 utopia-store/src/alerts.rs；
 * VISIBLE/SEARCH/ISLANDS/GROUP_LINES 四常量的内联规则见 AlertsMapper.xml）。
 */
public interface AlertsMapper {

    /**
     * alerts.rs:40-49 raise 的 INSERT（任务 8 补写路径——out_of_credit 的告警面，SQL
     * 逐字）。kb/severity/kind/min_role/subject_type/subject_id/detail 全列。
     */
    int insertAlert(@Param("id") UUID id, @Param("kbId") UUID kbId,
                    @Param("severity") String severity, @Param("kind") String kind,
                    @Param("minRole") String minRole, @Param("subjectType") String subjectType,
                    @Param("subjectId") UUID subjectId, @Param("detail") JsonNode detail);

    /**
     * alerts.rs:196-243 list_groups 主体：相邻同类折叠（gaps-and-islands）后按组聚合，
     * LIMIT $6 OFFSET $7（按组分页）。$1=user_id、$2=is_admin、$3/$4=可见 kb 与秩、$5=q。
     */
    List<AlertGroup> selectGroups(@Param("userId") UUID userId,
                                  @Param("isAdmin") boolean isAdmin,
                                  @Param("kbIds") List<UUID> kbIds,
                                  @Param("kbRanks") List<Integer> kbRanks,
                                  @Param("q") String q,
                                  @Param("limit") long limit,
                                  @Param("offset") long offset);

    /** alerts.rs:243-253：总**组**数（同一 base CTE，数的是组不是行）。 */
    long countGroups(@Param("userId") UUID userId,
                     @Param("isAdmin") boolean isAdmin,
                     @Param("kbIds") List<UUID> kbIds,
                     @Param("kbRanks") List<Integer> kbRanks,
                     @Param("q") String q);

    /** alerts.rs:295-310 unread_count：角标（可见且未读）。 */
    long selectUnreadCount(@Param("userId") UUID userId,
                           @Param("isAdmin") boolean isAdmin,
                           @Param("kbIds") List<UUID> kbIds,
                           @Param("kbRanks") List<Integer> kbRanks);

    /** alerts.rs:261-298 mark_group_read：组区间内自己可见的未读全标；回标记行数。 */
    int markGroupRead(@Param("userId") UUID userId, @Param("kbId") java.util.UUID kbId,
                      @Param("kind") String kind, @Param("from") java.time.OffsetDateTime from,
                      @Param("to") java.time.OffsetDateTime to, @Param("isAdmin") boolean isAdmin,
                      @Param("kbIds") java.util.List<UUID> kbIds,
                      @Param("kbRanks") java.util.List<Integer> kbRanks);

    /** alerts.rs:316-332 mark_all_read。 */
    int markAllRead(@Param("userId") UUID userId, @Param("isAdmin") boolean isAdmin,
                    @Param("kbIds") java.util.List<UUID> kbIds,
                    @Param("kbRanks") java.util.List<Integer> kbRanks);
}
