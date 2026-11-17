package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/an_undeclared_name_is_looked_up.rs（1 例）——existing_by_name（#559）：
 * 一个没声明的主宾是不是库里已经有的东西。不问类型，认别名，并掉的不算；没有就是没有。
 * 断言逐条不改写。
 */
class AnUndeclaredNameIsLookedUpTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ResolutionService resolution;
    @Autowired
    NamesService names;

    @Test
    void aKnownNameIsFoundByAnySpellingAndAnUnknownOneIsNot() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID merged = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'lookup-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'lookup-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'lookup-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label)"
                + " VALUES (?, ?, 'organization', 'Organization')", organization, kb);
        try {
            // 有类型的、带别名的一个；并掉的一个（同名，不该被找到）。
            // 别名是一条名字事实（0041），不再是 entities 上的一列
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name)"
                    + " VALUES (?, ?, ?, 'Acme Corporation')", acme, kb, organization);
            names.record(kb, acme, "Acme Corporation", null, null);
            names.record(kb, acme, "ACME", null, null);
            jdbc.update("INSERT INTO entities (id, kb_id, canonical_name, merged_into)"
                    + " VALUES (?, ?, 'Nova Labs', ?)", merged, kb, acme);

            assertEquals(acme, resolution.existingByName(kb, "Acme Corporation"), "原名");
            assertEquals(acme, resolution.existingByName(kb, "acme corporation"), "大小写不论");
            assertEquals(acme, resolution.existingByName(kb, "ACME"), "别名也算");
            assertEquals(acme, resolution.existingByName(kb, "Acme"),
                    "泛用后缀词干互推：Acme ↔ Acme Corporation");
            assertNull(resolution.existingByName(kb, "Nova Labs"), "并掉的不算");
            assertNull(resolution.existingByName(kb, "lawsuit against Acme"), "描述不是实体");
        } finally {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
