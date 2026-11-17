package tech.seexun.jknow.web.knowledge.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;

/**
 * 移植自 api/mapping_routes.rs 内联 2 例（断言逐字不改写）：一条口径至少得有表/表达式/
 * SQL 之一；preview 跑的正是测量台打分的那句 SQL（sql 优先去尾分号，expr+table 拼装，
 * 只有表名不是可执行口径）。
 */
class MappingRoutesShapeTests {

    @Test
    void aDefinitionNeedsSomethingToRun() {
        assertThrows(AppException.class,
                () -> MappingsController.definitionShape(null, null, null));
        assertDoesNotThrow(() -> MappingsController.definitionShape("t", null, null));
        assertDoesNotThrow(() -> MappingsController.definitionShape(null, "sum(x)", null));
        assertDoesNotThrow(() -> MappingsController.definitionShape(null, null, "SELECT 1"));
    }

    @Test
    void previewRunsWhatTheBenchScores() {
        // sql 优先，且去掉结尾的分号——闸门只放行单条语句
        assertEquals("SELECT 2",
                MappingsController.renderSql("t", "sum(x)", "SELECT 2;"));
        assertEquals("SELECT sum(amt) / 100.0 FROM dw.orders",
                MappingsController.renderSql("dw.orders", "sum(amt) / 100.0", null));
        // 只有表名不是一个可执行的口径
        assertNull(MappingsController.renderSql("t", null, null));
    }
}
