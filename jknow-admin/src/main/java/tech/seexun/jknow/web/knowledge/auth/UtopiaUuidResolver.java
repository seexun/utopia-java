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
