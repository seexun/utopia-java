package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.GovernanceService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.NameVectorsService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.ResolutionService.DescendantsMemo;
import tech.seexun.jknow.store.ResolutionService.Neighbour;
import tech.seexun.jknow.store.ReviewService;
import tech.seexun.jknow.store.VectorIndexService;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.ResolutionMapper;
import tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 移植自 tests/store/a_batch_gathers_its_neighbours_in_order.rs——类型消解的近邻那一路
 * （#514），打在真库上。规矩：消解的答案是台账的函数，与候选按什么顺序、什么时机取
 * 回来无关。钉四样：<b>顺序</b>（一批主语的邻居按送进去的顺序回来）；<b>并发上限不改
 * 答案</b>（1 与 8 同一份；两个连接的小池子也跑得完 60 个）；<b>SQL 三道门</b>（自己
 * 不是自己的邻居；没判出类型的不当证据；同一篇文档的要标出来）；<b>后代集合的记忆</b>
 * （一批之内同一个粗类只问一次，没有粗类的不进表；批内本体动了记忆给旧的）。
 *
 * <p>这个测试独占 5 维画像（其它连库测试用 3 维）——起跑先 drop 该维度的索引，收尾
 * 再 drop，与 Rust 原测试同口径。
 */
class ABatchGathersItsNeighboursInOrderTests extends HeartStoreTestBase {

    /** 这个测试独占的维度：其它连库测试用 3 维。 */
    private static final int DIMS = 5;
    private static final int TYPED = 40;

    @Autowired
    ResolutionService resolution;
    @Autowired
    VectorIndexService vectorIndex;
    @Autowired
    ResolutionMapper resolutionMapper;
    @Autowired
    NamesService names;
    @Autowired
    NameVectorsService nameVectors;
    @Autowired
    GovernanceService governanceReads;
    @Autowired
    TemporalService temporal;
    @Autowired
    ReviewService reviews;
    @Autowired
    DocumentsWriteMapper documents;
    @Autowired
    JobsService jobs;
    @Autowired
    ReasoningService reasoning;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    DataSource dataSource;

    private record Fixture(UUID org, UUID kb, UUID thing, UUID person, UUID company,
                           UUID employee, List<UUID> subjects, UUID threeDimsSubject) {
    }

    private Fixture f;
    private long lcgSeed = 0xfeedL;

    /** Rust lcg：u64 wrap 乘加，取高 31 位映射到 [-0.5, 0.5)。 */
    private float lcg() {
        lcgSeed = lcgSeed * 6364136223846793005L + 1442695040888963407L;
        return (lcgSeed >>> 33) / 2147483648f - 0.5f;
    }

    private float[] vector(int dims) {
        float[] v = new float[dims];
        for (int i = 0; i < dims; i++) {
            v[i] = lcg();
        }
        return v;
    }

