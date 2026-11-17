package tech.seexun.jknow.contract.model;

import java.util.Locale;

/**
 * 步骤的定向（M1 双打，m1-recon §5 共享库裁定）：
 * <ul>
 * <li>{@code rust}——只打 Rust（写路径归 Rust：建工作区/库/上传等一切有副作用的步骤）；</li>
 * <li>{@code java}——只打 Java（Java 独有端点，M1 暂无使用者，语义先就位）；</li>
 * <li>{@code both}——双打：同一请求对 Rust 与 Java 各发一次，各录一份黄金
 * （读步骤；两服务共享同一 PostgreSQL，读同一数据，双打验证的就是端点层）。</li>
 * </ul>
 * 缺省 both。仅双打模式（Recorder {@code --rust/--java}）生效；
 * 单打模式（{@code --target}）忽略定向，全部步骤打同一服务（黄金重录语义，M0 兼容）。
 * setup/ensure 前置整体视为 target: rust——账号注册/登录是写路径，不进黄金样本。
 */
public enum Target {

    RUST, JAVA, BOTH;

    /** YAML 值解析：大小写不敏感；非法值给可定位报错（剧本写错早暴露）。 */
    public static Target parse(String raw, String where) {
        if (raw == null || raw.isBlank()) {
            return BOTH;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "rust" -> RUST;
            case "java" -> JAVA;
            case "both" -> BOTH;
            default -> throw new IllegalArgumentException(
                    where + ": target 必须是 rust|java|both，实际是: " + raw);
        };
    }

    /** YAML/黄金 meta 里的规范小写形。 */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
