package tech.seexun.jknow.contract.model;

/**
 * 剧本 setup 块（两种模式，P3 golden 复活批新增 ruoyi_login）：
 * <ul>
 * <li>{@code register}（缺省，源仓语义）——注册拿会话（409 转登录），cookie 会话；
 * 见 {@link RegisterSetup}；</li>
 * <li>{@code ruoyi_login}（P3 新增）——若依 {@code POST /login} 捕顶层 token（AjaxResult：
 * {@code {"msg","code","token"}}），会话标识 {@code "bearer:<token>"}，后续步由引擎默认注入
 * {@code Authorization: Bearer} 头（步级 headers 仍可覆盖）。setup 本身不进黄金样本。</li>
 * </ul>
 * yaml 形态：
 * <pre>{@code
 * setup:
 *   mode: ruoyi_login   # 可省，缺省 register
 *   username: admin
 *   password: admin123
 * }</pre>
 *
 * @param mode     模式（缺省 REGISTER，向后兼容——旧剧本无 mode 键即源仓注册流）
 * @param register register 模式的账号体（ruoyi_login 模式必须为 null）
 * @param username ruoyi_login 模式的登录名（register 模式必须为 null）
 * @param password ruoyi_login 模式的口令（register 模式必须为 null）
 */
public record Setup(Mode mode, RegisterSetup register, String username, String password) {

    public enum Mode { REGISTER, RUOYI_LOGIN }

    /** mode 缺省 REGISTER（旧剧本无 mode 键即源仓注册流）。 */
    public Mode modeOrDefault() {
        return mode == null ? Mode.REGISTER : mode;
    }

    /** ruoyi_login 模式判定（引擎 setup 分支与普通步假 200 识别共用）。 */
    public boolean ruoyiLogin() {
        return modeOrDefault() == Mode.RUOYI_LOGIN;
    }

    /** yaml mode 字符串解析（register|ruoyi_login，大小写不敏感；null/空白即缺省）。 */
    public static Mode parseMode(String raw, String src) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase()) {
            case "register" -> Mode.REGISTER;
            case "ruoyi_login", "ruoyi-login" -> Mode.RUOYI_LOGIN;
            default -> throw new IllegalArgumentException(
                    src + ": setup.mode 必须是 register|ruoyi_login，实际是: " + raw);
        };
    }
}
