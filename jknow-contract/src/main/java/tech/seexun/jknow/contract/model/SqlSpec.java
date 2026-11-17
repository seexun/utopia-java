package tech.seexun.jknow.contract.model;

import java.util.List;
import java.util.Map;

/**
 * 剧本 SQL 步骤（{@code sql:}，M3 任务 11）的载荷：直连共享 PostgreSQL 执行一串语句。
 *
 * <p><b>为什么需要它</b>：M3 心脏剧本的固定装置与裁决前提超出两侧 HTTP 面的共同部分——
 * workspace/KB 创建（M2 裁定 Java 写域不含）、phrase/type 绑定与消解审核行（对齐队列
 * 写端点属 M4）、勘误动作记录（errata_review agent 属 M4）。这些是<b>装置</b>，不是被测
 * 写路径：两轮（--writes rust / --writes java）执行同一串 SQL，产生同构前提，之后的写步
 * 与快照比对裁决两侧心脏代码的对错（m3-plan 任务 11「直接 SQL 造裁决前提」）。
 *
 * <pre>{@code
 * - id: fixture
 *   sql:
 *     wait_quiet: '${kb_id}'        # 可选：执行前静默等待该 kb 的后台 job（${var} 模板）
 *     statements:
 *       - 'INSERT ... RETURNING id AS ws_id'
 *       - 'INSERT ... ''${ws_id}'' ... RETURNING id AS kb_id'
 *     capture: { kb_id: kb_id }     # 可选：var → 结果列名，逐语句扫描 RETURNING/SELECT 首行
 * }</pre>
 *
 * <ul>
 * <li>{@code statements}——必填非空列表；每条独立执行（JDBC 单语句），整体包<b>一个事务</b>
 * （装置原子性：半截装置比没有装置更糟）；语句按序执行，前面的 capture 变量在后续语句的
 * {@code ${var}} 替换中可见（装置内部接力）；SQL 内的单引号按 YAML 规则书写。</li>
 * <li>{@code capture}——可选 var → 列名映射：每条有结果集的语句（RETURNING/SELECT）首行
 * 中出现该列即捕获（后语句覆盖先语句）；模板替换与 HTTP 步同源。</li>
 * <li>{@code wait_quiet}——可选 kb_id 模板：执行前先做 {@link tech.seexun.jknow.contract.Snapshot#awaitQuiet}
 * ——SQL 装置若引用前面写步（推送/本体编辑）的库内产物，必须等那些后台 job 落定，
 * 否则语句看到的是半加工态（60s 超时同快照步口径）。</li>
 * </ul>
 *
 * <p>SQL 步不产生黄金样本（确定性库操作无「两侧行为」可比，Diff 无对象），只做捕获与
 * 装置；执行失败（SQL 错误/等待超时/捕获列缺失）按 {@code ScenarioFailure} 中止剧本。
 *
 * @param statements SQL 语句列表（${var} 模板，逐条执行）
 * @param capture    var → 结果列名（可选；扫每条语句结果集的首行）
 * @param waitQuiet  执行前静默等待的 kb_id 模板（可选）
 */
public record SqlSpec(List<String> statements, Map<String, String> capture, String waitQuiet) {
}
