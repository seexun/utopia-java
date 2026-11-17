package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_described_thing_is_an_entity_without_a_name.rs（132 行）——
 * 一个被描述的东西是一个没有名字的实体（0044 第一刀，#729）。create_described 写
 * description、不写 known_as：名字事实是召回的桥（0041），一段描述不能做桥——两篇文档
 * 里碰巧描述得一样的两个东西不该因此接到一起。
 */
class ADescribedThingIsAnEntityWithoutANameTests extends HeartStoreTestBase {

    private static final String ORG = "described-thing-test";

    @Autowired
    ResolutionService resolution;
    @Autowired
    NamesService names;

    @Test
    void a_described_thing_has_a_description_and_no_name() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, ?)", org, ORG);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)", kb, ws, ORG);
        try {
            String description = "the buyer's Shanghai subsidiary";
            UUID id = resolution.createDescribed(kb, description, "subsidiary");

            var row = jdbc.queryForMap(
                    "SELECT canonical_name, description, type_id, specific_type FROM entities WHERE id = ?",
                    id);
            assertEquals(description, row.get("description"), "描述是它的身份");
            assertEquals(description, row.get("canonical_name"), "显示名就是描述本身");
            assertNull(row.get("type_id"), "文档的类别词不是本体的类");
            assertEquals("subsidiary", row.get("specific_type"));

            // 没有一条以它为主语的事实——尤其没有 known_as
            Long facts = jdbc.queryForObject("SELECT count(*) FROM facts WHERE subject_id = ?",
                    Long.class, id);
            assertEquals(0L, facts, "描述不写成名字事实");
            assertTrue(names.forEntity(kb, id, null).isEmpty(), "名字栏是空的");

            // 按名字召回找不到它：描述不是桥
            assertNull(resolution.existingByName(kb, description),
                    "一段描述不能把后来的提及接到它身上");
            // 名字事实那条召回也找不到它：库里有一条同样小写值的名字事实才算
            // （text[] 经 createArrayOf 绑定——PgJDBC 不认裸 Object[] 的 setObject）
            List<UUID> recalled = jdbc.query(con -> {
                var ps = con.prepareStatement("""
                        SELECT e.id FROM entities e
                         WHERE e.kb_id = ? AND e.merged_into IS NULL AND e.id IN
                               (SELECT nf.subject_id FROM facts nf
                                 JOIN relation_types nr ON nr.id = nf.predicate_id
                                WHERE nf.kb_id = ? AND nr.kb_id = ? AND nr.builtin AND nr.key = 'known_as'
                                  AND nf.invalidated_at IS NULL AND nf.object_value IS NOT NULL
                                  AND lower(nf.object_value->>'value') = ANY(?))
                        """);
                ps.setObject(1, kb);
                ps.setObject(2, kb);
                ps.setObject(3, kb);
                ps.setArray(4, con.createArrayOf("text",
                        new String[]{description.toLowerCase()}));
                return ps;
            }, (rs, i) -> rs.getObject(1, UUID.class));
            assertTrue(recalled.isEmpty());
            // 后来一条同样措辞的提及走消解：另建一个，不归到被描述的那个身上
            var later = resolution.resolveMention(kb, null, description, null, null, null, List.of());
            assertTrue(later.created(), "描述不是桥，提及不归到它身上");
            assertNotEquals(id, later.entityId());

            // 同一段描述再建一次是另一个实体：库里不去重，同一篇里并成一个是调用方的事
            UUID twin = resolution.createDescribed(kb, description, null);
            assertNotEquals(twin, id);
            String specific = jdbc.queryForObject(
                    "SELECT specific_type FROM entities WHERE id = ?", String.class, twin);
            assertNull(specific);

            assertThrows(AppException.class,
                    () -> resolution.createDescribed(kb, "   ", null), "空描述不是一个东西");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
