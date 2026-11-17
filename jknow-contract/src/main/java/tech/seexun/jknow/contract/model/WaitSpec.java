package tech.seexun.jknow.contract.model;

/**
 * 剧本等待步骤（{@code wait:}，M3 任务 11）的载荷：静默等待该 kb 的后台 job 落定
 * （{@link tech.seexun.jknow.contract.Snapshot#awaitQuiet}——due-or-running 口径，
 * 60s 超时 / 500ms 轮询）。
 *
 * <p><b>为什么单独一步</b>：心脏写路径的关键节点在后台 job 里（摄取→抽取→时间解析→
 * 物化→公理对账）。SQL 步（wait_quiet）只覆盖「SQL 之前」；「job 之后、下一个 HTTP 写步
 * 之前」的确定性（如：物化跑完才改公理触发对账）由这一步承担。固定 sleep 不可靠
 * （慢机器等不够、快机器白等），以库内 job 静默为准——与快照步同一个门。
 *
 * <pre>{@code
 * - id: wait-materialize
 *   wait: { kb_id: '${kb_id}' }
 * }</pre>
 *
 * <p>等待步不产生黄金样本（无两侧行为可比）。
 *
 * @param kbId kb_id 模板（${var} 替换后解析为 UUID）
 */
public record WaitSpec(String kbId) {
}
