package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import tech.seexun.jknow.contract.model.Target;

/**
 * 一步（一侧）的录制结果：meta 用模板路径（{@code ${}} 未替换），跨次录制稳定——
 * 变量替换后的真实路径含每次都不同的 uuid，进了 meta 自比对就永远过不了。
 * normalized 是规范化后的响应体树（掩码/占位已生效）。
 *
 * <p>M1 双打扩展两个字段：
 * {@code target}——该样本的<b>实际路由侧</b>（both 步记 both；单侧步记其落侧——
 * Recorder {@code --writes java} 把 rust 步重定向到 Java 后记 java），进黄金 meta，
 * Diff 据此跳过单侧样本（rust 步只存在于 golden-rust）；
 * {@code side}——这一份结果实际来自哪个服务（"rust"/"java"；单打模式恒为 "single"），
 * Recorder 双打按 side 分拣到 golden-rust/ 与 golden-java/。
 *
 * <p>M3 任务 1 扩展 {@code snapshot}：快照步的标记——黄金文件名带 {@code .snapshot.json}
 * 后缀、meta.kind=snapshot、载荷键为 {@code snapshot}（HTTP 步为 {@code normalized}）。
 * 快照步 method 恒为 {@code SNAPSHOT}、pathTemplate 为 kb_id 模板原文。
 */
public record RecordedStep(String step, String method, String pathTemplate,
                           Integer expectedStatus, int status, JsonNode normalized,
                           Target target, String side, boolean snapshot) {

    /** M0 兼容构造：无定向信息（视为 both）、单一目标侧、HTTP 步。 */
    public RecordedStep(String step, String method, String pathTemplate,
                        Integer expectedStatus, int status, JsonNode normalized) {
        this(step, method, pathTemplate, expectedStatus, status, normalized, Target.BOTH, "rust", false);
    }

    /** M1 兼容构造：定向 + 侧别、HTTP 步。 */
    public RecordedStep(String step, String method, String pathTemplate, Integer expectedStatus,
                        int status, JsonNode normalized, Target target, String side) {
        this(step, method, pathTemplate, expectedStatus, status, normalized, target, side, false);
    }
}
