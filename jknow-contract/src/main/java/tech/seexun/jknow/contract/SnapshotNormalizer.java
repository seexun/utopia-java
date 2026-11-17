package tech.seexun.jknow.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 终态快照归一化器（M3 任务 1，锁 4 的尺子）：把一次 dump 的原始行集归一化成
 * <b>确定性 JSON</b>——同库两次 dump 逐字节一致；两个不同 kb（两侧各自跑一遍写路径、
 * UUID 全不同）只要业务内容相同，归一化后也逐字节一致。HTTP 响应比对用的
 * {@link Normalizer} 只会把 UUID 抹成同一个 {@code <uuid>} 占位符（行间引用关系全丢），
 * 快照比对要的是<b>保持引用一致性的抹除</b>——同一 UUID 全快照内同一占位符、
 * 不同 UUID 不同占位符，否则「事实 A 指实体 X」与「事实 A 指实体 Y」会被抹成一样的。
 *
 * <p>四件事，每件都有明确的「为什么」：
 *
 * <p><b>1. UUID 规范标签（Weisfeiler-Lehman 式迭代精化）</b>。两侧 UUID 各自随机，
 * 占位符编号不能按「先见先编」（先见序依赖物理行序，两次 dump 可能不同序；更依赖
 * 归一化本身的输出序——循环依赖）。做法：每个 uuid 主键行的签名从<b>非 uuid 标量列</b>
 * 起步（名字/短语/键字/状态/时间秩——这些两侧天然同值），迭代 {@link #REFINE_ROUNDS}
 * 轮并入各引用列指向
 * 行的上一轮签名（事实→实体的名字、合并→双方实体、supersedes 链……逐层展开）。最终
 * 按（签名，原始 uuid）排序编号成 {@code <id:N>}：业务内容决定编号，两侧独立计算仍
 * 对齐。真正同构的行（签名全同、内容全同）编号靠原始 uuid 兜底——可交换的行内容
 * 相同，交换不制造差异。<b>kb 自身</b>的 uuid 恒为 {@code <kb>}；<b>库外引用</b>
 * （users、未 dump 的表——两侧账号/规则 id 本就不同且无从对齐）恒为 {@code <ext>}：
 * 外部身份的区分对跨运行比对没有意义，统一占位反而把「谁来做的」这类运行间噪声抹平。
 *
 * <p><b>2. 时间戳保序占位</b>。两侧的绝对时刻必不同，但<b>相对次序</b>是写路径语义
 * （先 attestation 后闭合、created 先于 invalidated）。做法：全快照所有时刻去重后按
 * Instant 全局排序定秩，值→{@code <t:秩>}。秩由数据（次序）决定而非遍历序——天然
 * 确定性；两侧只要事件次序相同，秩就相同。时刻相同（同事务 now()）→ 同秩，无次序
 * 歧义。若两侧事件次序真的不同（例如闭合先于断言），秩错位 → 报差异——这正是要
 * 抓的写路径语义差异，不是假差异。
 *
 * <p><b>3. 行按稳定复合键排序</b>（键集见 {@link SnapshotTables} 登记簿）：优先非 id
 * 业务键，业务键全同才落 id 占位符——原始 UUID 进首层排序会把两侧的随机序带进来
 * （假差异），占位符（内容决定）不会。末位兜底整行序列化字节序，保证全序确定。
 *
 * <p><b>4. 标量口径</b>：浮点 6 位小数四舍五入（复用 {@link Normalizer}——REAL 列
 * 两侧序列化尾差）；JSONB 列递归走 Normalizer 树规范化（内嵌 uuid/ts 文本按正则
 * 抹平）；pgvector 列恒 {@code <vector>}（浮点向量逐维比对只有噪声）。
 *
 * <p>输入是「表名 → 行列表（列名 → 原始 Java 值）」——与 JDBC 解耦，单测直接构造。
 */
final class SnapshotNormalizer {

    /** pgvector 列的哨兵值（JDBC 材料化时替换，归一化为 {@code <vector>}）。 */
    enum SqlVector {
        INSTANCE
    }

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    /**
     * WL 精化轮数：事实→实体→名字 1 轮即收敛，但 M3 任务 4 起快照里有了会互相长引用的
     * 长链——survivor 链（decide_review 沿 merged_into 走 ≤16 跳）与 supersedes 改写链
     * （revert_merge 的 with_rewrites 递归收全）都能把签名一跳一跳往深处带。3 轮只盖住
     * 短链，连环合并下两侧编号会错位出假差异（任务 1 审查 M-1）——按最长链 + 余量取 20：
     * 每轮全表一次（不是全表×全表），20 轮在千行级快照上仍是毫秒面。
     */
    private static final int REFINE_ROUNDS = 20;

    private SnapshotNormalizer() {
    }

    /**
     * 归一化入口：输出 {@code {"tables": {表名: [行...]}}}——表名排序，行按稳定键排序，
     * 行内键排序。确定性：同输入同输出（与 Map 遍历序无关）。
     */
    static JsonNode normalize(UUID kbId, Map<String, List<Map<String, Object>>> rawTables) {
        // ---- 1. 时间戳定秩（全局排序，数据决定）----
        Map<OffsetDateTime, Integer> tsRank = timestampRanks(rawTables);

        // ---- 2. uuid 主键索引：标签锚点（有 uuid 主键的登记表）----
        Map<UUID, String> tableOf = new HashMap<>();
        Map<UUID, Integer> rowOf = new HashMap<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : rawTables.entrySet()) {
            String pk = SnapshotTables.REGISTRY.get(e.getKey()).pkColumn();
            if (pk == null) {
                continue;
            }
            for (int i = 0; i < e.getValue().size(); i++) {
                Object v = e.getValue().get(i).get(pk);
                if (v instanceof UUID id && !id.equals(kbId)) {
                    tableOf.put(id, e.getKey());
                    rowOf.put(id, i);
                }
            }
        }

        // ---- 3. WL 签名精化（REFINE_ROUNDS 轮）：内容定签名，签名定标签 ----
        Map<String, String[]> sig = signatures(kbId, rawTables, tsRank, tableOf, rowOf);

        // ---- 4. 标签编号：按（最终签名, 原始 uuid）排序 → <id:1..> ----
        List<UUID> ids = new ArrayList<>(tableOf.keySet());
        ids.sort(Comparator.comparing((UUID u) -> sig.get(tableOf.get(u))[rowOf.get(u)])
                .thenComparing(UUID::toString));
        Map<UUID, String> labels = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            labels.put(ids.get(i), "<id:" + (i + 1) + ">");
        }

        // ---- 5. 行归一化 + 稳定键排序，表名排序输出 ----
        ObjectNode tables = F.objectNode();
        for (String table : rawTables.keySet().stream().sorted().toList()) {
            SnapshotTables.TableSpec spec = SnapshotTables.REGISTRY.get(table);
            List<Map<String, Object>> rows = rawTables.get(table);
            List<JsonNode> normalized = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                normalized.add(rowNode(kbId, row, labels, tsRank));
            }
            normalized.sort(rowComparator(spec.businessKeys()));
            ArrayNode arr = F.arrayNode();
            normalized.forEach(arr::add);
            tables.set(table, arr);
        }
        ObjectNode root = F.objectNode();
        root.set("tables", tables);
        return root;
    }

    /** 全快照时间戳去重、按 Instant 全局排序定秩（秩 1 起）。 */
    private static Map<OffsetDateTime, Integer> timestampRanks(Map<String, List<Map<String, Object>>> rawTables) {
        List<OffsetDateTime> all = new ArrayList<>();
        for (List<Map<String, Object>> rows : rawTables.values()) {
            for (Map<String, Object> row : rows) {
                for (Object v : row.values()) {
                    if (v instanceof OffsetDateTime ts) {
                        all.add(ts);
                    }
                }
            }
        }
        List<Instant> distinct = all.stream().map(OffsetDateTime::toInstant).distinct().sorted().toList();
        Map<OffsetDateTime, Integer> ranks = new HashMap<>();
        for (OffsetDateTime ts : all) {
            ranks.computeIfAbsent(ts, t -> distinct.indexOf(t.toInstant()) + 1);
        }
        return ranks;
    }

    /**
     * WL 签名：sig_0 = 表名 + 非 uuid 标量（时间戳以秩进签名）；每轮并入引用槽
     * （uuid 列 / uuid 数组元素）指向行的上一轮签名，按（槽名，引用签名）排序拼接。
     * kb 引用恒 {@code kb}、库外引用恒 {@code ext}（占位语义与输出一致）。
     */
    private static Map<String, String[]> signatures(UUID kbId, Map<String, List<Map<String, Object>>> rawTables,
                                                    Map<OffsetDateTime, Integer> tsRank,
                                                    Map<UUID, String> tableOf, Map<UUID, Integer> rowOf) {
        Map<String, String[]> sig = new HashMap<>();
        List<Map.Entry<String, List<Map<String, Object>>>> entries = List.copyOf(rawTables.entrySet());
        for (Map.Entry<String, List<Map<String, Object>>> e : entries) {
            List<Map<String, Object>> rows = e.getValue();
            String[] arr = new String[rows.size()];
            for (int i = 0; i < rows.size(); i++) {
                arr[i] = e.getKey() + "|" + scalarSignature(kbId, rows.get(i), tsRank);
            }
            sig.put(e.getKey(), arr);
        }
        for (int round = 1; round <= REFINE_ROUNDS; round++) {
            Map<String, String[]> next = new HashMap<>();
            for (Map.Entry<String, List<Map<String, Object>>> e : entries) {
                List<Map<String, Object>> rows = e.getValue();
                String[] arr = new String[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    List<String> refs = new ArrayList<>();
                    for (Map.Entry<String, Object> col : rows.get(i).entrySet()) {
                        collectRefs(kbId, col.getValue(), col.getKey(), refs, tableOf, rowOf, sig);
                    }
                    java.util.Collections.sort(refs);
                    arr[i] = sig.get(e.getKey())[i] + (refs.isEmpty() ? "" : "|" + String.join("|", refs));
                }
                next.put(e.getKey(), arr);
            }
            sig = next;
        }
        return sig;
    }

    /** 非 uuid 标量的确定性表示（uuid 列不进标量签名——引用关系在精化轮里展开）。 */
    private static String scalarSignature(UUID kbId, Map<String, Object> row, Map<OffsetDateTime, Integer> tsRank) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> col : new TreeMap<>(row).entrySet()) {
            String s = scalarRepr(kbId, col.getValue(), tsRank);
            if (s != null) {
                parts.add(col.getKey() + "=" + s);
            }
        }
        return String.join(";", parts);
    }

    /** 单值签名表示；uuid 引用返回 null（不进标量签名），kb 自身返回常量 "kb"。 */
    private static String scalarRepr(UUID kbId, Object v, Map<OffsetDateTime, Integer> tsRank) {
        if (v == null) {
            return "null";
        }
        if (v instanceof UUID id) {
            return id.equals(kbId) ? "kb" : null;
        }
        if (v instanceof OffsetDateTime ts) {
            return "t" + tsRank.get(ts);
        }
        if (v instanceof Float || v instanceof Double) {
            return "f:" + Normalizer.roundFloating(BigDecimal.valueOf(((Number) v).doubleValue())).toPlainString();
        }
        if (v instanceof BigDecimal bd) {
            return "n:" + bd.toPlainString();
        }
        if (v instanceof Number n) {
            return "i:" + n.longValue();
        }
        if (v instanceof Boolean b) {
            return "b:" + b;
        }
        if (v instanceof String s) {
            return "s:" + s;
        }
        if (v instanceof JsonNode j) {
            return "j:" + Normalizer.normalizeTree(j, java.util.Set.of());
        }
        if (v instanceof SqlVector) {
            return "vec";
        }
        if (v instanceof List<?> list) {
            // 数组的标量签名只含非 uuid 元素（uuid 元素作为引用槽在精化轮展开）
            List<String> parts = new ArrayList<>();
            for (Object el : list) {
                String s = scalarRepr(kbId, el, tsRank);
                if (s != null) {
                    parts.add(s);
                }
            }
            return "a:[" + String.join(",", parts) + "]";
        }
        return "o:" + v;
    }

    /**
     * 收集一行里的引用槽（uuid 列、uuid 数组元素）→ "槽名=引用方上一轮签名摘要"。
     *
     * <p><b>引用签名进摘要而不是原文</b>：签名的增长是线性递推
     * {@code L_r(行) = L_{r-1}(行) + Σ L_{r-1}(引用)}——链上是多项式，但互指稠密图
     * （facts↔entities：每个实体被几十条事实引用、每条事实又引用回实体）上是指数。
     * REFINE_ROUNDS 提到 20（M-1：survivor/supersedes 长链深度）之后原文拼接会撑爆
     * String（实测 Overflow: String length out of range）。改存 64 位摘要：每轮每槽
     * 恒定 16 个十六进制字符，长度随轮数线性；深度信息不丢——第 r 轮摘要递归编入了
     * 距离 r 的邻域内容。两侧 dump 走同一个归一化器，摘要确定性保证同构行同签名。
     */
    private static void collectRefs(UUID kbId, Object v, String slot, List<String> refs,
                                    Map<UUID, String> tableOf, Map<UUID, Integer> rowOf,
                                    Map<String, String[]> sig) {
        if (v instanceof UUID id && !id.equals(kbId)) {
            refs.add(slot + "=" + digest(refSignature(id, tableOf, rowOf, sig)));
        } else if (v instanceof List<?> list) {
            for (Object el : list) {
                if (el instanceof UUID id && !id.equals(kbId)) {
                    refs.add(slot + "[]=" + digest(refSignature(id, tableOf, rowOf, sig)));
                }
            }
        }
    }

    /** SHA-256 前 16 个十六进制字符（64 位）：确定性、长度恒定，数千行级碰撞可忽略。 */
    private static String digest(String s) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", h[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String refSignature(UUID id, Map<UUID, String> tableOf, Map<UUID, Integer> rowOf,
                                       Map<String, String[]> sig) {
        String t = tableOf.get(id);
        return t == null ? "ext" : sig.get(t)[rowOf.get(id)];
    }

    /** 行 → 键排序的 JSON 对象；值按口径转节点（uuid→标签、ts→秩、浮点容差、jsonb 递归）。 */
    private static ObjectNode rowNode(UUID kbId, Map<String, Object> row, Map<UUID, String> labels,
                                      Map<OffsetDateTime, Integer> tsRank) {
        ObjectNode out = F.objectNode();
        for (Map.Entry<String, Object> col : new TreeMap<>(row).entrySet()) {
            out.set(col.getKey(), valueNode(kbId, col.getValue(), labels, tsRank));
        }
        return out;
    }

    private static JsonNode valueNode(UUID kbId, Object v, Map<UUID, String> labels,
                                      Map<OffsetDateTime, Integer> tsRank) {
        if (v == null) {
            return F.nullNode();
        }
        if (v instanceof UUID id) {
            if (id.equals(kbId)) {
                return F.textNode("<kb>");
            }
            String label = labels.get(id);
            return F.textNode(label != null ? label : "<ext>");
        }
        if (v instanceof OffsetDateTime ts) {
            return F.textNode("<t:" + tsRank.get(ts) + ">");
        }
        if (v instanceof Float || v instanceof Double) {
            return F.numberNode(Normalizer.roundFloating(BigDecimal.valueOf(((Number) v).doubleValue())));
        }
        if (v instanceof BigDecimal bd) {
            return F.numberNode(bd);
        }
        if (v instanceof Number n) {
            return n instanceof Long ? F.numberNode(n.longValue()) : F.numberNode(n.intValue());
        }
        if (v instanceof Boolean b) {
            return F.booleanNode(b);
        }
        if (v instanceof String s) {
            if (Normalizer.looksLikeUuid(s)) {
                return F.textNode("<uuid>");
            }
            if (Normalizer.looksLikeTimestamp(s)) {
                return F.textNode("<ts>");
            }
            return F.textNode(s);
        }
        if (v instanceof JsonNode j) {
            return Normalizer.normalizeTree(j, java.util.Set.of());
        }
        if (v instanceof SqlVector) {
            return F.textNode("<vector>");
        }
        if (v instanceof List<?> list) {
            ArrayNode arr = F.arrayNode();
            for (Object el : list) {
                arr.add(valueNode(kbId, el, labels, tsRank));
            }
            return arr;
        }
        return F.textNode(String.valueOf(v));
    }

    /**
     * 行排序比较器：稳定复合键逐键比（归一化后的序列化形，字典序），全同落整行序列化
     * 字节序（全序确定）。缺失键列按最小值（schema 演进的防御）。
     */
    private static Comparator<JsonNode> rowComparator(List<String> businessKeys) {
        return (a, b) -> {
            for (String key : businessKeys) {
                int c = keyForm(a, key).compareTo(keyForm(b, key));
                if (c != 0) {
                    return c;
                }
            }
            return a.toString().compareTo(b.toString());
        };
    }

    /** 键列的排序形：缺失列 '\u0000'（恒最小），其余取归一化节点的紧凑序列化。 */
    private static String keyForm(JsonNode row, String key) {
        JsonNode v = row.get(key);
        return v == null || v.isNull() ? "\u0000" : v.toString();
    }
}
