package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.extraction.ScriptedModelServer;
import tech.seexun.jknow.web.knowledge.jobs.ResolveTypesJob;
import tech.seexun.jknow.store.SettingsService;

/**
 * 类型消解 job 的脚本化全链（任务书「resolve 跨轴分档」——type_resolution.rs:434-639
 * 的判定面）：候选内高置信且在粗类子树里→自动 retype；换了分类轴→留给人不动；
 * "null"/"none" 当 null；候选之外的 key 不认；看过打 type_resolved_at 防同批回流；
 * 审计 ontology.types_resolved{via:job}。直调 {@link ResolveTypesJob#resolveTypesJob}
 * （不经队列——共享栈 Rust worker 会消费同名 kind）。
 */
class ResolveTypesAgentTests extends StoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ResolveTypesJob resolve;
    @Autowired
    SettingsService settings;

    private static final class Fx {
        final UUID org;
        final UUID kb;
        final UUID product;
        final UUID productLine;
        final UUID softwareApp;
        final UUID media;
        final UUID narrowing;
        final UUID crossing;
        final ScriptedModelServer model;

        Fx(UUID org, UUID kb, UUID product, UUID productLine, UUID softwareApp, UUID media,
           UUID narrowing, UUID crossing, ScriptedModelServer model) {
            this.org = org;
            this.kb = kb;
            this.product = product;
            this.productLine = productLine;
            this.softwareApp = softwareApp;
            this.media = media;
            this.narrowing = narrowing;
            this.crossing = crossing;
            this.model = model;
        }
    }

    /**
     * 一座开着 auto_type_resolution 的库：product（粗类）下挂 product_line；
     * software_application 在另一条轴（media 下）；两个实体 specific_type 给了说法
     * （入选 selectTypeCandidates）。嵌入走脚本端点（文本派生四维）——preview 开头的
     * refresh_scoped 会把四个类嵌上，查询侧同一端点，于是四个类**全部**进候选清单
     *（检索上限 40 > 4，与距离无关），断言只认 key 不认序。实体的 profile_embedding
     * 留空——近邻投票一路为空，不干扰分档断言。
     */
    private Fx seed(List<String> replies) throws java.io.IOException {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID product = Uuids.nowV7();
        UUID productLine = Uuids.nowV7();
        UUID softwareApp = Uuids.nowV7();
        UUID media = Uuids.nowV7();
        UUID narrowing = Uuids.nowV7();
        UUID crossing = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations(id,name) VALUES (?,'resolve-types-test')", org);
        jdbc.update("INSERT INTO workspaces(id,org_id,name) VALUES (?,?,'resolve-types-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases(id,workspace_id,name,auto_type_resolution) "
                + "VALUES (?,?,'resolve-types-test', TRUE)", kb, ws);
        jdbc.update("""
                INSERT INTO entity_types(id,kb_id,key,label,description) VALUES
                    (?,?,'product','Product','a coarse product class'),
                    (?,?,'product_line','Product Line','a finer product class'),
                    (?,?,'software_application','Software Application','an app'),
                    (?,?,'media','Media','a creative work')
                """, product, kb, productLine, kb, softwareApp, kb, media, kb);
        // product 的子树：product ⊃ product_line（software_application 在另一条轴上）
        jdbc.update("INSERT INTO entity_type_parents(child_id,parent_id,is_primary) "
                + "VALUES (?, ?, TRUE), (?, ?, TRUE)", productLine, product, softwareApp, media);
        jdbc.update("""
                INSERT INTO entities(id,kb_id,canonical_name,type_id,specific_type,type_source) VALUES
                    (?,?,'Widget A',?, 'product line', 'extracted'),
                    (?,?,'DeepBlue Vector',?, 'vector database software', 'extracted')
                """, narrowing, kb, product, crossing, kb, product);
        // 裁决的 id 是块序（fact_count 降序）——给 Widget A 一条活事实把它钉在 s0
        jdbc.update("INSERT INTO facts(id,kb_id,subject_id,object_id,layer,phrase) "
                + "VALUES (?, ?, ?, ?, 'open', 'similar to')", Uuids.nowV7(), kb, narrowing,
                crossing);
        ScriptedModelServer model = ScriptedModelServer.start(replies);
        // 嵌入模型也要配：候选检索（nearest_for_each）没配嵌入时整路降级成空清单，
        // 两个实体全被过滤，裁决根本不会发生
        settings.upsert(ws, model.endpoint(), null, "scripted", model.endpoint(), null,
                "scripted-embed", null);
        return new Fx(org, kb, product, productLine, softwareApp, media, narrowing, crossing,
                model);
    }

    private void cleanup(Fx f) {
        if (f.model != null) {
            f.model.stop();
        }
        jdbc.update("DELETE FROM jobs WHERE payload->>'kb_id' = ?", f.kb.toString());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org);
    }

    /**
     * 跨轴分档：narrowing 案选了粗类子树里的 product_line、置信 0.9——自动改；
     * crossing 案选了另一条轴上的 software_application、同样 0.9——模型自报的置信度
     * 不是分档依据，跨了轴就留给人（类型没动）；第三个案答 "none"——保持不动。
     */
    @Test
    void narrowingAutoAppliesAndCrossingTheAxisIsLeftForPeople() throws Exception {
        // 裁决按块里的 id 对回——两个实体按 fact_count 并列时按创建序，narrowing 在前
        String verdicts = "{\"verdicts\":["
                + "{\"id\":0,\"name\":\"Widget A\",\"choice\":\"product_line\",\"confidence\":0.9,\"reason\":\"plainly a product line\"},"
                + "{\"id\":1,\"name\":\"DeepBlue Vector\",\"choice\":\"software_application\",\"confidence\":0.9,\"reason\":\"it is an app, not a product\"},"
                + "{\"id\":2,\"name\":\"Ghost\",\"choice\":\"none\",\"confidence\":0.1,\"reason\":\"nearest was product_line, but nothing fits\"}"
                + "]}";
        Fx f = seed(List.of(verdicts));
        try {
            resolve.resolveTypesJob(f.kb);

            // narrowing：自动改到 product_line，账本一行，引擎身份（type_source 不是 human）
            assertEquals(f.productLine, jdbc.queryForObject(
                    "SELECT type_id FROM entities WHERE id = ?", UUID.class, f.narrowing));
            assertEquals("inferred", jdbc.queryForObject(
                    "SELECT type_source FROM entities WHERE id = ?", String.class, f.narrowing),
                    "落库不带 actor——引擎身份（retype 的 CASE WHEN actor IS NULL THEN "
                            + "'inferred'），跑过一次消解的实体不该从此被锁成 human");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT count(*) FROM entity_retypes WHERE kb_id = ? AND to_type_id = ?",
                    Long.class, f.kb, f.productLine));
            // crossing：跨轴留给人——类型没动
            assertEquals(f.product, jdbc.queryForObject(
                    "SELECT type_id FROM entities WHERE id = ?", UUID.class, f.crossing));
            // 看过的都打了标（连没给候选的也算看过——防同批回流）
            assertEquals(2, jdbc.queryForObject(
                    "SELECT count(*) FROM entities WHERE kb_id = ? AND type_resolved_at IS NOT NULL",
                    Long.class, f.kb));
            // 审计：ontology.types_resolved via job
            String detail = jdbc.queryForObject(
                    "SELECT detail::text FROM audit_events WHERE kb_id = ? "
                            + "AND action = 'ontology.types_resolved'", String.class, f.kb);
            assertNotNull(detail);
            assertTrue(detail.contains("\"via\"") && detail.contains("\"rounds\""), detail);
            // 模型恰被问一次（一轮 60 席位装下这两个实体；第三个 id 的裁决对不上任何块）
            assertEquals(1, f.model.requests().size());
        } finally {
            cleanup(f);
        }
    }

    /** 候选之外的答案不是「更好的判断」——left_alone 连理由都记成 chose X not among。 */
    @Test
    void aChoiceOutsideTheCandidatesIsRefusedNotBelieved() throws Exception {
        String verdicts = "{\"verdicts\":["
                + "{\"id\":0,\"name\":\"Widget A\",\"choice\":\"organization\",\"confidence\":0.99,\"reason\":\"trust me\"}"
                + "]}";
        Fx f = seed(List.of(verdicts));
        try {
            resolve.resolveTypesJob(f.kb);
            assertEquals(f.product, jdbc.queryForObject(
                    "SELECT type_id FROM entities WHERE id = ?", UUID.class, f.narrowing),
                    "候选之外的 key 不认——什么都没动");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT count(*) FROM entity_retypes WHERE kb_id = ?", Long.class, f.kb));
        } finally {
            cleanup(f);
        }
    }

    /** 开关关着的库什么都不做（连模型都不问）。 */
    @Test
    void aKbWithAutoResolutionOffDoesNothing() throws Exception {
        Fx f = seed(List.of());
        try {
            jdbc.update("UPDATE knowledge_bases SET auto_type_resolution = FALSE WHERE id = ?",
                    f.kb);
            resolve.resolveTypesJob(f.kb);
            assertEquals(0, f.model.requests().size());
            assertNull(jdbc.queryForObject(
                    "SELECT type_resolved_at FROM entities WHERE id = ?", java.time.OffsetDateTime.class,
                    f.narrowing));
        } finally {
            cleanup(f);
        }
    }
}