    @BeforeEach
    void seed() throws Exception {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID thing = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID company = Uuids.nowV7();
        UUID employee = Uuids.nowV7();
        String tag = Uuids.nowV7().toString();

        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'gather-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'gather-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'gather-test')",
                kb, ws);
        for (Object[] te : new Object[][]{{thing, "thing"}, {person, "person"},
                {company, "company"}, {employee, "employee"}}) {
            jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    te[0], kb, te[1], te[1]);
        }
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", person, thing);
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", company, thing);
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", employee, person);

        UUID[] types = {person, company, employee};
        for (int i = 0; i < TYPED; i++) {
            insert(kb, "typed " + i, types[i % 3], vector(DIMS));
        }
        List<UUID> subjects = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            subjects.add(insert(kb, "subject " + i, thing, vector(DIMS)));
        }
        insert(kb, "untyped", null, vector(DIMS));
        insert(kb, "three dims", person, vector(3));
        UUID threeDimsSubject = Uuids.nowV7();
        insertEntity(kb, threeDimsSubject, "three dims subject", thing, vector(3));

        // 主语 0 和 typed[0] 在同一篇文档里：一条事实，证据落在那篇文档的一块上
        UUID sameDocNeighbour = null; // typed 0 的 id 需要回读
        sameDocNeighbour = jdbc.queryForObject(
                "SELECT id FROM entities WHERE kb_id = ? AND canonical_name = 'typed 0'", UUID.class, kb);
        UUID doc = Uuids.nowV7();
        UUID chunk = Uuids.nowV7();
        UUID fact = Uuids.nowV7();
        jdbc.update("INSERT INTO documents (id, kb_id, filename, sha256, status)"
                        + " VALUES (?, ?, 'shared.md', ?, 'ready')", doc, kb, "sha-" + tag);
        jdbc.update("INSERT INTO chunks (id, kb_id, document_id, seq, text)"
                + " VALUES (?, ?, ?, 0, 'a sentence')", chunk, kb, doc);
        jdbc.update("INSERT INTO facts (id, kb_id, subject_id, object_id) VALUES (?, ?, ?, ?)",
                fact, kb, subjects.get(0), sameDocNeighbour);
        jdbc.update("INSERT INTO fact_evidence (fact_id, chunk_id, quote, document_id)"
                + " VALUES (?, ?, 'a sentence', ?)", fact, chunk, doc);

        f = new Fixture(org, kb, thing, person, company, employee, subjects, threeDimsSubject);
    }

    private UUID insert(UUID kb, String name, UUID typeId, float[] v) {
        UUID id = Uuids.nowV7();
        insertEntity(kb, id, name, typeId, v);
        return id;
    }

    private void insertEntity(UUID kb, UUID id, String name, UUID typeId, float[] v) {
        jdbc.update("""
                INSERT INTO entities (id, kb_id, type_id, canonical_name, profile_embedding, profile_n)
                 VALUES (?, ?, ?, ?, ?::vector, 1)
                """, id, kb, typeId, name, VectorText.literal(v));
    }

    @AfterEach
    void teardown() throws Exception {
        vectorIndex.drop(VectorIndexService.Target.ENTITY_PROFILES, DIMS);
        jdbc.update("DELETE FROM knowledge_bases WHERE id = ?", f.kb());
        jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
    }

    private static List<String> names(List<Neighbour> list) {
        return list.stream().map(Neighbour::name).toList();
    }

    @Test
    void theNeighboursComeBackInOrderAndTheGatesHold() throws Exception {
        vectorIndex.drop(VectorIndexService.Target.ENTITY_PROFILES, DIMS);

        // ---- 一、逐个问，作为基准
        List<List<Neighbour>> serial = new ArrayList<>();
        for (UUID s : f.subjects()) {
            serial.add(resolution.nearestTypedEntities(f.kb(), s, 10));
        }
        for (int i = 0; i < serial.size(); i++) {
            List<Neighbour> list = serial.get(i);
            assertEquals(10, list.size(), "主语 " + i + " 该有十个邻居");
            String me = "subject " + i;
            assertTrue(list.stream().noneMatch(n -> n.name().equals(me)),
                    "自己不是自己的邻居（别的主语可以是：它们挂着 thing，是已定类的）");
            assertTrue(list.stream().noneMatch(n -> n.name().equals("untyped")),
                    "没判出类型的不当证据");
            assertTrue(list.stream().noneMatch(n -> n.name().equals("three dims")),
                    "另一维度的画像不是候选");
        }
        // 主语 0 的邻居里，typed 0 与它同一篇文档，别的都不是
        List<String> flagged = serial.get(0).stream()
                .filter(Neighbour::sameDocument)
                .map(Neighbour::name)
                .toList();
        boolean hasTyped0 = serial.get(0).stream().anyMatch(n -> n.name().equals("typed 0"));
        if (hasTyped0) {
            assertEquals(List.of("typed 0"), flagged, "同一篇文档的要标出来，且只标它");
        } else {
            assertTrue(flagged.isEmpty(), "不在邻居里就没什么可标");
        }
        // 自己的向量是 3 维的主语：没有 3 维的已定类邻居可比……除了那一条 3 维的
        List<Neighbour> odd = resolution.nearestTypedEntities(f.kb(), f.threeDimsSubject(), 10);
        assertEquals(List.of("three dims"), names(odd), "3 维的查询只找 3 维的，也不报错");

        // ---- 二、一批取：顺序与上限
        List<List<Neighbour>> gathered1 =
                resolution.nearestTypedForEachWith(f.kb(), f.subjects(), 10, 1);
        List<List<Neighbour>> gathered8 =
                resolution.nearestTypedForEachWith(f.kb(), f.subjects(), 10, 8);
        assertEquals(f.subjects().size(), gathered1.size());
        for (int i = 0; i < f.subjects().size(); i++) {
            assertEquals(names(serial.get(i)), names(gathered1.get(i)),
                    "上限 1：第 " + i + " 个位置是第 " + i + " 个主语的邻居");
            assertEquals(names(serial.get(i)), names(gathered8.get(i)),
                    "上限 8：第 " + i + " 个位置是第 " + i + " 个主语的邻居");
        }

        // ---- 三、两个连接的池子也跑得完六十个（Rust tiny pool 对等：小池 ResolutionService 实例）
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(DB_URL);
        cfg.setUsername(DB_USER);
        cfg.setPassword(DB_PASS);
        cfg.setMaximumPoolSize(2);
        cfg.setConnectionTimeout(10_000);
        try (HikariDataSource tiny = new HikariDataSource(cfg)) {
            ResolutionService tinyResolution = new ResolutionService(resolutionMapper, names,
                    nameVectors, governanceReads, temporal, reviews, documents, jobs, reasoning,
                    vectorIndex, tiny, txManager);
            // Rust subjects.iter().cycle().take(60)：三个主语循环铺满 60 个
            List<UUID> cycled = new ArrayList<>(60);
            for (int i = 0; i < 60; i++) {
                cycled.add(f.subjects().get(i % 3));
            }
            List<List<Neighbour>> overTiny =
                    tinyResolution.nearestTypedForEachWith(f.kb(), cycled, 10, 8);
            assertEquals(60, overTiny.size());
            for (int i = 0; i < overTiny.size(); i++) {
                assertEquals(names(serial.get(i % 3)), names(overTiny.get(i)));
            }
        }

        // ---- 四、索引就位之后同一份答案
        VectorIndexService.Built built =
                vectorIndex.build(VectorIndexService.Target.ENTITY_PROFILES, DIMS);
        assertTrue(built.created());
        assertEquals("entities_profile_embedding_hnsw_5", built.name());
        List<List<Neighbour>> indexed =
                resolution.nearestTypedForEach(f.kb(), f.subjects(), 10);
        for (int i = 0; i < f.subjects().size(); i++) {
            assertEquals(names(serial.get(i)), names(indexed.get(i)), "索引改了主语 " + i + " 的邻居");
        }

        // ---- 五、后代集合按粗类记一批
        DescendantsMemo memo = new DescendantsMemo();
        assertEquals(direct(f.thing()), memo.get(resolution, f.kb(), f.thing()));
        assertEquals(new HashSet<>(List.of(f.person(), f.employee())),
                memo.get(resolution, f.kb(), f.person()));
        assertEquals(direct(f.company()), memo.get(resolution, f.kb(), f.company()));
        assertEquals(3, memo.len());
        assertTrue(memo.get(resolution, f.kb(), null).isEmpty(), "没有粗类：没有后代这个轴");
        assertEquals(3, memo.len(), "没有粗类的不进表");
        // 批内本体动了：记忆给的还是问过的那一份，直接问给的是新的——这就是「记住」
        UUID intern = Uuids.nowV7();
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'intern', 'intern')",
                intern, f.kb());
        jdbc.update("INSERT INTO entity_type_parents (child_id, parent_id) VALUES (?, ?)", intern, f.person());
        assertFalse(memo.get(resolution, f.kb(), f.person()).contains(intern));
        assertTrue(direct(f.person()).contains(intern));
    }

    private Set<UUID> direct(UUID root) {
        return new HashSet<>(resolution.descendantsOf(f.kb(), root));
    }
}
