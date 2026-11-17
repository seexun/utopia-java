package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/an_untyped_name_meets_its_namesake.rs（80 行）——没有类的实体也要
 * 撞得上同名者（0044 第一刀之后的实测）。开放图谱里的实体都没有类：类由对齐来定，
 * 抽取只记文档的字。候选查询从前写的是 {@code e.type_id = $2}，传 NULL 时这个条件永远
 * 不成立——于是同一个库里「Securities and Exchange Commission」和它的全大写写法成了
 * 两个实体。这里守的是：两次没有类的提及，写法只差大小写，落到同一个实体
 * （{@code IS NOT DISTINCT FROM} 是 0009 的 NULL 陷阱修复）。
 */
class AnUntypedNameMeetsItsNamesakeTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    @Test
    void an_untyped_mention_attaches_to_its_untyped_namesake() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'untyped-namesake-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'untyped-namesake-test')",
                ws, org);
        jdbc.update(
                "INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'untyped-namesake-test')",
                kb, ws);
        try {
            var first = resolution.resolveMention(kb, null,
                    "Securities and Exchange Commission", null, null, null, List.of());
            assertTrue(first.created(), "第一次提及要建实体");
            var again = resolution.resolveMention(kb, null,
                    "SECURITIES AND EXCHANGE COMMISSION", null, null, null, List.of());
            assertEquals(first.entityId(), again.entityId(),
                    "只差大小写的同名提及要落到同一个没有类的实体上");
            assertFalse(again.created());
            Long n = jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND merged_into IS NULL",
                    Long.class, kb);
            assertEquals(1L, n);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
