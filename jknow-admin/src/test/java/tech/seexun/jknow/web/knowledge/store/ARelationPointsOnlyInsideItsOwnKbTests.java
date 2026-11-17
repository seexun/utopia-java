package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;

/**
 * 移植自 tests/store/a_relation_points_only_inside_its_own_kb.rs（339 行）——
 * inverseOf / subPropertyOf 的落库与边界。<b>这里守的第一条是知识库隔离</b>：列上的
 * 外键不认 kb_id，挡不住的话拿到任意一个 id 就能让推理机跨库读公理。前端只列本库的
 * 关系是界面礼貌不是边界，校验必须在 store 层，测试也必须在这里。
 *
 * <p>等价点：Rust 断言 {@code format!("{err:?}").contains("unknown_relation")} → Java
 * assertThrows(AppException) + 错误码检查（AppException.getCode）。
 */
class ARelationPointsOnlyInsideItsOwnKbTests extends HeartStoreTestBase {

    @Autowired
    OntologyService ontology;

    /** 一个 org 底下两个库。两个是必需的——这组测试的主角就是跨库那条线。 */
    private UUID[] twoKbs() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'link-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'link-test')", ws,
                org);
        for (UUID id : List.of(a, b)) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, ?)",
                    id, ws, id.equals(a) ? "link-a" : "link-b");
        }
        return new UUID[]{org, a, b};
    }

    /** 建一条最普通的关系，不带任何链。 */
    private UUID plain(UUID kb, String key) {
        return ontology.createRelationType(kb, key, key, "state",
                OntologyService.RelationAxioms.empty(), "", "relation", List.of(), List.of(),
                null, null);
    }

    private static String codeOf(Runnable call) {
        try {
            call.run();
            return "<no error>";
        } catch (AppException e) {
            return e.code().orElse("<no code>");
        }
    }

    @Test
    void a_relation_points_only_inside_its_own_kb() {
        UUID[] orgKbs = twoKbs();
        UUID org = orgKbs[0];
        UUID kbA = orgKbs[1];
        UUID kbB = orgKbs[2];
        try {
            UUID worksAt = plain(kbA, "works_at");
            UUID employs = plain(kbA, "employs");
            UUID ceoOf = plain(kbA, "ceo_of");
            // 另一个库里的关系。名字取一样的——同名不同库，正是最容易被当成自己人的那种
            UUID foreign = plain(kbB, "employs");

            // ---- 一、跨库指向：建的时候就该被拒
            assertEquals("unknown_relation", codeOf(() -> ontology.createRelationType(kbA,
                    "leaks", "leaks", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            foreign, null),
                    "", "relation", List.of(), List.of(), null, null)),
                    "**拒绝的理由不能透露那个 id 存在于别处**——不区分「不存在」与「在别的库」");
            Long leaked = jdbc.queryForObject(
                    "SELECT count(*) FROM relation_types WHERE kb_id = ? AND key = ?",
                    Long.class, kbA, "leaks");
            assertEquals(0L, leaked, "被拒的那次不该留下半行");

            // ---- 二、跨库指向：改的时候同样该被拒
            assertEquals("unknown_relation", codeOf(() -> ontology.updateRelationType(kbA,
                    worksAt, "works at", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            null, foreign),
                    "", null, null, null, null)),
                    "改成指向别的知识库同样必须被拒");

            // ---- 三、本库之内：写得进，也读得回
            ontology.updateRelationType(kbA, worksAt, "works at", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            employs, null),
                    "", null, null, null, null);
            ontology.updateRelationType(kbA, ceoOf, "ceo of", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            null, worksAt),
                    "", null, null, null, null);
            var views = ontology.relationTypes(kbA);
            var find = views.stream().filter(v -> v.id().equals(worksAt)).findFirst().orElseThrow();
            assertEquals(employs, find.inverseOf(),
                    "**视图必须回这两个值**——下拉框要显示当前选的是谁，读不回来的话打开表单是空的，保存一次就把声明抹了");
            var ceoView = views.stream().filter(v -> v.id().equals(ceoOf)).findFirst().orElseThrow();
            assertEquals(worksAt, ceoView.subPropertyOf());

            // ---- 四、缺省 = 清空，与上面六位公理同一条规矩
            ontology.updateRelationType(kbA, worksAt, "works at", "state",
                    OntologyService.RelationAxioms.empty(), "", null, null, null, null);
            assertEquals(null, ontology.relationTypes(kbA).stream()
                    .filter(v -> v.id().equals(worksAt)).findFirst().orElseThrow().inverseOf(),
                    "不传 = 清空。一半覆盖一半保留，会让「我把逆去掉了」和「我没碰逆」长得一模一样");

            // ---- 五、属性：不能有链，也不能当链的目标
            UUID salary = ontology.createRelationType(kbA, "salary", "salary", "state",
                    OntologyService.RelationAxioms.empty(), "", "attribute",
                    List.of(borrowClass(kbA)), List.of(), "number", null);
            assertEquals("link_target_is_attr", codeOf(() -> ontology.updateRelationType(kbA,
                    ceoOf, "ceo of", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            salary, null),
                    "", null, null, null, null)),
                    "属性不能当逆——它的宾语是字面值，反过来指回来无从谈起");
            assertEquals("attr_has_no_link", codeOf(() -> ontology.updateRelationType(kbA, salary,
                    "salary", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            null, ceoOf),
                    "", null, null, null, null)),
                    "属性自己也不能有父属性");

            // ---- 六、子属性不能是自己。库里有 CHECK，但那是 500
            assertEquals("sub_property_self", codeOf(() -> ontology.updateRelationType(kbA, ceoOf,
                    "ceo of", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            null, ceoOf),
                    "", null, null, null, null)),
                    "得在撞上 CHECK 之前给出人话，而不是让人看到一个 500");

            // 逆是自己**不拦**：那等于 symmetric，是合法声明。
            ontology.updateRelationType(kbA, employs, "employs", "state",
                    new OntologyService.RelationAxioms(false, false, false, false, false, false,
                            employs, null),
                    "", null, null, null, null);
            assertTrue(true, "逆是自己是合法声明（= symmetric），不拦");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    /** 借一个类给属性当 domain——属性没有类就建不出来。 */
    private UUID borrowClass(UUID kb) {
        return ontology.createEntityType(kb, "person", "Person", "#888888", "circle", List.of(),
                "");
    }
}
