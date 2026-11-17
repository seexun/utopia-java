package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.OffsetDateTime;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.Names;

/**
 * 纯单元（无 DB）：parse_instant 语义（graph_routes.rs:27-44 逐字）与 normalize_name
 * （resolution.rs:41-51 逐字）——日期/ RFC3339 / UTC 归一 / 422 文案 / 全角折叠 / 空白折叠。
 */
class GraphUnitsTests {

    // ---- parseInstant ----

    @org.junit.jupiter.api.Test
    void dateOnlyMeansStartOfDayUtc() {
        assertEquals(OffsetDateTime.parse("2026-11-01T00:00:00Z"),
                GraphController.parseInstant("at", "2026-11-01"));
    }

    @org.junit.jupiter.api.Test
    void rfc3339KeepsInstantAndNormalizesToUtc() {
        // chrono with_timezone(Utc)：+08:00 归一到 UTC 同一时刻
        assertEquals(OffsetDateTime.parse("2026-11-01T02:30:00Z"),
                GraphController.parseInstant("as_of", "2026-11-01T10:30:00+08:00"));
    }

    @org.junit.jupiter.api.Test
    void blankOrNullMeansAbsent() {
        assertNull(GraphController.parseInstant("at", null));
        assertNull(GraphController.parseInstant("at", ""));
        assertNull(GraphController.parseInstant("at", "   "));
    }

    @org.junit.jupiter.api.Test
    void garbageIs422ValidationWithCanonicalMessage() {
        for (String field : new String[]{"at", "as_of"}) {
            AppException e = assertThrows(AppException.class,
                    () -> GraphController.parseInstant(field, "bogus"));
            assertEquals(AppException.Kind.VALIDATION, e.kind());
            assertEquals("Invalid `" + field + "` (expected YYYY-MM-DD or RFC3339)", e.getMessage());
        }
    }

    // ---- normalize_name（graph entities 搜索的精确同名集原料） ----

    @org.junit.jupiter.api.Test
    void fullWidthFoldsToHalfWidth() {
        assertEquals("ABC123", Names.normalizeName("ＡＢＣ１２３"), "U+FF01..FF5E 偏移 -0xFEE0");
        assertEquals("a!b?", Names.normalizeName("ａ！ｂ？"));
    }

    @org.junit.jupiter.api.Test
    void whitespaceCollapsesIncludingIdeographicSpace() {
        assertEquals("张 三", Names.normalizeName("  张　三  "), "U+3000 折叠为半角空格");
        assertEquals("a b", Names.normalizeName("a\t\n b"));
        assertEquals("", Names.normalizeName(" 　 "));
    }

    @org.junit.jupiter.api.Test
    void plainNamesPassThrough() {
        assertEquals("Acme Corp", Names.normalizeName("Acme Corp"));
        assertEquals("星野科技", Names.normalizeName("星野科技"));
    }
}
