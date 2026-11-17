package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.NamesService;

/**
 * 按名字找实体：名字完全相同的排在最前，再按度数（a_search_puts_the_exact_name_first.rs
 * 原值移植，M5 任务 6 台账收口）。
 *
 * <p>从前只按度数排、截在 limit 上。问「Apple」而库里有九个事实更多的「Apple Store …」，
 * 叫 Apple 的那个排到第十——对话与 MCP 的工具只读前八条，于是它找不到，按名字读事实时
 * 读成了另一个。别名同理：「IBM」是 International Business Machines 的一个名字（0041），
 * 问 IBM 的人要的就是它。回放那一支多绑一个时刻参数，顺序跟着挪了一位，所以也要走一遍。
 */
class ASearchPutsTheExactNameFirstTests extends StoreTestBase {

    @Autowired
    GraphService graph;
    @Autowired
    NamesService names;

    private UUID entity(UUID kb, UUID type, String name) {
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name)"
                + " VALUES (?, ?, ?, ?)", id, kb, type, name);
        return id;
    }

    /// 一条事实，让 subject 的度数加一
    private void busy(UUID kb, UUID subject, UUID rel, UUID object) {
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,"
                + " recorded_at) VALUES (?, ?, ?, ?, ?, now())", Uuids.nowV7(), kb, subject,
                rel, object);
    }

    @Test
    void aSearchPutsTheExactNameFirst() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID ty = Uuids.nowV7();
        UUID rel = Uuids.nowV7();
        try {
            jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'exact-name-search-test')",
                    org);
            jdbc.update("INSERT INTO workspaces (id, org_id, name)"
                    + " VALUES (?, ?, 'exact-name-search-test')", ws, org);
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name)"
                    + " VALUES (?, ?, 'exact-name-search-test')", kb, ws);
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label)"
                    + " VALUES (?, ?, 'organization', 'Organization')", ty, kb);
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label, kind)"
                    + " VALUES (?, ?, 'located_in', 'located in', 'relation')", rel, kb);
            UUID city = entity(kb, ty, "Cupertino");

            // 叫 Apple 的没有事实；九个名字里带 Apple 的各有一条，度数都比它高
            UUID apple = entity(kb, ty, "Apple");
            for (int i = 1; i <= 9; i++) {
                UUID store = entity(kb, ty, "Apple Store " + i);
                busy(kb, store, rel, city);
            }
            GraphService.Search out = graph.searchEntities(kb, "Apple", 8, 0, null);
            assertEquals(10, out.total());
            assertEquals(8, out.entities().size());
            assertEquals(apple, out.entities().get(0).id(),
                    "the entity named exactly Apple comes first");

            // 回放那一支：时刻参数挪了一位，同名仍在最前（时刻取库钟——JVM 钟与
            // Docker PG 钟有漂移时，e.created_at <= asOf 会整行滤空，等价点记 javadoc）
            GraphService.Search replay = graph.searchEntities(kb, "Apple", 8, 0,
                    jdbc.queryForObject("SELECT now()", java.time.OffsetDateTime.class));
            assertEquals(apple, replay.entities().get(0).id(),
                    "the same holds when replaying a moment");

            // 别名：IBM 是 International Business Machines 的一个名字，大小写不论
            UUID ibm = entity(kb, ty, "International Business Machines");
            names.record(kb, ibm, "IBM", null, null);
            for (int i = 1; i <= 9; i++) {
                UUID lab = entity(kb, ty, "IBM Lab " + i);
                busy(kb, lab, rel, city);
            }
            GraphService.Search byAlias = graph.searchEntities(kb, "ibm", 8, 0, null);
            assertEquals(10, byAlias.total());
            assertEquals(ibm, byAlias.entities().get(0).id(),
                    "an entity whose name is exactly IBM comes first");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
