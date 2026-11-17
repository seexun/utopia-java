package tech.seexun.jknow.contract.model;

import java.util.Map;

/**
 * 剧本 store 步骤（{@code store:}，M3 任务 11）的载荷：以<b>写路径侧</b>自己的心脏代码
 * 执行一个 store 层操作——Rust 轮走 Rust 的 HTTP 等价端点，Java 轮经
 * {@link tech.seexun.jknow.contract.StoreBridge} 直调 utopia-store 的 Spring bean。
 *
 * <p><b>为什么需要它（M3/M4 边界的切流预演口径）</b>：消解合并的裁决写路径在 M3 的
 * Java 侧只存在于 store 层（{@code ResolutionService.decideReview}——m3-plan 前置 3：
 * store 层 decide_review/merge_entities/revert_merge 属 M3，14 队列写端点属 M4）。
 * 快照比对要裁决的是<b>两侧心脏代码</b>产出的一致性，不是两侧 HTTP 框架——所以这一步
 * 在 Rust 轮映射到 Rust 的对应 HTTP 端点（review_routes::decide，其 handler 主体就是
 * store 调用 + RBAC/审计/事件），在 Java 轮直调 store bean。台账（audit_log）与事件
 * 不进快照，比对面同构。
 *
 * <pre>{@code
 * - id: review-merge
 *   store:
 *     op: decide_review
 *     args: { kb_id: '${kb_id}', review_id: '${review_id}', action: merge,
 *             user_id: '${user_id}', rationale: 'scenario merge' }
 * }</pre>
 *
 * <ul>
 * <li>{@code op}——操作名（当前支持 {@code decide_review}：合并/保留一条消解审核，
 * Rust 侧映射 {@code POST /api/v1/kbs/{kb_id}/review/{review_id}}，body
 * {@code {"action": "...", "rationale": "..."}}）。</li>
 * <li>{@code args}——参数表（值均支持 ${var} 模板）；执行失败（HTTP 非 2xx / store 抛出）
 * 按 {@code ScenarioFailure} 中止剧本。</li>
 * </ul>
 *
 * <p>样本落盘为常量 {@code {"ok": true}}（method=STORE、path=op 名）——真实对错由其后的
 * 快照步裁决，这里只留执行轨迹。
 *
 * @param op   操作名
 * @param args 参数（${var} 模板）
 */
public record StoreSpec(String op, Map<String, String> args) {
}
