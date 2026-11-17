package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.ExportStore;

/**
 * 移植自 tests/store/exported_references_never_cross_a_kb.rs（3 例）。
 *
 * <p>写入侧：导出会触碰的每条边都被 0070 的外键/触发器挡住；导出侧体检覆盖 0070 保护的
 * <b>全部</b>结构引用边——别库/悬空的行宁可整份拒导，也不许伪造 IRI 或静默丢语义。落到
 * 别库的下场分两种：
 * <ul>
 *   <li>铸成本库 IRI 的引用（实体、事实、派生、文档、段落、规则）→ 伪造身份；</li>
 *   <li>进本库词汇表按 id 查的引用（谓词、属性类型、实体类型、父类）→ 静默消失。</li>
 * </ul>
 * 两种都是坏行，触发器与导出侧一律拒。坏行靠 {@code SET LOCAL
 * session_replication_role='replica'} 造——只关本事务的触发器（含 FK 强制），提交即恢复；
 * 这同时允许造出「指着的行已不在」的悬空引用，正是从 0070 之前的备份恢复进来的形态。
 */
class ExportedReferencesNeverCrossAKbTests extends StoreTestBase {

    private static Fixture f;

    @Autowired
    private DataSource dataSource;

    record Fixture(UUID org, UUID a, UUID b, UUID docA, UUID docB, UUID chunkA, UUID chunkB,
                   UUID entA, UUID entB, UUID factA, UUID factB, UUID relA, UUID relB, UUID clsA,
                   UUID clsB, UUID ruleA, UUID ruleB, UUID aruleA, UUID aruleB, UUID derA,
                   UUID derB, UUID iruleA, UUID iruleB, UUID arvA, UUID arvB, UUID erunA,
                   UUID erunB, UUID eactA) {
    }

