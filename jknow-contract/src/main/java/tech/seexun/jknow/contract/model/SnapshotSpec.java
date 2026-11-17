package tech.seexun.jknow.contract.model;

import java.util.List;

/**
 * 剧本快照步骤（{@code snapshot:}）的载荷：按 kb_id 直连共享 PostgreSQL dump 账本终态。
 *
 * <p>M3 锁 4（规格 §5）的核心语义：HTTP 响应比对只能看到端点层，而写路径的真正产物是
 * <b>库内终态</b>——同一剧本在 Rust 与 Java 各自跑一遍完整写路径（各自新建 kb，UUID 不同），
 * 各自 dump 终态，深度比对两个归一化后的快照（共享库下「两侧各 dump 一次同一库」是退化的：
 * 必然相同，比不出写路径的对错）。
 *
 * <pre>{@code
 * - id: ledger-snapshot
 *   snapshot: { kb_id: '${kb_id}' }        # kb_id 是模板，运行时 ${var} 替换
 *   target: rust                            # 跟随写路径：--writes java 时随之记到 java 侧
 * }</pre>
 *
 * <ul>
 * <li>{@code kb_id}——必填模板，通常引用建库步捕获的 {@code ${kb_id}}；</li>
 * <li>{@code tables}——可选表名列表，覆盖默认心脏集（默认集见 Snapshot 表登记）。</li>
 * </ul>
 *
 * <p>快照步不发生 HTTP 请求：它先<b>静默等待</b>（轮询 jobs 表，该 kb 无 queued/running，
 * 60s 超时 / 500ms 间隔——后台 job 时序会制造假差异，见 Snapshot.awaitQuiet），再 dump。
 * 归一化（UUID/时间戳占位、行按稳定键排序、浮点容差）见 SnapshotNormalizer——两边运行
 * 各自归一化后，同一业务内容必须产出逐字节相同的 JSON。
 *
 * @param kbId  kb_id 模板（未替换的 {@code ${var}} 原文）
 * @param tables 表名覆盖列表；null/空 = 默认心脏集
 */
public record SnapshotSpec(String kbId, List<String> tables) {
}
