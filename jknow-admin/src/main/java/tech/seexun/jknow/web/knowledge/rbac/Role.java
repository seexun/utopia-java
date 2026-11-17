package tech.seexun.jknow.web.knowledge.rbac;

/**
 * KB 角色序（对齐 Rust utopia-core models::Role，models.rs:55-81）：
 * 声明序即偏序 viewer &lt; editor &lt; admin &lt; owner（derive PartialOrd）；parse 对齐
 * Role::parse——只认四个小写字面量，未知值 null（kb_members 里出现脏角色按「不可见」处理，
 * access.rs:22-24 的 Option 语义）。
 */
public enum Role {

    VIEWER(0),
    EDITOR(1),
    ADMIN(2),
    OWNER(3);

    private final int rank;

    Role(int rank) {
        this.rank = rank;
    }

    /** 角色序（require_kb 的最低角色判定）。 */
    public int rank() {
        return rank;
    }

    /** models.rs:72-80 Role::parse。 */
    public static Role parse(String s) {
        if (s == null) {
            return null;
        }
        return switch (s) {
            case "owner" -> OWNER;
            case "admin" -> ADMIN;
            case "editor" -> EDITOR;
            case "viewer" -> VIEWER;
            default -> null;
        };
    }

    /** models.rs:58-66 as_str：库里存的小写文本（my_role 等回显用）。 */
    public String asStr() {
        return name().toLowerCase();
    }
}
