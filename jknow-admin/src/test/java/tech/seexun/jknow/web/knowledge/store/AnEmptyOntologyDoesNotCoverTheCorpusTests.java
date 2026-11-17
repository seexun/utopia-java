package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.mapper.OntologyMapper.Uncovered;

/**
 * 移植自 tests/store/an_empty_ontology_does_not_cover_the_corpus.rs（1 例）——文档说了、
 * 本体还放不下的有多少：没绑到类的类别词、没绑到属性的短语（判成 none 的与两票不一致的
 * 都算）。工作台从前拿 0003 的 ontology_misses 说「覆盖了」，而开放图谱的抽取不往那张表
 * 里写：一个类都没有的库，二十二个类别词没着落，页面写着「覆盖了」。自建自拆。
 */
class AnEmptyOntologyDoesNotCoverTheCorpusTests extends HeartStoreTestBase {

    private static final String ORG = "empty-ontology-does-not-cover-test";

    @Autowired
    OntologyService ontology;

    @Test
    void kindWordsAndPhrasesWithNoPlaceAreCountedPerBase() {
        jdbc.update("DELETE FROM organizations WHERE name = ?", ORG);
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID other = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID leads = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,?)", org, ORG);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?, ?, ?)", ws, org, ORG);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name) VALUES (?, ?, 'a'),"
                + " (?, ?, 'b')", kb, ws, other, ws);
        try {
            // 什么都还没读过：没有要等的
            assertEquals(new Uncovered(0, 0), ontology.uncovered(kb));

            // 读过文档、一个类都没有：每个类别词都没着落
            for (String word : new String[] {"person", "project", "city"}) {
                jdbc.update("INSERT INTO type_bindings (id, kb_id, kind_word, status)"
                        + " VALUES (?, ?, ?, 'none')", Uuids.nowV7(), kb, word);
            }
            assertEquals(3, ontology.uncovered(kb).kindWords());

            // 加了一个类、一条属性：绑上的不算，判成 none 的和两票不一致的都算
            jdbc.update("INSERT INTO entity_types(id,kb_id,key,label)"
                    + " VALUES (?, ?, 'person', 'person')", person, kb);
            jdbc.update("INSERT INTO relation_types(id,kb_id,key,label,kind,temporal,description)"
                    + " VALUES (?, ?, 'leads', 'leads', 'relation', 'state', '')", leads, kb);
            jdbc.update("UPDATE type_bindings SET status='bound', type_id=?"
                    + " WHERE kb_id=? AND kind_word='person'", person, kb);
            jdbc.update("UPDATE type_bindings SET status='undecided' WHERE kb_id=?"
                    + " AND kind_word='city'", kb);
            jdbc.update("INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, status,"
                            + " relation_type_id, direction) VALUES"
                            + " (?, ?, '负责', ?, 'bound', ?, 'forward'),"
                            + " (?, ?, '汇报给', ?, 'none', NULL, NULL),"
                            + " (?, ?, '调任', ?, 'undecided', NULL, NULL)",
                    Uuids.nowV7(), kb, person, leads,
                    Uuids.nowV7(), kb, person,
                    Uuids.nowV7(), kb, person);
            jdbc.update("INSERT INTO type_bindings (id, kb_id, kind_word, status)"
                    + " VALUES (?, ?, 'supplier', 'none')", Uuids.nowV7(), other);
            assertEquals(new Uncovered(2, 2), ontology.uncovered(kb));
            assertEquals(1, ontology.uncovered(other).kindWords(), "each base counts its own");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }
}
