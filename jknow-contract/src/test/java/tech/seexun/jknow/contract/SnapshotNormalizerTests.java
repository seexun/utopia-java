package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 快照归一化器 TDD（M3 任务 1）：锁 4 尺子的四条命门——确定性、跨运行 UUID 对齐、
 * 时间戳保序、差异可抓。全部用构造行集直测（与 JDBC 解耦），覆盖真实 dump 的全部值形状。
 */
class SnapshotNormalizerTests {

    /** 行构造（LinkedHashMap）：列值可为 null（Map.of 不收 null，而真实行到处是可空列）。 */
    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    private static final UUID KB_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID KB_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    /** 造一个最小账本（kb 可参数化）：两个实体（Cara/Gamma）、一个类型、一个关系、两条事实。 */
    private static Map<String, List<Map<String, Object>>> ledger(UUID kb, UUID type, UUID rel, UUID eCara,
                                                                 UUID eGamma, UUID fWorks, UUID fKnown,
                                                                 OffsetDateTime t1, OffsetDateTime t2) {
        return Map.of(
                "entities", List.of(
                        row("id", eGamma, "kb_id", kb, "canonical_name", "Gamma", "type_id", type,
                                "created_at", t1),
                        row("id", eCara, "kb_id", kb, "canonical_name", "Cara", "type_id", type,
                                "created_at", t1)),
                "entity_types", List.of(
                        row("id", type, "kb_id", kb, "key", "person", "label", "Person",
                                "builtin", Boolean.TRUE, "created_at", t1)),
                "relation_types", List.of(
                        row("id", rel, "kb_id", kb, "key", "works_at", "label", "works at",
                                "kind", "relation", "created_at", t1)),
                "facts", List.of(
                        row("id", fWorks, "kb_id", kb, "subject_id", eCara, "predicate_id", rel,
                                "object_id", eGamma, "confidence", 0.9, "recorded_at", t2),
                        row("id", fKnown, "kb_id", kb, "subject_id", eCara, "predicate_id", null,
                                "object_value", new com.fasterxml.jackson.databind.node.TextNode("Cara Diaz"),
                                "confidence", 1.0, "recorded_at", t2)));
    }

    /** 一组跨运行等价的固定装置：UUID/时间戳不同、业务内容相同。 */
    private record Fixture(Map<String, List<Map<String, Object>>> tables) {
    }

    private static Fixture fixtureA() {
        return new Fixture(ledger(
                KB_A,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                OffsetDateTime.parse("2026-01-01T10:00:00Z"),
                OffsetDateTime.parse("2026-01-01T11:00:00Z")));
    }

    private static Fixture fixtureB() {
        return new Fixture(ledger(
                KB_B,
                UUID.fromString("aaaaaaa1-1111-1111-1111-111111111111"),
                UUID.fromString("aaaaaaa2-2222-2222-2222-222222222222"),
                UUID.fromString("aaaaaaa3-3333-3333-3333-333333333333"),
                UUID.fromString("aaaaaaa4-4444-4444-4444-444444444444"),
                UUID.fromString("aaaaaaa5-5555-5555-5555-555555555555"),
                UUID.fromString("aaaaaaa6-6666-6666-6666-666666666666"),
                OffsetDateTime.parse("2026-02-02T08:00:00Z"),
                OffsetDateTime.parse("2026-02-02T09:30:00Z")));
    }

    /** 同库两次 dump 逐字节稳定（含输入行序打乱——物理行序不是快照语义）。 */
    @Test void 同库两次归一化逐字节一致() {
        JsonNode first = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        JsonNode again = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        assertEquals(first.toString(), again.toString());
    }

    /**
     * 锁 4 的核心断言：两侧各自跑一遍写路径，UUID 全不同、绝对时刻全不同，
     * 只要业务内容相同，归一化后逐字节一致（HTTP 比对做不到这一点——那把尺子
     * 会把所有 UUID 抹成同一个占位符，行间引用关系全丢）。
     */
    @Test void 两侧不同UUID同业务内容归一化等价() {
        JsonNode a = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        JsonNode b = SnapshotNormalizer.normalize(KB_B, fixtureB().tables());
        assertEquals(a.toString(), b.toString());
    }

