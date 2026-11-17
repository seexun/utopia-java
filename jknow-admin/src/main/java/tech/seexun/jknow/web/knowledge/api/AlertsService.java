package tech.seexun.jknow.web.knowledge.api;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.entity.AlertGroup;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.AlertsMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper.VisibleKbRole;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 告警中心读面编排（对齐旧仓库 alerts.rs:196-255 list_groups / 295-310 unread_count）。
 *
 * <p>可见性（alerts.rs:336-351 visible）：kb_role 的集合版拆成两个平行数组喂
 * {@code unnest($3::uuid[], $4::int[])}。三条分支与 kb_role 一一对应（access.rs:50-80）：
 * is_admin → 全部 KB Owner；矩阵有记录 → 矩阵角色（优先于 open）；open 库 + membership → Viewer。
 * 角色序三处一致（VISIBLE 的 CASE / rank / Role.rank——alerts.rs:364-407 同一不变量）。
 */
@Service
public class AlertsService {

    private final AlertsMapper alerts;
    private final KbFamilyMapper kbFamily;
    private final KbMapper kbs;

    public AlertsService(AlertsMapper alerts, KbFamilyMapper kbFamily, KbMapper kbs) {
        this.alerts = alerts;
        this.kbFamily = kbFamily;
        this.kbs = kbs;
    }

    /** 一页分组 + 总组数（alerts.rs:205 空串当没搜：前端清空搜索框不该变成搜空字符串）。 */
    public AlertsPage list(UserRow user, String q, long limit, long offset) {
        String query = trimToNull(q);
        Visibility v = visibility(user);
        List<AlertGroup> items = alerts.selectGroups(user.id(), user.isAdmin(),
                v.kbIds, v.kbRanks, query, limit, offset);
        long total = alerts.countGroups(user.id(), user.isAdmin(), v.kbIds, v.kbRanks, query);
        return new AlertsPage(items, total);
    }

    /** 未读角标（alerts_routes.rs:91-97 单独一条路由而不是从列表里数）。 */
    public long unread(UserRow user) {
        Visibility v = visibility(user);
        return alerts.selectUnreadCount(user.id(), user.isAdmin(), v.kbIds, v.kbRanks);
    }

    /**
     * alerts.rs:261-298 mark_group_read（M5 任务 6 落位）：把一整组标已读。**逐人**——
     * 读过不等于问题没了，别人的未读不受影响。按时间区间圈而不是发 id 列表。
     */
    public int markGroupRead(UserRow user, java.util.UUID kbId, String kind,
                              java.time.OffsetDateTime from, java.time.OffsetDateTime to) {
        Visibility v = visibility(user);
        return alerts.markGroupRead(user.id(), kbId, kind, from, to, user.isAdmin(),
                v.kbIds, v.kbRanks);
    }

    /** alerts.rs:316-332 mark_all_read（M5 任务 6 落位）：全部已读，同一可见性谓词。 */
    public int markAllRead(UserRow user) {
        Visibility v = visibility(user);
        return alerts.markAllRead(user.id(), user.isAdmin(), v.kbIds, v.kbRanks);
    }

    private Visibility visibility(UserRow user) {
        List<UUID> ids = new ArrayList<>();
        List<Integer> ranks = new ArrayList<>();
        if (user.isAdmin()) {
            // access.rs:51-56：is_admin → 全部 KB，Owner（rank 3）
            for (UUID kbId : kbs.selectAllIds()) {
                ids.add(kbId);
                ranks.add(Role.OWNER.rank());
            }
            return new Visibility(ids, ranks);
        }
        for (VisibleKbRole row : kbFamily.selectVisibleKbRoles(user.id())) {
            // 矩阵优先于 open——跟 kb_role 一样，矩阵有记录就不再看 visibility
            if (row.matrixRole() != null) {
                Role r = Role.parse(row.matrixRole());
                if (r != null) {
                    ids.add(row.id());
                    ranks.add(r.rank());
                }
            } else if (row.openMember()) {
                ids.add(row.id());
                ranks.add(Role.VIEWER.rank());
            }
        }
        return new Visibility(ids, ranks);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** alerts.rs:178-181 GroupPage（一页分组 + 总组数）。 */
    public record AlertsPage(List<AlertGroup> items, long total) {
    }

    /** 可见性平行数组（kb_id, rank）。 */
    private record Visibility(List<UUID> kbIds, List<Integer> kbRanks) {
    }
}