    /** 两库，每库一套能被引用的零件：文档/段落/实体/事实/谓词/类/公理规则/业务规则/派生。
     *  每个 @Test 各自种一份（Rust 同形）：retained 那例会物理删行，共享种子会互相串味。 */
    @BeforeEach
    void seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID a = Uuids.nowV7();
        UUID b = Uuids.nowV7();
        UUID docA = Uuids.nowV7();
        UUID docB = Uuids.nowV7();
        UUID chunkA = Uuids.nowV7();
        UUID chunkB = Uuids.nowV7();
        UUID entA = Uuids.nowV7();
        UUID entB = Uuids.nowV7();
        UUID factA = Uuids.nowV7();
        UUID factB = Uuids.nowV7();
        UUID relA = Uuids.nowV7();
        UUID relB = Uuids.nowV7();
        UUID clsA = Uuids.nowV7();
        UUID clsB = Uuids.nowV7();
        UUID ruleA = Uuids.nowV7();
        UUID ruleB = Uuids.nowV7();
        UUID aruleA = Uuids.nowV7();
        UUID aruleB = Uuids.nowV7();
        UUID derA = Uuids.nowV7();
        UUID derB = Uuids.nowV7();
        UUID iruleA = Uuids.nowV7();
        UUID iruleB = Uuids.nowV7();
        UUID arvA = Uuids.nowV7();
        UUID arvB = Uuids.nowV7();
        UUID erunA = Uuids.nowV7();
        UUID erunB = Uuids.nowV7();
        UUID eactA = Uuids.nowV7();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'xkb-ref-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'xkb-ref-test')", ws, org);
        for (UUID kb : List.of(a, b)) {
            jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) "
                    + "VALUES (?, ?, 'xkb-ref-test')", kb, ws);
        }
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, external_key) "
                + "VALUES (?, ?, 'a.md', 'sha-a', 'ready', 'file:///a.md')", docA, a);
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status, external_key) "
                + "VALUES (?, ?, 'b.md', 'sha-b', 'ready', 'file:///b.md')", docB, b);
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) "
                + "VALUES (?, ?, ?, 0, 'x')", chunkA, a, docA);
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text) "
                + "VALUES (?, ?, ?, 0, 'x')", chunkB, b, docB);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entA, a);
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'e')", entB, b);
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, object_id, confidence) "
                + "VALUES (?, ?, ?, ?, 0.9)", factA, a, entA, entA);
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, object_id, confidence) "
                + "VALUES (?, ?, ?, ?, 0.9)", factB, b, entB, entB);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, 'r')",
                relA, a, "r-" + relA);
        jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, 'r')",
                relB, b, "r-" + relB);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, 'c')",
                clsA, a, "c-" + clsA);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, 'c')",
                clsB, b, "c-" + clsB);
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                + "VALUES (?, ?, ?, 'transitive')", ruleA, a, relA);
        jdbc.update("INSERT INTO rules (id, kb_id, predicate_id, kind) "
                + "VALUES (?, ?, ?, 'transitive')", ruleB, b, relB);
        jdbc.update("INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_predicate_id, conclude_value) "
                        + "VALUES (?, ?, ?, ?, 'attribute', ?, '42'::jsonb)",
                aruleA, a, "ar-" + aruleA, clsA, relA);
        jdbc.update("INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_predicate_id, conclude_value) "
                        + "VALUES (?, ?, ?, ?, 'attribute', ?, '42'::jsonb)",
                aruleB, b, "ar-" + aruleB, clsB, relB);
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", derA, a, entA, relA, entA, ruleA);
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                + "rule_id) VALUES (?, ?, ?, ?, ?, ?)", derB, b, entB, relB, entB, ruleB);
        // 0073 之后进库的同库家族成员：蕴含规则、读数缓存、勘误账、定义史——供越库断言取用
        jdbc.update("INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                + "conclude_property_id, status) VALUES (?, ?, 'phrase', 'p', ?, 'approved')",
                iruleA, a, relA);
        jdbc.update("INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                + "conclude_property_id, status) VALUES (?, ?, 'phrase', 'p', ?, 'approved')",
                iruleB, b, relB);
        jdbc.update("INSERT INTO attribute_rule_versions (id, kb_id, rule_id, seq, definition) "
                + "VALUES (?, ?, ?, 1, '{}'::jsonb)", arvA, a, aruleA);
        jdbc.update("INSERT INTO attribute_rule_versions (id, kb_id, rule_id, seq, definition) "
                + "VALUES (?, ?, ?, 1, '{}'::jsonb)", arvB, b, aruleB);
        jdbc.update("INSERT INTO errata_runs (id, kb_id, document_id) VALUES (?, ?, ?)", erunA, a, docA);
        jdbc.update("INSERT INTO errata_runs (id, kb_id, document_id) VALUES (?, ?, ?)", erunB, b, docB);
        jdbc.update("INSERT INTO errata_actions (id, kb_id, run_id, document_id, action, status) "
                + "VALUES (?, ?, ?, ?, 'keep', 'held')", eactA, a, erunA, docA);
        // 读数缓存没有 id 列——键是 (kb_id, reading, phrase)
        jdbc.update("INSERT INTO phrase_readings (kb_id, reading, phrase, entity_id) "
                + "VALUES (?, 'nationality', 'r', ?)", a, entA);
        jdbc.update("INSERT INTO phrase_readings (kb_id, reading, phrase, entity_id) "
                + "VALUES (?, 'nationality', 'r', ?)", b, entB);
        f = new Fixture(org, a, b, docA, docB, chunkA, chunkB, entA, entB, factA, factB, relA, relB,
                clsA, clsB, ruleA, ruleB, aruleA, aruleB, derA, derB, iruleA, iruleB, arvA, arvB,
                erunA, erunB, eactA);
    }

    @AfterEach
    void cleanup() {
        if (!sharedStackUp() || f == null) {
            return;
        }
        for (UUID kb : List.of(f.a(), f.b())) {
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        }
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    /** 新的跨库写：每条边都被它自己的触发器当场挡下。 */
    @Test
    void new_cross_kb_writes_are_rejected_on_every_exported_edge() {
        // —— 派生的前提：两种前提，两种越库形态
        for (UUID[] row : new UUID[][]{{f.factB(), null}, {null, f.derB()}}) {
            assertRejected("derivation premise",
                    "INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, "
                            + "premise_derived_id, seq) VALUES (?, ?, ?, 0)",
                    f.derA(), row[0], row[1]);
        }

        // —— 边上的属性：别库类型会被词汇表静默跳过，别库实体会被铸进本库 IRI
        assertRejected("qualifier.type→foreign",
                "INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, value) "
                        + "VALUES (?, ?, '\"lit\"'::jsonb)", f.factA(), f.relB());
        assertRejected("qualifier.entity→foreign",
                "INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, entity_id) "
                        + "VALUES (?, ?, ?)", f.factA(), f.relA(), f.entB());

        // —— 事实本体的五个引用列（from_statement_id 是同表自指：immediate 端在 BEFORE
        //    触发器上，目标早已在场时当场拒）
        assertRejected("fact.subject", "INSERT INTO facts (id, kb_id, subject_id, subject_id_bogus) "
                        + "VALUES (?, ?, ?, NULL)".replace("subject_id_bogus", "subject_id"),
                Uuids.nowV7(), f.a(), f.entB());
        assertRejected("fact.object",
                "INSERT INTO facts (id, kb_id, subject_id, object_id) VALUES (?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.entB());
        assertRejected("fact.predicate",
                "INSERT INTO facts (id, kb_id, subject_id, predicate_id) VALUES (?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relB());
        assertRejected("fact.supersedes",
                "INSERT INTO facts (id, kb_id, subject_id, supersedes) VALUES (?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.factB());
        assertRejected("fact.from_statement",
                "INSERT INTO facts (id, kb_id, subject_id, from_statement_id) VALUES (?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.factB());

        // —— 陈述来源（typed_fact_sources，0068）：A 的事实吃 B 的陈述
        assertRejected("factsource.statement→foreign",
                "INSERT INTO typed_fact_sources (fact_id, statement_id) VALUES (?, ?)",
                f.factA(), f.factB());

        // —— 开放陈述的属性（statement_qualifiers，0061）
        assertRejected("squalifier.entity→foreign",
                "INSERT INTO statement_qualifiers (fact_id, role, entity_id) VALUES (?, 'as', ?)",
                f.factA(), f.entB());

        // —— 时间提及（0061/0064）
        assertRejected("timemention.fact→foreign",
                "INSERT INTO time_mentions (id, kb_id, fact_id, chunk_id, text, char_start) "
                        + "VALUES (?, ?, ?, ?, '去年', 0)",
                Uuids.nowV7(), f.a(), f.factB(), f.chunkA());
        assertRejected("timemention.chunk→foreign",
                "INSERT INTO time_mentions (id, kb_id, fact_id, chunk_id, text, char_start) "
                        + "VALUES (?, ?, ?, ?, '去年', 0)",
                Uuids.nowV7(), f.a(), f.factA(), f.chunkB());

        // —— 类型与短语绑定（0065/0066）
        assertRejected("binding.type→foreign",
                "INSERT INTO type_bindings (id, kb_id, kind_word, status, type_id) "
                        + "VALUES (?, ?, 'corp', 'bound', ?)",
                Uuids.nowV7(), f.a(), f.clsB());
        assertRejected("pbinding.subject_type→foreign",
                "INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, status) "
                        + "VALUES (?, ?, 'runs', ?, 'none')",
                Uuids.nowV7(), f.a(), f.clsB());
        assertRejected("pbinding.relation→foreign",
                "INSERT INTO phrase_bindings (id, kb_id, phrase, relation_type_id, direction, "
                        + "status) VALUES (?, ?, 'runs', ?, 'forward', 'bound')",
                Uuids.nowV7(), f.a(), f.relB());

        // —— 派生事实本体的五个引用列
        assertRejected("derived.subject",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, rule_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entB(), f.relA(), f.ruleA());
        assertRejected("derived.object",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, rule_id, object_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.ruleA(), f.entB());
        assertRejected("derived.predicate",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, rule_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relB(), f.ruleA());
        assertRejected("derived.rule",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, rule_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.ruleB());
        // attribute_rule 与 rule 互斥：测 attribute_rule 那行不带 rule_id
        assertRejected("derived.attribute_rule",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, attribute_rule_id) "
                        + "VALUES (?, ?, ?, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.aruleB());

        // —— 实体的类、类层级、互斥、domain/range
        assertRejected("entity.type→foreign",
                "INSERT INTO entities (id, kb_id, canonical_name, type_id) VALUES (?, ?, 'e', ?)",
                Uuids.nowV7(), f.a(), f.clsB());
        assertRejected("class.parent→foreign",
                "INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)",
                f.clsA(), f.clsB());
        assertRejected("class.disjoint→foreign",
                "INSERT INTO entity_type_disjoint (kb_id, a_id, b_id) VALUES (?, ?, ?)",
                f.a(), f.clsA(), f.clsB());
        for (String table : new String[]{"relation_type_domains", "relation_type_ranges"}) {
            assertRejected(table,
                    "INSERT INTO " + table + " (relation_type_id, entity_type_id) VALUES (?, ?)",
                    f.relA(), f.clsB());
        }

        // —— 关系的同表自指与边属性声明
        assertRejected("relation.inverse→foreign",
                "INSERT INTO relation_types (id, kb_id, key, label, inverse_of) "
                        + "VALUES (?, ?, 'inv', 'inv', ?)",
                Uuids.nowV7(), f.a(), f.relB());
        assertRejected("relation.sub_property→foreign",
                "INSERT INTO relation_types (id, kb_id, key, label, sub_property_of) "
                        + "VALUES (?, ?, 'sub', 'sub', ?)",
                Uuids.nowV7(), f.a(), f.relB());
        assertRejected("relation.qualifier→foreign",
                "INSERT INTO relation_type_qualifiers (relation_type_id, qualifier_type_id) "
                        + "VALUES (?, ?)",
                f.relA(), f.relB());

        // —— 规则本体：公理编在哪个谓词上、业务规则看什么类得什么结论
        assertRejected("rule.predicate→foreign",
                "INSERT INTO rules (id, kb_id, predicate_id, kind) VALUES (?, ?, ?, 'transitive')",
                Uuids.nowV7(), f.a(), f.relB());
        // 每条结论形状自己的必填列（CHECK 钉死了 typing/attribute/computed 的列组）
        assertRejected("arule.subject_type→foreign",
                "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_predicate_id, conclude_value) "
                        + "VALUES (?, ?, 'ar', ?, 'attribute', ?, '42'::jsonb)",
                Uuids.nowV7(), f.a(), f.clsB(), f.relA());
        assertRejected("arule.conclude_type→foreign",
                "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_type_id) VALUES (?, ?, 'ar', ?, 'typing', ?)",
                Uuids.nowV7(), f.a(), f.clsA(), f.clsB());
        assertRejected("arule.conclude_predicate→foreign",
                "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_predicate_id, conclude_value) "
                        + "VALUES (?, ?, 'ar', ?, 'attribute', ?, '42'::jsonb)",
                Uuids.nowV7(), f.a(), f.clsA(), f.relB());

        // —— #901:0070 之后进库的表也归同一条不变量 ——
        assertRejected("irule.subject_type→foreign",
                "INSERT INTO implication_rules (id, kb_id, trigger, phrase, conclude_property_id, "
                        + "status, subject_type_id) VALUES (?, ?, 'phrase', 'p', ?, 'approved', ?)",
                Uuids.nowV7(), f.a(), f.relA(), f.clsB());
        assertRejected("irule.object_type→foreign",
                "INSERT INTO implication_rules (id, kb_id, trigger, phrase, conclude_property_id, "
                        + "status, object_type_id) VALUES (?, ?, 'phrase', 'p', ?, 'approved', ?)",
                Uuids.nowV7(), f.a(), f.relA(), f.clsB());
        assertRejected("irule.conclude_property→foreign",
                "INSERT INTO implication_rules (id, kb_id, trigger, phrase, conclude_property_id, "
                        + "status) VALUES (?, ?, 'phrase', 'p', ?, 'approved')",
                Uuids.nowV7(), f.a(), f.relB());

        // 读数缓存答出的实体（复合外键）；UPDATE 路径同样拦
        assertRejected("reading.entity→foreign",
                "INSERT INTO phrase_readings (kb_id, reading, phrase, entity_id) "
                        + "VALUES (?, 'nationality', 'x', ?)",
                f.a(), f.entB());
        assertRejected("reading.entity 改成别库也必须被拒",
                "UPDATE phrase_readings SET entity_id = ? WHERE kb_id = ? AND phrase = 'r'",
                f.entB(), f.a());

        // 隐含事实的出处（owner-derived 触发器）：规则、触发的陈述、触发的实体
        // 都按所属 fact 的库判；CHECK 要求 statement/entity 恰好一个非空
        assertRejected("implied.rule→foreign",
                "INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) "
                        + "VALUES (?, ?, ?)",
                f.factA(), f.iruleB(), f.factA());
        assertRejected("implied.statement→foreign",
                "INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) "
                        + "VALUES (?, ?, ?)",
                f.factA(), f.iruleA(), f.factB());
        assertRejected("implied.entity→foreign",
                "INSERT INTO implied_fact_sources (fact_id, rule_id, entity_id) "
                        + "VALUES (?, ?, ?)",
                f.factA(), f.iruleA(), f.entB());
        // UPDATE 路径：先落合法行，再把 rule 改成别库的——UPDATE OF 盯得住
        jdbc.update("INSERT INTO implied_fact_sources (fact_id, rule_id, entity_id) "
                + "VALUES (?, ?, ?)", f.factB(), f.iruleB(), f.entB());
        assertRejected("implied.rule 改成别库也必须被拒",
                "UPDATE implied_fact_sources SET rule_id = ? WHERE fact_id = ?",
                f.iruleA(), f.factB());

        // 勘误账：run 盯的文档、action 上的六个引用列全是复合外键
        assertRejected("erratarun.document→foreign",
                "INSERT INTO errata_runs (id, kb_id, document_id) VALUES (?, ?, ?)",
                Uuids.nowV7(), f.a(), f.docB());
        for (Object[] row : new Object[][]{
                {"fact_id", f.factB()}, {"statement_id", f.factB()}, {"predicate_id", f.relB()},
                {"new_fact_id", f.factB()}}) {
            assertRejected("errata col",
                    "INSERT INTO errata_actions (id, kb_id, run_id, document_id, action, status, "
                            + row[0] + ") VALUES (?, ?, ?, ?, 'keep', 'held', ?)",
                    Uuids.nowV7(), f.a(), f.erunA(), f.docA(), row[1]);
        }
        // run/document 两列走 UPDATE 路径验：把已有 action 挪到别库的 run 或文档上
        assertRejected("errata.run",
                "UPDATE errata_actions SET run_id = ? WHERE id = ?", f.erunB(), f.eactA());
        assertRejected("errata.document",
                "UPDATE errata_actions SET document_id = ? WHERE id = ?", f.docB(), f.eactA());

        // 规则定义史与凭它推出的派生（#913 的表也在面内）
        assertRejected("arversion.rule→foreign",
                "INSERT INTO attribute_rule_versions (id, kb_id, rule_id, seq, definition, "
                        + "superseded_at) VALUES (?, ?, ?, 9, '{}'::jsonb, now())",
                Uuids.nowV7(), f.a(), f.aruleB());
        assertRejected("derived.attribute_rule_version→foreign",
                "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_value, "
                        + "attribute_rule_id, attribute_rule_version_id) "
                        + "VALUES (?, ?, ?, ?, '\"v\"'::jsonb, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.aruleA(), f.arvB());

        // 0071/0080 出生就带复合键、却一直不在体检里的两条边
        assertRejected("arule.join_predicate→foreign",
                "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                        + "conclude_predicate_id, join_predicate_id) "
                        + "VALUES (?, ?, 'ar-r', ?, 'relation', ?, ?)",
                Uuids.nowV7(), f.a(), f.clsA(), f.relA(), f.relB());
        assertRejected("namevector.fact→foreign",
                "INSERT INTO name_vectors (fact_id, kb_id, entity_id, embedding) "
                        + "VALUES (?, ?, ?, '[0]'::vector)",
                f.factB(), f.a(), f.entA());

        // —— kb 过户：把已被引用的行挪到别的库，等于把指着它的行一次全变坏行
        for (Object[] row : new Object[][]{
                {"entities", f.entA()}, {"entity_types", f.clsA()}, {"relation_types", f.relA()},
                {"derived_facts", f.derA()}, {"rules", f.ruleA()},
                {"attribute_rules", f.aruleA()}, {"implication_rules", f.iruleA()},
                {"errata_runs", f.erunA()}, {"errata_actions", f.eactA()},
                {"attribute_rule_versions", f.arvA()}}) {
            assertRejected(row[0] + " 过户",
                    "UPDATE " + row[0] + " SET kb_id = ? WHERE id = ?", f.b(), row[1]);
        }
        // 读数缓存的键不是 id——按 (kb_id, reading, phrase) 挪库同样不许
        assertRejected("phrase_readings.kb_id 过户",
                "UPDATE phrase_readings SET kb_id = ? WHERE kb_id = ? AND phrase = 'r'",
                f.b(), f.a());

        // —— 防误伤：同库的合法写入照常
        jdbc.update("INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, seq) "
                + "VALUES (?, ?, 0)", f.derA(), f.factA());
        jdbc.update("INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, entity_id) "
                + "VALUES (?, ?, ?)", f.factA(), f.relA(), f.entA());
        // 新家族同库写入也要照常：出处、读数、勘误、定义史、名字向量
        jdbc.update("INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) "
                + "VALUES (?, ?, ?)", f.factA(), f.iruleA(), f.factA());
        jdbc.update("INSERT INTO phrase_readings (kb_id, reading, phrase, entity_id) "
                + "VALUES (?, 'nationality', 'y', ?)", f.a(), f.entA());
        jdbc.update("INSERT INTO errata_actions (id, kb_id, run_id, document_id, fact_id, "
                        + "statement_id, predicate_id, new_fact_id, action, status) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'revise', 'held')",
                Uuids.nowV7(), f.a(), f.erunA(), f.docA(), f.factA(), f.factA(), f.relA(), f.factA());
        jdbc.update("INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_value, "
                + "attribute_rule_id, attribute_rule_version_id) "
                + "VALUES (?, ?, ?, ?, '\"ok\"'::jsonb, ?, ?)",
                Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.aruleA(), f.arvA());
        jdbc.update("INSERT INTO name_vectors (fact_id, kb_id, entity_id, embedding) "
                + "VALUES (?, ?, ?, '[0]'::vector)", f.factA(), f.a(), f.entA());
    }

    /** 存量坏行：体检报出正确的边，对应的页读取同样拒。 */
    @Test
    void malformed_rows_fail_every_exported_edge_closed() throws SQLException {
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            update(tx, "SET LOCAL session_replication_role = 'replica'");

            // 每条边造一行坏行——全部落在库 A 身上
            update(tx, "INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, seq) "
                    + "VALUES (?, ?, 0)", f.derA(), f.factB());
            update(tx, "INSERT INTO fact_derivations (derived_fact_id, premise_derived_id, seq) "
                    + "VALUES (?, ?, 1)", f.derA(), f.derB());
            update(tx, "INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, value) "
                    + "VALUES (?, ?, '\"lit\"'::jsonb)", f.factA(), f.relB());
            update(tx, "INSERT INTO fact_qualifiers (fact_id, qualifier_type_id, entity_id) "
                    + "VALUES (?, ?, ?)", f.factA(), f.relA(), f.entB());
            update(tx, "UPDATE facts SET supersedes = ? WHERE id = ?", f.factB(), f.factA());
            update(tx, "INSERT INTO typed_fact_sources (fact_id, statement_id) VALUES (?, ?)",
                    f.factA(), f.factB());
            update(tx, "UPDATE facts SET from_statement_id = ? WHERE id = ?", f.factB(), f.factA());
            update(tx, "INSERT INTO statement_qualifiers (fact_id, role, entity_id) "
                    + "VALUES (?, 'as', ?)", f.factA(), f.entB());
            // 时间提及两种坏法：归属在 A 却指着 B 的段落（体检扫得见）；归属在 B 却挂在 A 的事实上
            update(tx, "INSERT INTO time_mentions (id, kb_id, fact_id, chunk_id, text, char_start) "
                    + "VALUES (?, ?, ?, ?, '去年', 0)", Uuids.nowV7(), f.a(), f.factA(), f.chunkB());
            update(tx, "INSERT INTO time_mentions (id, kb_id, fact_id, chunk_id, text, char_start) "
                    + "VALUES (?, ?, ?, ?, '前年', 2)", Uuids.nowV7(), f.b(), f.factA(), f.chunkA());
            update(tx, "INSERT INTO type_bindings (id, kb_id, kind_word, status, type_id) "
                    + "VALUES (?, ?, 'corp', 'bound', ?)", Uuids.nowV7(), f.a(), f.clsB());
            update(tx, "INSERT INTO phrase_bindings (id, kb_id, phrase, relation_type_id, direction, "
                    + "status) VALUES (?, ?, 'runs', ?, 'forward', 'bound')",
                    Uuids.nowV7(), f.a(), f.relB());
            update(tx, "UPDATE derived_facts SET rule_id = ? WHERE id = ?", f.ruleB(), f.derA());
            update(tx, "UPDATE entities SET type_id = ? WHERE id = ?", f.clsB(), f.entA());
            update(tx, "INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)",
                    f.clsA(), f.clsB());
            update(tx, "INSERT INTO relation_type_domains (relation_type_id, entity_type_id) "
                    + "VALUES (?, ?)", f.relA(), f.clsB());

            // —— 体检覆盖 0070/0091 保护的全部结构边：剩下每条边也各造一行坏行 ——
            update(tx, "INSERT INTO fact_evidence (fact_id, chunk_id) VALUES (?, ?)",
                    f.factA(), f.chunkB());
            update(tx, "INSERT INTO fact_evidence (fact_id, chunk_id, document_id) "
                    + "VALUES (?, ?, ?)", f.factA(), f.chunkA(), f.docB());
            update(tx, "INSERT INTO chunks (id, kb_id, document_id, seq, text) "
                    + "VALUES (?, ?, ?, 9, 'x')", Uuids.nowV7(), f.a(), f.docB());
            update(tx, "UPDATE facts SET subject_id = ?, object_id = ?, predicate_id = ? "
                    + "WHERE id = ?", f.entB(), f.entB(), f.relB(), f.factA());
            update(tx, "UPDATE derived_facts SET subject_id = ?, object_id = ?, predicate_id = ? "
                    + "WHERE id = ?", f.entB(), f.entB(), f.relB(), f.derA());
            update(tx, "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_id, "
                    + "attribute_rule_id) VALUES (?, ?, ?, ?, ?, ?)",
                    Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.entA(), f.aruleB());
            // 类互斥的两个引用列是两条结构边、共用一个报错 label——a_id 别库一行、b_id 别库一行
            update(tx, "INSERT INTO entity_type_disjoint (kb_id, a_id, b_id) VALUES (?, ?, ?)",
                    f.a(), f.clsB(), f.clsA());
            update(tx, "INSERT INTO entity_type_disjoint (kb_id, a_id, b_id) VALUES (?, ?, ?)",
                    f.a(), f.clsA(), f.clsB());
            update(tx, "INSERT INTO relation_type_ranges (relation_type_id, entity_type_id) "
                    + "VALUES (?, ?)", f.relA(), f.clsB());
            update(tx, "INSERT INTO relation_type_qualifiers (relation_type_id, qualifier_type_id) "
                    + "VALUES (?, ?)", f.relA(), f.relB());
            update(tx, "UPDATE relation_types SET inverse_of = ?, sub_property_of = ? WHERE id = ?",
                    f.relB(), f.relB(), f.relA());
            update(tx, "UPDATE rules SET predicate_id = ? WHERE id = ?", f.relB(), f.ruleA());
            update(tx, "UPDATE attribute_rules SET subject_type_id = ?, conclude_predicate_id = ? "
                    + "WHERE id = ?", f.clsB(), f.relB(), f.aruleA());
            update(tx, "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                    + "conclude_type_id) VALUES (?, ?, 'ar-t', ?, 'typing', ?)",
                    Uuids.nowV7(), f.a(), f.clsA(), f.clsB());
            update(tx, "INSERT INTO attribute_rule_conditions (id, rule_id, seq, predicate_id, op) "
                    + "VALUES (?, ?, 0, ?, 'present')", Uuids.nowV7(), f.aruleA(), f.relB());
            update(tx, "INSERT INTO time_mentions (id, kb_id, fact_id, chunk_id, text, char_start) "
                    + "VALUES (?, ?, ?, ?, '明年', 4)", Uuids.nowV7(), f.a(), f.factB(), f.chunkA());
            update(tx, "INSERT INTO phrase_bindings (id, kb_id, phrase, subject_type_id, status) "
                    + "VALUES (?, ?, 'runs-sub', ?, 'none')", Uuids.nowV7(), f.a(), f.clsB());
            update(tx, "INSERT INTO phrase_bindings (id, kb_id, phrase, object_type_id, status) "
                    + "VALUES (?, ?, 'runs-obj', ?, 'none')", Uuids.nowV7(), f.a(), f.clsB());

            // —— #901 新家族的坏行：0091 保护的 15 条新边 + 两条早就带复合键的边 ——
            update(tx, "INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                    + "conclude_property_id, status, subject_type_id) "
                    + "VALUES (?, ?, 'phrase', 'p', ?, 'approved', ?)",
                    Uuids.nowV7(), f.a(), f.relA(), f.clsB());
            update(tx, "INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                    + "conclude_property_id, status, object_type_id) "
                    + "VALUES (?, ?, 'phrase', 'p', ?, 'approved', ?)",
                    Uuids.nowV7(), f.a(), f.relA(), f.clsB());
            update(tx, "INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                    + "conclude_property_id, status) VALUES (?, ?, 'phrase', 'p', ?, 'approved')",
                    Uuids.nowV7(), f.a(), f.relB());
            update(tx, "INSERT INTO phrase_readings (kb_id, reading, phrase, entity_id) "
                    + "VALUES (?, 'nationality', 'bad', ?)", f.a(), f.entB());
            // 隐含事实的出处三种坏法（CHECK：statement/entity 恰好一个；主键是
            // (fact_id, rule_id)，再落一条本库事实把三行错开）
            update(tx, "INSERT INTO facts (id, kb_id, subject_id, object_id, confidence) "
                    + "VALUES (?, ?, ?, ?, 0.9)", Uuids.nowV7(), f.a(), f.entA(), f.entA());
            UUID factA2;
            try (var ps = tx.prepareStatement(
                    "SELECT id FROM facts WHERE kb_id = ? AND id <> ? ORDER BY id DESC LIMIT 1")) {
                ps.setObject(1, f.a());
                ps.setObject(2, f.factA());
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    factA2 = (UUID) rs.getObject(1);
                }
            }
            UUID iruleA2 = Uuids.nowV7();
            update(tx, "INSERT INTO implication_rules (id, kb_id, trigger, phrase, "
                    + "conclude_property_id, status) VALUES (?, ?, 'phrase', 'q', ?, 'approved')",
                    iruleA2, f.a(), f.relA());
            update(tx, "INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) "
                    + "VALUES (?, ?, ?)", f.factA(), f.iruleB(), f.factA());
            update(tx, "INSERT INTO implied_fact_sources (fact_id, rule_id, statement_id) "
                    + "VALUES (?, ?, ?)", factA2, f.iruleA(), f.factB());
            update(tx, "INSERT INTO implied_fact_sources (fact_id, rule_id, entity_id) "
                    + "VALUES (?, ?, ?)", factA2, iruleA2, f.entB());
            // 定义史挂到别库规则；派生记下别库的版本
            update(tx, "INSERT INTO attribute_rule_versions (id, kb_id, rule_id, seq, definition, "
                    + "superseded_at) VALUES (?, ?, ?, 9, '{}'::jsonb, now())",
                    Uuids.nowV7(), f.a(), f.aruleB());
            update(tx, "INSERT INTO derived_facts (id, kb_id, subject_id, predicate_id, object_value, "
                    + "attribute_rule_id, attribute_rule_version_id) "
                    + "VALUES (?, ?, ?, ?, '\"bad\"'::jsonb, ?, ?)",
                    Uuids.nowV7(), f.a(), f.entA(), f.relA(), f.aruleA(), f.arvB());
            // 勘误账：run 盯别库文档一行；action 上的 run/document 各坏一行，其余四列塞进同一行
            update(tx, "INSERT INTO errata_runs (id, kb_id, document_id) VALUES (?, ?, ?)",
                    Uuids.nowV7(), f.a(), f.docB());
            update(tx, "INSERT INTO errata_actions (id, kb_id, run_id, document_id, action, status) "
                    + "VALUES (?, ?, ?, ?, 'keep', 'held')", Uuids.nowV7(), f.a(), f.erunB(), f.docA());
            update(tx, "INSERT INTO errata_actions (id, kb_id, run_id, document_id, action, status) "
                    + "VALUES (?, ?, ?, ?, 'keep', 'held')", Uuids.nowV7(), f.a(), f.erunA(), f.docB());
            update(tx, "INSERT INTO errata_actions (id, kb_id, run_id, document_id, fact_id, "
                    + "statement_id, predicate_id, new_fact_id, action, status) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'revise', 'held')",
                    Uuids.nowV7(), f.a(), f.erunA(), f.docA(), f.factB(), f.factB(), f.relB(),
                    f.factB());
            // relation 形状的 join 谓词指别库；名字向量两头各坏一行
            update(tx, "INSERT INTO attribute_rules (id, kb_id, name, subject_type_id, conclusion, "
                    + "conclude_predicate_id, join_predicate_id) "
                    + "VALUES (?, ?, 'ar-j', ?, 'relation', ?, ?)",
                    Uuids.nowV7(), f.a(), f.clsA(), f.relA(), f.relB());
            update(tx, "INSERT INTO name_vectors (fact_id, kb_id, entity_id, embedding) "
                    + "VALUES (?, ?, ?, '[0]'::vector)", f.factB(), f.a(), f.entA());
            update(tx, "INSERT INTO name_vectors (fact_id, kb_id, entity_id, embedding) "
                    + "VALUES (?, ?, ?, '[0]'::vector)", f.factA(), f.a(), f.entB());
            tx.commit();
        }

        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.provenanceIntegrity(tx, f.a()));
            String msg = err.getMessage() + " / " + err.detail().orElse("");
            // 体检覆盖 0070/0091 保护的全部结构边——埋下去的每一类坏行都要被点名
            for (String edge : new String[]{
                    "evidence.chunk", "evidence.document", "chunk.document",
                    "derivation.premise_fact", "derivation.premise_derived",
                    "qualifier.type", "qualifier.entity", "fact.subject", "fact.object",
                    "fact.predicate", "fact.supersedes", "fact.from_statement",
                    "derived.subject", "derived.object", "derived.predicate", "derived.rule",
                    "derived.attribute_rule", "derived.attribute_rule_version", "entity.type",
                    "class.parent", "class.disjoint", "relation.domain", "relation.range",
                    "relation.qualifier", "relation.inverse", "relation.sub_property",
                    "rule.predicate", "arule.subject_type", "arule.conclude_type",
                    "arule.conclude_predicate", "arule.join_predicate", "arversion.rule",
                    "condition.predicate", "factsource.statement", "squalifier.entity",
                    "timemention.fact", "timemention.chunk", "binding.type",
                    "pbinding.subject_type", "pbinding.object_type", "pbinding.relation",
                    "irule.subject_type", "irule.object_type", "irule.conclude_property",
                    "reading.entity", "implied.rule", "implied.statement", "implied.entity",
                    "erratarun.document", "errata.run", "errata.document", "errata.fact",
                    "errata.statement", "errata.predicate", "errata.new_fact",
                    "namevector.fact", "namevector.entity"}) {
                assertTrue(msg.contains(edge), "体检该报 " + edge + ": " + msg);
            }

            // 逐页校验：坏行落在哪页，哪页就拒——不能漏出伪造 IRI
            assertThrows(AppException.class, () -> ExportStore.factsPage(tx, f.a(), null));
            assertThrows(AppException.class, () -> ExportStore.derivedPage(tx, f.a(), null));
            assertThrows(AppException.class, () -> ExportStore.entitiesPage(tx, f.a(), null));
            assertThrows(AppException.class, () -> ExportStore.classes(tx, f.a()));
            assertThrows(AppException.class, () -> ExportStore.relations(tx, f.a()));
            tx.rollback();
        }

    }

    /** 留下的行才参与校验：被引的行在两次读之间消失，判违规的是<b>随页原子选出的归属</b>，
     *  不是事后另一个时刻的 JOIN——悬空引用照样拒。 */
    @Test
    void retained_row_validation_survives_dangling_and_late_state() throws SQLException {
        // 合法证据先行
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, document_id) "
                + "VALUES (?, ?, ?)", f.factA(), f.chunkA(), f.docA());
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            ExportStore.factsPage(tx, f.a(), null);
            tx.rollback();
        }

        // 绕过触发器把文档删掉：evidence 行还指着它——悬空不是「不存在所以跳过」
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            update(tx, "SET LOCAL session_replication_role = 'replica'");
            update(tx, "DELETE FROM documents WHERE id = ?", f.docA());
            tx.commit();
        }
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.factsPage(tx, f.a(), null));
            String msg = err.getMessage() + " / " + err.detail().orElse("");
            assertTrue(msg.contains("evidence.document"), "该报 evidence.document: " + msg);
            tx.rollback();
        }

        // 同理：前提行被删，derived 的前提数组仍留着死指针——逐页校验要拦
        jdbc.update("INSERT INTO fact_derivations (derived_fact_id, premise_fact_id, seq) "
                + "VALUES (?, ?, 0)", f.derA(), f.factA());
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            update(tx, "SET LOCAL session_replication_role = 'replica'");
            update(tx, "DELETE FROM facts WHERE id = ?", f.factA());
            tx.commit();
        }
        try (Connection tx = dataSource.getConnection()) {
            tx.setAutoCommit(false);
            AppException err = assertThrows(AppException.class,
                    () -> ExportStore.derivedPage(tx, f.a(), null));
            String msg = err.getMessage() + " / " + err.detail().orElse("");
            assertTrue(msg.contains("derivation.premise_fact"), "该报 derivation.premise_fact: " + msg);
            tx.rollback();
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    /** 该 INSERT/UPDATE 必须被数据库拒（触发器/复合外键当场挡）。 */
    private void assertRejected(String what, String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            throw new AssertionError(what + "→foreign 必须被拒（语句竟通过了）: " + sql);
        } catch (org.springframework.dao.DataAccessException expected) {
            // 拒了才算对：0070/0091 的触发器与复合外键在这里显形
        }
    }
}
