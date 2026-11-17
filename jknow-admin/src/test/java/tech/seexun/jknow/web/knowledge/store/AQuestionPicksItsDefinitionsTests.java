package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.entity.ConceptMapping;
import tech.seexun.jknow.store.entity.MappingText;

/**
 * 移植自 tests/store/a_question_picks_its_definitions.rs（1 例，M1 挂账 M5 向量路、
 * M3 任务 10 落 relevant 向量路时核销）——口径检索的存储面（#574）：该嵌的才嵌
 * （没向量的/模型换了的/文本改了的），向量一路按距离排（维度对不上跳过、换了模型
 * 还没重嵌的不参与），按 id 取回保持给定顺序（顺序是检索排出来的）。
 *
 * <p>与 Rust 的数据造法等价点：propose+confirm 走的写端点属 M4/M5（mappings 决定面），
 * 这里按 propose 会写下的形状直插行（scope 列名 source='wide'、status 先 proposed 再
 * confirmed——store 面测试自建自拆的同位造法）。set_embeddings 在 Java 侧是
 * updateMappingEmbedding（向量/模型/原文三者同一次写入，Rust set_embeddings 同语义）。
 */
class AQuestionPicksItsDefinitionsTests extends HeartStoreTestBase {

    @Autowired
    MappingsService mappings;

    private UUID base() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'pick-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'pick-test')",
                ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'pick-test')",
                kb, ws);
        return kb;
    }

    private UUID definition(UUID kb, String name, String summary) {
        UUID ent = Uuids.nowV7();
        jdbc.update("INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, ?)",
                ent, kb, name);
        UUID id = Uuids.nowV7();
        jdbc.update("INSERT INTO concept_mappings (id, kb_id, concept_id, source, table_name,"
                + " expr, summary) VALUES (?, ?, ?, 'wide', 'dw.t', 'sum(x)', ?)", id, kb, ent,
                summary);
        jdbc.update("UPDATE concept_mappings SET status = 'confirmed' WHERE id = ?", id);
        return id;
    }

    /** m::set_embeddings 的 Java 侧形状：一批 (行文本, 向量) 连同模型/原文写回。 */
    private void setEmbeddings(String model, List<Map.Entry<MappingText, float[]>> items) {
        for (Map.Entry<MappingText, float[]> item : items) {
            jdbc.update("UPDATE concept_mappings SET embedding = ?::vector, embedded_model = ?,"
                            + " embedded_text = ? WHERE id = ?",
                    vectorLiteral(item.getValue()), model, item.getKey().embedText(),
                    item.getKey().id());
        }
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 12).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private List<UUID> vectorSearch(UUID kb, String model, float[] embedding, long limit) {
        return jdbc.queryForList(
                "SELECT id FROM concept_mappings"
                        + " WHERE kb_id = ? AND status = 'confirmed' AND embedding IS NOT NULL"
                        + " AND embedded_model = ?"
                        + " AND vector_dims(embedding) = vector_dims(?::vector)"
                        + " ORDER BY embedding <=> ?::vector LIMIT ?",
                UUID.class, kb, model, vectorLiteral(embedding), vectorLiteral(embedding), limit);
    }

    @Test
    void onlyWhatChangedGetsEmbeddedAndNeighboursComeBackInOrder() {
        UUID kb = base();
        try {
            UUID gmv = definition(kb, "GMV", "paid, non-test, yuan");
            UUID net = definition(kb, "Net sales", "GMV minus refunds");
            UUID freight = definition(kb, "Freight", "freight collected");

            // 一开始三条都要嵌
            List<MappingText> stale = mappings.needingEmbedding(kb, "bge-m3", 100);
            assertEquals(3, stale.size());
            MappingText textOf = null;
            for (MappingText t : stale) {
                if (t.id().equals(gmv)) {
                    textOf = t;
                }
            }
            assertEquals("GMV: paid, non-test, yuan", textOf.embedText());

            // 嵌进去（三维就够测排序）
            setEmbeddings("bge-m3", List.of(
                    new AbstractMap.SimpleEntry<>(textOf, new float[] {1f, 0f, 0f}),
                    new AbstractMap.SimpleEntry<>(find(stale, net), new float[] {0.9f, 0.1f, 0f}),
                    new AbstractMap.SimpleEntry<>(find(stale, freight),
                            new float[] {0f, 0f, 1f})));
            assertTrue(mappings.needingEmbedding(kb, "bge-m3", 100).isEmpty(),
                    "嵌过且没变的不该再嵌");

            // 换模型：全部要重嵌；改文本：只有那一条要重嵌
            assertEquals(3, mappings.needingEmbedding(kb, "other-model", 100).size());
            // 补嵌有上限：一问只嵌这么多，剩下的下一问接着补
            assertEquals(2, mappings.needingEmbedding(kb, "other-model", 2).size());
            jdbc.update("UPDATE concept_mappings SET summary = 'freight, paid orders only'"
                    + " WHERE id = ?", freight);
            List<MappingText> again = mappings.needingEmbedding(kb, "bge-m3", 100);
            assertEquals(List.of(freight), again.stream().map(MappingText::id).toList(),
                    "改了说明的那条要重嵌");

            // 向量一路：离 (1,0,0) 最近的是 GMV，其次 Net sales；Freight 最远
            assertEquals(List.of(gmv, net, freight),
                    vectorSearch(kb, "bge-m3", new float[] {1f, 0f, 0f}, 3));
            // 维度对不上的查询一条都不回（换过模型还没重嵌的那种状态）
            assertTrue(vectorSearch(kb, "bge-m3", new float[] {1f, 0f}, 3).isEmpty());
            // 换了模型、还没重嵌的行不参与：维度一样也不比
            assertTrue(vectorSearch(kb, "other-model", new float[] {1f, 0f, 0f}, 3).isEmpty());

            // 按 id 取回保持给定顺序
            List<ConceptMapping> got = mappings.byIds(kb, List.of(freight, gmv));
            assertEquals(List.of(freight, gmv), got.stream().map(ConceptMapping::id).toList());
            assertEquals("GMV", got.get(1).conceptName());
        } finally {
            // 只删知识库，不删 org——用户是软删除的，测试也不该造一个产品里不存在的动作
            jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", kb);
        }
    }

    private static MappingText find(List<MappingText> stale, UUID id) {
        for (MappingText t : stale) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        throw new AssertionError("no mapping text for " + id);
    }
}
