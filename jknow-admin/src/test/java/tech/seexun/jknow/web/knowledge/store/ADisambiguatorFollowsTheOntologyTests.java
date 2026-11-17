package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ResolutionService;

/**
 * 移植自 tests/store/a_disambiguator_follows_the_ontology.rs（239 行，#299）——同名后缀
 * 从本体来，不从一张手写词表来。词汇表用 schema.org 那一套（works_for），正是原先那张
 * 词表里没有的：这个测试在旧实现上必须是红的，否则它什么也没证明。
 */
class ADisambiguatorFollowsTheOntologyTests extends HeartStoreTestBase {

    @Autowired
    ResolutionService resolution;

    record Fixture(UUID org, UUID kb, UUID smithA, UUID smithB, UUID wangA, UUID wangB, UUID lin) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID organization = Uuids.nowV7();
        UUID document = Uuids.nowV7();
        // works_for 声明了值域；mentioned_in 是抽取顺手长出来的，没人说过它指向什么
        UUID worksFor = Uuids.nowV7();
        UUID mentionedIn = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID stMarys = Uuids.nowV7();
        UUID reportA = Uuids.nowV7();
        UUID reportB = Uuids.nowV7();
        UUID smithA = Uuids.nowV7();
        UUID smithB = Uuids.nowV7();
        UUID wangA = Uuids.nowV7();
        UUID wangB = Uuids.nowV7();
        UUID lin = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'disambig-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'disambig-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'disambig-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'organization', 'Organization')",
                organization, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'document', 'Document')",
                document, kb);
        // works_for：状态关系，声明了值域 → 身份性的
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal)
                 VALUES (?, ?, 'works_for', 'works for', 'state')
                """, worksFor, kb);
        jdbc.update(
                "INSERT INTO relation_type_ranges (relation_type_id, entity_type_id) VALUES (?, ?)",
                worksFor, organization);
        // mentioned_in：没声明值域，且是事件——置信度和时间都更占优，旧排序会选它
        jdbc.update("""
                INSERT INTO relation_types (id, kb_id, key, label, temporal)
                 VALUES (?, ?, 'mentioned_in', 'mentioned in', 'event')
                """, mentionedIn, kb);

        for (Object[] e : new Object[][]{{acme, organization, "Acme Robotics"},
                {stMarys, organization, "St Mary's Hospital"},
                {reportA, document, "2026 Field Report"},
                {reportB, document, "2026 Ward Roster"},
                {smithA, person, "John Smith"}, {smithB, person, "John Smith"},
                {wangA, person, "Wang Ning"}, {wangB, person, "Wang Ning"},
                {lin, person, "Lin Zhao"}}) {
            jdbc.update("INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    e[0], kb, e[1], e[2]);
        }
        // 两个 John Smith 分得清：雇主不同；同时各有一条更晚、更高置信的「被提到过」
        fact(kb, smithA, worksFor, acme, 0.8f, "2026-01-01T00:00:00Z");
        fact(kb, smithB, worksFor, stMarys, 0.8f, "2026-01-01T00:00:00Z");
        fact(kb, smithA, mentionedIn, reportA, 0.99f, "2026-05-01T00:00:00Z");
        fact(kb, smithB, mentionedIn, reportB, 0.99f, "2026-05-01T00:00:00Z");
        // 两个 Wang Ning 都在 Acme：这条事实分不开他们
        fact(kb, wangA, worksFor, acme, 0.8f, "2026-02-01T00:00:00Z");
        fact(kb, wangB, worksFor, acme, 0.8f, "2026-02-01T00:00:00Z");
        fact(kb, lin, worksFor, acme, 0.8f, "2026-02-01T00:00:00Z");
        return new Fixture(org, kb, smithA, smithB, wangA, wangB, lin);
    }

    private void fact(UUID kb, UUID subject, UUID predicate, UUID object, float confidence,
                      String rec) {
        jdbc.update("""
                INSERT INTO facts (id, kb_id, subject_id, predicate_id, object_id,
                                    confidence, recorded_at)
                 VALUES (?, ?, ?, ?, ?, ?, ?)
                """, Uuids.nowV7(), kb, subject, predicate, object, confidence,
                OffsetDateTime.parse(rec));
    }

    private String suffix(UUID id) {
        return jdbc.queryForObject("SELECT disambiguator FROM entities WHERE id = ?", String.class,
                id);
    }

    @Test
    void a_suffix_comes_from_the_ontology_not_a_word_list() {
        Fixture f = seed();
        try {
            for (String name : new String[]{"John Smith", "Wang Ning", "Lin Zhao"}) {
                resolution.refreshDisambiguators(f.kb(), name);
            }

            // 1. schema.org 的词汇也认。旧实现的词表里没有 works_for，两个人双双退回
            //    "Person"——分得清清楚楚的两个人，界面上却一模一样
            assertEquals("Acme Robotics", suffix(f.smithA()));
            assertEquals("St Mary's Hospital", suffix(f.smithB()));

            // 2. 更晚、更高置信的 mentioned_in 没有胜出：它没声明过值域，又是事件。
            //    「他被写进过哪份报告」不是他是谁
            assertNotEquals("2026 Field Report", suffix(f.smithA()));

            // 3. 同一个雇主分不开两个 Wang Ning：那就都写雇主，而不是编一个假的区分。
            //    共同的雇主仍是信息，只是它不承担区分的职责
            assertEquals("Acme Robotics", suffix(f.wangA()));
            assertEquals("Acme Robotics", suffix(f.wangB()));

            // 4. 组内唯一：没有要分开的对象，就不该挂后缀
            assertNull(suffix(f.lin()));

            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
            int gone = jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
            assertEquals(1, gone, "一次性 org 没删掉");
        } finally {
            // 失败路径也保证回收（org 名唯一，重复删无害）
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }
}