    /** UUID 占位保持引用一致性：事实的 subject_id 与实体的 id 是同一占位符；kb 恒 <kb>；库外引用恒 <ext>。 */
    @Test void UUID占位保持引用一致性() {
        JsonNode out = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        // 行按稳定键排序：entities 首行是 Cara（canonical_name 字典序）
        JsonNode cara = out.path("tables").path("entities").get(0);
        JsonNode worksFact = out.path("tables").path("facts").get(1); // facts 键序：subject_id 同 → object_value null 在前
        assertEquals(cara.path("id").asText(), worksFact.path("subject_id").asText(),
                "同一实体 UUID 全快照内必须同一占位符");
        assertNotEquals(cara.path("id").asText(), out.path("tables").path("entities").get(1).path("id").asText());
        assertEquals("<kb>", worksFact.path("kb_id").asText());
        // 库外引用（未 dump 表的 uuid）恒 <ext>：两侧账号/规则 id 本就无从对齐
        Map<String, List<Map<String, Object>>> withExternal = Map.of("entities", List.of(
                row("id", UUID.fromString("99999999-9999-9999-9999-999999999999"), "kb_id", KB_A,
                        "canonical_name", "X", "type_id", null, "merged_into",
                        UUID.fromString("88888888-8888-8888-8888-888888888888"))));
        JsonNode ext = SnapshotNormalizer.normalize(KB_A, withExternal);
        assertEquals("<ext>", ext.path("tables").path("entities").get(0).path("merged_into").asText());
    }

    /** 时间戳占位保序：先发生的秩小；次序倒了就是语义差异（秩错位→比对报差异）。 */
    @Test void 时间戳占位保序() {
        JsonNode out = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        String caraCreated = out.path("tables").path("entities").get(0).path("created_at").asText();
        String worksRecorded = out.path("tables").path("facts").get(1).path("recorded_at").asText();
        assertTrue(caraCreated.compareTo(worksRecorded) < 0,
                "10:00 创建实体早于 11:00 记录事实，占位符秩必须保序: " + caraCreated + " vs " + worksRecorded);
        assertEquals("<t:1>", caraCreated);
        assertEquals("<t:2>", worksRecorded);
    }

    /** 浮点容差：REAL 列两侧序列化尾差（0.9 vs 0.90000001）不构成差异。 */
    @Test void 浮点容差抹平尾差() {
        Map<String, List<Map<String, Object>>> a = Map.of("facts", List.of(
                row("id", UUID.fromString("11111111-1111-1111-1111-111111111111"), "kb_id", KB_A,
                        "subject_id", UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "confidence", 0.9)));
        Map<String, List<Map<String, Object>>> b = Map.of("facts", List.of(
                row("id", UUID.fromString("aaaaaaaa-1111-1111-1111-111111111111"), "kb_id", KB_A,
                        "subject_id", UUID.fromString("bbbbbbbb-3333-3333-3333-333333333333"),
                        "confidence", 0.90000001)));
        assertEquals(SnapshotNormalizer.normalize(KB_A, a).toString(),
                SnapshotNormalizer.normalize(KB_A, b).toString());
    }

    /** 行按稳定复合键排序：输入乱序不影响输出序（业务键优先，不进原始 UUID）。 */
    @Test void 行按稳定复合键排序() {
        JsonNode out = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        JsonNode entities = out.path("tables").path("entities");
        assertEquals("Cara", entities.get(0).path("canonical_name").asText());
        assertEquals("Gamma", entities.get(1).path("canonical_name").asText());
        JsonNode facts = out.path("tables").path("facts");
        // facts 键序 subject_id→predicate_id→object_id→object_value：两条事实 subject 同，
        // predicate works_at(有) 排在 null 之后？不——null 键序恒最小（见 keyForm），
        // predicate_id=null 的 known_as 事实在前
        assertEquals(true, facts.get(0).path("predicate_id").isNull());
        assertEquals(false, facts.get(1).path("predicate_id").isNull());
    }

