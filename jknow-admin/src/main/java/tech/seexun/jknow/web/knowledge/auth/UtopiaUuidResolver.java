package tech.seexun.jknow.web.knowledge.auth;

import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * 若依 userId（Long）→ 业务身份（{@link UserRow}，主档 sys_user 的 utopia_uuid 桥接列，V0101）。
 * 列折回口径与 AccountsMapper.selectUserById 一致：email→user_name、display_name→nick_name、
 * is_admin→user_id=1 或授超级管理员角色（sys_role.role_id=1）、在职→del_flag='0' AND status='0'。
 * sys_user 更新极少，{@link ConcurrentHashMap} 无失效（YAGNI，方案 B 裁定）。
 */
@Component
public class UtopiaUuidResolver {

    private static final String SQL = """
            SELECT su.utopia_uuid, su.user_name, su.nick_name, su.user_id, su.create_time,
                   (SELECT o.id FROM organizations o ORDER BY o.created_at LIMIT 1) AS org_id,
                   (su.user_id = 1 OR EXISTS (SELECT 1 FROM sys_user_role ur
                        WHERE ur.user_id = su.user_id AND ur.role_id = 1)) AS is_admin
              FROM sys_user su
             WHERE su.user_id = ? AND su.del_flag = '0' AND su.status = '0'
            """;

    private final JdbcTemplate jdbc;

    private final ConcurrentHashMap<Long, UserRow> cache = new ConcurrentHashMap<>();

    /** 反查缓存（utopia_uuid → 若依主档，P1 任务 8 pt- 兜底用；同前向口径无失效）。 */
    private final ConcurrentHashMap<UUID, Binding> byUuid = new ConcurrentHashMap<>();

    public UtopiaUuidResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按若依 userId 解析业务身份；未找到（不存在/已删/已停用）返回 null——
     * 调用方（ClientContextBridgeFilter）不挂 USER_ATTR，业务端点按未登录语义走。
     */
    public UserRow userOf(Long userId) {
        return cache.computeIfAbsent(userId, this::query);
    }

    /**
     * 按业务 uuid 反查在职若依主档（P1 任务 8 pt- 令牌面）：personal_tokens 行的
     * user_id（utopia uuid）→ sys_user；在职口径与前向一致（del_flag='0' AND
     * status='0'）。<b>结果进反查缓存、无失效</b>（与前向 userOf 同裁定）——停用/解绑
     * 的生效窗口到进程重启，不是下一次请求立即生效。未绑定/不在职返回 null，调用方按 401 走。
     */
    public Binding bindingOf(UUID utopiaUuid) {
        return byUuid.computeIfAbsent(utopiaUuid, this::queryBinding);
    }

    /** 若依主档四件套（LoginUser 构造所需的最小集）。 */
    public record Binding(Long userId, Long deptId, String userName, String nickName) {
    }

    private Binding queryBinding(UUID utopiaUuid) {
        List<Binding> rows = jdbc.query(
                "SELECT user_id, dept_id, user_name, nick_name FROM sys_user "
                        + "WHERE utopia_uuid = ? AND del_flag = '0' AND status = '0'",
                (rs, i) -> new Binding(rs.getLong("user_id"),
                        (Long) rs.getObject("dept_id"),
                        rs.getString("user_name"), rs.getString("nick_name")),
                utopiaUuid);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private UserRow query(Long userId) {
        List<UserRow> rows = jdbc.query(SQL, (rs, i) -> {
            var ts = rs.getTimestamp("create_time");
            return new UserRow(
                    rs.getObject("utopia_uuid", UUID.class),
                    rs.getObject("org_id", UUID.class),
                    rs.getString("user_name"),
                    null,
                    rs.getString("nick_name"),
                    rs.getBoolean("is_admin"),
                    ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC),
                    null);
        }, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
