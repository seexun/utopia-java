package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.RelationAxioms;
import tech.seexun.jknow.store.PhraseBindingsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 移植自 tests/store/negative_binding_definition_edit.rs（316 行，6 例）——负向判定也依赖
 * 已有的定义与新的候选（#773）：判成 none / undecided 之后库里长出新类/新属性，判定过期；
 * 库外的新定义不影响本库；人判过的（哪怕过期）仍拒绝代理改写。
 */
class NegativeBindingDefinitionEditTests extends HeartStoreTestBase {

    private static final String WORD = "candidate wording";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TypeBindingsService typeBindings;
    @Autowired
    PhraseBindingsService phraseBindings;
    @Autowired
    OntologyService ontology;
    @Autowired
    GraphWriteService writes;

    private enum BindingKind {
        KIND_WORD("entity_types", "type_bindings"),
        PHRASE("relation_types", "phrase_bindings");

        final String definitions;
        final String bindings;

        BindingKind(String definitions, String bindings) {
            this.definitions = definitions;
            this.bindings = bindings;
        }

        boolean stale(UUID kb, TypeBindingsService typeBindings,
                      PhraseBindingsService phraseBindings) {
            return !switch (this) {
                case KIND_WORD -> typeBindings.stale(kb).isEmpty();
                case PHRASE -> phraseBindings.stale(kb).isEmpty();
            };
        }

        boolean decide(UUID kb, UUID chosen, String status, String actor,
                       TypeBindingsService typeBindings, PhraseBindingsService phraseBindings) {
            var votes = JsonNodeFactory.instance.objectNode();
            return switch (this) {
                case KIND_WORD -> typeBindings.decide(kb, WORD, List.of(), chosen, status, votes, actor);
                case PHRASE -> {
                    PhraseSignature sig = phraseBindings.signatures(kb).stream()
                            .filter(s -> s.phrase().equals(WORD)).findFirst()
                            .orElseThrow(() -> new IllegalStateException(
                                    "the open statement produces a signature"));
                    yield phraseBindings.decide(kb, sig, new PhraseBindingsService.Decision(
                            chosen, chosen != null ? "forward" : null, status, votes, actor,
                            null, null, false));
                }
            };
        }
    }

    private enum Change {
        EDIT, ADD
    }

    private void lifecycle(BindingKind kind, String status, Change change, String actor) {
        UUID org = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'negative-binding-test')", org);
        try {
            exercise(org, kind, status, change, actor);
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", org);
        }
    }

    private void exercise(UUID org, BindingKind kind, String status, Change change, String actor) {
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID otherKb = Uuids.nowV7();
        UUID definition = Uuids.nowV7();
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'binding-test')",
                ws, org);
        for (UUID base : new UUID[]{kb, otherKb}) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'test')",
                    base, ws);
        }
        String table = kind.definitions;
        // 不靠睡眠控制次序：创建 < 判定 < 下面的真编辑
        jdbc.update("INSERT INTO " + table + " (id, kb_id, key, label, description, created_at, updated_at)"
                + " VALUES (?, ?, 'candidate', 'candidate', 'old definition', '2000-01-01', '2000-01-01')",
                definition, kb);
        UUID subject = Uuids.nowV7();
        UUID object = Uuids.nowV7();
        for (UUID entity : new UUID[]{subject, object}) {
            jdbc.update("""
                    INSERT INTO entities (id, kb_id, canonical_name, specific_type)
                     VALUES (?, ?, ?, ?)
                    """, entity, kb, entity.toString(), WORD);
        }
        writes.insertOpenStatement(kb, subject, WORD, object, null, null, 0.9f);
        assertEquals(WORD, typeBindings.signatures(kb).get(0).kindWord());
        UUID chosen = "bound".equals(status) ? definition : null;
        assertTrue(kind.decide(kb, chosen, status, actor, typeBindings, phraseBindings));
        jdbc.update("UPDATE " + kind.bindings + " SET decided_at = '2000-01-02' WHERE kb_id = ?", kb);
        assertFalse(kind.stale(kb, typeBindings, phraseBindings), "unchanged inputs stay cached");

        // 别的库里更新的候选不许作废这个库的判定
        jdbc.update("INSERT INTO " + table + " (id, kb_id, key, label) VALUES (?, ?, 'other', 'other')",
                Uuids.nowV7(), otherKb);
        assertFalse(kind.stale(kb, typeBindings, phraseBindings), "invalidation is scoped to the KB");

        switch (change) {
            case ADD -> jdbc.update(
                    "INSERT INTO " + table + " (id, kb_id, key, label) VALUES (?, ?, 'new', 'new')",
                    Uuids.nowV7(), kb);
            case EDIT -> {
                switch (kind) {
                    case KIND_WORD -> ontology.updateEntityType(kb, definition, "candidate", null,
                            "circle", List.of(), "expanded definition");
                    case PHRASE -> ontology.updateRelationType(kb, definition, "candidate", "state",
                            new RelationAxioms(false, false, false, false, false, false, null, null),
                            "expanded definition", null, null, null, null);
                }
            }
        }
        Boolean createdBefore = jdbc.queryForObject(
                "SELECT created_at < '2000-01-02'::timestamptz FROM " + table + " WHERE id = ?",
                Boolean.class, definition);
        Boolean updatedAfter = jdbc.queryForObject(
                "SELECT updated_at > '2000-01-02'::timestamptz FROM " + table + " WHERE id = ?",
                Boolean.class, definition);
        assertTrue(createdBefore);
        assertEquals(change == Change.EDIT, updatedAfter);
        assertTrue(kind.stale(kb, typeBindings, phraseBindings), kind + " " + status + " should be stale after " + change);

        if ("person".equals(actor)) {
            assertFalse(kind.decide(kb, definition, "bound", "agent", typeBindings, phraseBindings),
                    "a stale person decision still rejects automatic replacement");
            var saved = jdbc.queryForMap(
                    "SELECT status, decided_by FROM " + kind.bindings + " WHERE kb_id = ?", kb);
            assertEquals(status, saved.get("status"));
            assertEquals(actor, saved.get("decided_by"));
        } else {
            // 新的判定（包括又一条负向的）把工作重新收口
            assertTrue(kind.decide(kb, chosen, status, actor, typeBindings, phraseBindings));
            assertFalse(kind.stale(kb, typeBindings, phraseBindings), "a fresh decision stays cached");
        }
    }

    @Test
    void newCandidatesAndBoundEditsStillInvalidate() {
        for (BindingKind kind : BindingKind.values()) {
            lifecycle(kind, "bound", Change.EDIT, "agent");
            for (String status : new String[]{"none", "undecided"}) {
                lifecycle(kind, status, Change.ADD, "agent");
            }
        }
    }

    @Test
    void stalePersonDecisionsStillRejectAgentWrites() {
        for (BindingKind kind : BindingKind.values()) {
            for (String status : new String[]{"none", "undecided"}) {
                for (Change change : Change.values()) {
                    lifecycle(kind, status, change, "person");
                }
            }
        }
    }

    @Test
    void classEditReconsidersNone() {
        lifecycle(BindingKind.KIND_WORD, "none", Change.EDIT, "agent");
    }

    @Test
    void classEditReconsidersUndecided() {
        lifecycle(BindingKind.KIND_WORD, "undecided", Change.EDIT, "agent");
    }

    @Test
    void propertyEditReconsidersNone() {
        lifecycle(BindingKind.PHRASE, "none", Change.EDIT, "agent");
    }

    @Test
    void propertyEditReconsidersUndecided() {
        lifecycle(BindingKind.PHRASE, "undecided", Change.EDIT, "agent");
    }
}