    /** 已知差异注入——改一行：归一化后必然不同，且 Diff 首差定位到该行。 */
    @Test void 注入改行差异可抓() {
        Map<String, List<Map<String, Object>>> mutated = new java.util.HashMap<>(fixtureA().tables());
        mutated.put("entities", List.of(
                row("id", UUID.fromString("44444444-4444-4444-4444-444444444444"), "kb_id", KB_A,
                        "canonical_name", "Gamma Inc", "type_id",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "created_at", OffsetDateTime.parse("2026-01-01T10:00:00Z")),
                row("id", UUID.fromString("33333333-3333-3333-3333-333333333333"), "kb_id", KB_A,
                        "canonical_name", "Cara", "type_id",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "created_at", OffsetDateTime.parse("2026-01-01T10:00:00Z"))));
        JsonNode a = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        JsonNode b = SnapshotNormalizer.normalize(KB_A, mutated);
        String diff = Diff.firstDifference(a, b, "$");
        assertNotEquals(null, diff, "改一行必须被抓到");
        assertTrue(diff.contains("canonical_name"), "首差定位到被改的列: " + diff);
    }

    /** 已知差异注入——加一行：数组长度不同即差异。 */
    @Test void 注入加行差异可抓() {
        Map<String, List<Map<String, Object>>> mutated = new java.util.HashMap<>(fixtureA().tables());
        mutated.put("entity_types", List.of(
                row("id", UUID.fromString("11111111-1111-1111-1111-111111111111"), "kb_id", KB_A,
                        "key", "person", "label", "Person", "builtin", Boolean.TRUE,
                        "created_at", OffsetDateTime.parse("2026-01-01T10:00:00Z")),
                row("id", UUID.fromString("77777777-7777-7777-7777-777777777777"), "kb_id", KB_A,
                        "key", "organization", "label", "Organization", "builtin", Boolean.FALSE,
                        "created_at", OffsetDateTime.parse("2026-01-01T10:00:00Z"))));
        JsonNode a = SnapshotNormalizer.normalize(KB_A, fixtureA().tables());
        JsonNode b = SnapshotNormalizer.normalize(KB_A, mutated);
        assertNotEquals(null, Diff.firstDifference(a, b, "$"));
    }

    /** 已知差异注入——时间次序反转（写路径真差异）：占位秩错位被抓住。 */
    @Test void 注入时间次序反转可抓() {
        Fixture a = fixtureA(); // 实体 10:00 建、事实 11:00 记
        Map<String, List<Map<String, Object>>> swapped = ledger(
                KB_A,
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                UUID.fromString("66666666-6666-6666-6666-666666666666"),
                OffsetDateTime.parse("2026-01-01T12:00:00Z"), // 实体晚于事实建立——语义差异
                OffsetDateTime.parse("2026-01-01T11:00:00Z"));
        assertNotEquals(SnapshotNormalizer.normalize(KB_A, a.tables()).toString(),
                SnapshotNormalizer.normalize(KB_A, swapped).toString());
    }

    /** 两个业务内容相同但同名并列的实体（消歧后缀不同）：占位标签仍稳定对齐（内容定标签）。 */
    @Test void 同名实体按消歧后缀对齐() {
        UUID t = UUID.fromString("11111111-1111-1111-1111-111111111111");
        Map<String, List<Map<String, Object>>> a = Map.of("entities", List.of(
                row("id", UUID.fromString("33333333-3333-3333-3333-333333333333"), "kb_id", KB_A,
                        "canonical_name", "Gamma", "disambiguator", "Platform", "created_at",
                        OffsetDateTime.parse("2026-01-01T10:00:00Z")),
                row("id", UUID.fromString("44444444-4444-4444-4444-444444444444"), "kb_id", KB_A,
                        "canonical_name", "Gamma", "disambiguator", "Retail", "created_at",
                        OffsetDateTime.parse("2026-01-01T10:00:00Z"))));
        Map<String, List<Map<String, Object>>> b = Map.of("entities", List.of(
                row("id", UUID.fromString("bbbbbbbb-3333-3333-3333-333333333333"), "kb_id", KB_A,
                        "canonical_name", "Gamma", "disambiguator", "Platform", "created_at",
                        OffsetDateTime.parse("2026-03-03T10:00:00Z")),
                row("id", UUID.fromString("bbbbbbbb-4444-4444-4444-444444444444"), "kb_id", KB_A,
                        "canonical_name", "Gamma", "disambiguator", "Retail", "created_at",
                        OffsetDateTime.parse("2026-03-03T10:00:00Z"))));
        assertEquals(SnapshotNormalizer.normalize(KB_A, a).toString(),
                SnapshotNormalizer.normalize(KB_A, b).toString());
    }
}
