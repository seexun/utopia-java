package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.PathsService;
import tech.seexun.jknow.store.PathsService.Limits;
import tech.seexun.jknow.store.PathsService.Path;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 移植自 tests/store/a_path_joins_two_entities.rs（422 行，5 例）——#558：两点之间的路径。
 * 回的是链不是节点集；at 时每条边都得在那一刻成立；枢纽不往下展；记录轴回放；
 * 反方向在每一跳数下都不丢。断言逐条对译。
 */
class APathJoinsTwoEntitiesTests extends HeartStoreTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    tech.seexun.jknow.store.GraphWriteService writes;
    @Autowired
    PathsService paths;

    record Fixture(UUID org, UUID kb, UUID acme, UUID beta, UUID gamma, UUID bob, UUID carol,
                   UUID dan, UUID employee, UUID founder, UUID partner, UUID advises,
                   UUID direct) {
    }

    private Fixture seed() {
        UUID org = Uuids.nowV7();
        UUID ws = Uuids.nowV7();
        UUID kb = Uuids.nowV7();
        UUID person = Uuids.nowV7();
        UUID org2 = Uuids.nowV7();
        UUID acme = Uuids.nowV7();
        UUID beta = Uuids.nowV7();
        UUID gamma = Uuids.nowV7();
        UUID bob = Uuids.nowV7();
        UUID carol = Uuids.nowV7();
        UUID dan = Uuids.nowV7();
        UUID employee = Uuids.nowV7();
        UUID founder = Uuids.nowV7();
        UUID partner = Uuids.nowV7();
        UUID advises = Uuids.nowV7();
        UUID direct = null;
        jdbc.update("INSERT INTO organizations (id, name) VALUES (?, 'paths-test')", org);
        jdbc.update("INSERT INTO workspaces (id, org_id, name) VALUES (?, ?, 'paths-test')", ws, org);
        jdbc.update("INSERT INTO knowledge_bases (id, workspace_id, name) VALUES (?, ?, 'paths-test')",
                kb, ws);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'person', 'Person')",
                person, kb);
        jdbc.update("INSERT INTO entity_types (id, kb_id, key, label) VALUES (?, ?, 'org', 'Org')",
                org2, kb);
        for (Object[] row : new Object[][]{{acme, org2, "Acme"}, {beta, org2, "Beta"},
                {gamma, org2, "Gamma"}, {bob, person, "Bob"}, {carol, person, "Carol"},
                {dan, person, "Dan"}}) {
            jdbc.update(
                    "INSERT INTO entities (id, kb_id, type_id, canonical_name) VALUES (?, ?, ?, ?)",
                    row[0], kb, row[1], row[2]);
        }
        for (UUID p : List.of(employee, founder, partner, advises)) {
            jdbc.update("INSERT INTO relation_types (id, kb_id, key, label) VALUES (?, ?, ?, ?)",
                    p, kb, p.toString(), p.toString());
        }
        Validity[] spans = {
                new Validity(t("2016-01-01T00:00:00Z"), "month", t("2020-12-01T00:00:00Z"), "month",
                        null, null),
                new Validity(t("2021-01-01T00:00:00Z"), "month", null, null, null, null),
                new Validity(t("2023-01-01T00:00:00Z"), "month", null, null, null, null),
                new Validity(t("2018-01-01T00:00:00Z"), "month", null, null, null, null),
                new Validity(t("2019-01-01T00:00:00Z"), "month", null, null, null, null),
                new Validity(t("2020-01-01T00:00:00Z"), "month", null, null, null, null),
                new Validity(t("2016-01-01T00:00:00Z"), "month", t("2019-01-01T00:00:00Z"), "month",
                        null, null),
                new Validity(t("2021-01-01T00:00:00Z"), "month", null, null, null, null),
                // 同一条边的第二份事实（只有锚点、没日期的那种）：路径不该因此多出一条
                new Validity(null, null, null, null, t("2024-05-01T00:00:00Z"), null),
        };
        Object[][] edges = {
                {acme, employee, bob},
                {bob, founder, beta},
                {acme, partner, beta},
                {acme, employee, carol},
                {carol, advises, gamma},
                {gamma, partner, beta},
                {acme, employee, dan},
                {dan, founder, beta},
                {bob, founder, beta},
        };
        for (int i = 0; i < edges.length; i++) {
            var wrote = writes.insertFact(kb, (UUID) edges[i][0], (UUID) edges[i][1],
                    (UUID) edges[i][2], spans[i], 0.9f);
            if (edges[i][0].equals(acme) && edges[i][1].equals(partner)) {
                direct = wrote.id();
            }
        }
        return new Fixture(org, kb, acme, beta, gamma, bob, carol, dan, employee, founder,
                partner, advises, direct);
    }

    private static java.time.OffsetDateTime t(String s) {
        return java.time.OffsetDateTime.parse(s);
    }

    /** 路径读成「途经谁」，好断言。 */
    private static List<UUID> via(Path p) {
        return p.nodes().subList(1, p.nodes().size() - 1);
    }

    @Test
    void pathsComeShortestFirstAndStopAtMaxHops() {
        Fixture f = seed();
        try {
            List<Path> all = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null,
                    Limits.DEFAULT);
            List<Integer> hops = all.stream().map(Path::hops).toList();
            assertEquals(List.of(1, 2, 2, 3), hops,
                    "短的在前：直连、两条两跳、一条三跳；Bob 那条边有两份事实，仍是一条路");
            assertTrue(via(all.get(0)).isEmpty());
            List<List<UUID>> two = List.of(via(all.get(1)), via(all.get(2)));
            assertTrue(two.contains(List.of(f.bob())) && two.contains(List.of(f.dan())));
            assertEquals(List.of(f.carol(), f.gamma()), via(all.get(3)));
            // 边自带两端与谓词，顺着走的边主语就是上一个节点
            Path chain = all.get(3);
            assertEquals(f.acme(), chain.edges().get(0).subjectId());
            assertEquals("Carol", chain.edges().get(0).objectName());
            assertEquals(f.partner().toString(), chain.edges().get(2).predicate());
            assertEquals(f.beta(), chain.edges().get(2).objectId());

            List<Path> twoHop = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null,
                    Limits.of(2));
            assertEquals(3, twoHop.size(), "两跳以内没有 Carol 那条");
            List<Path> oneHop = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null,
                    new Limits(1, Limits.DEFAULT.maxPaths(), Limits.DEFAULT.hubDegree(),
                            Limits.DEFAULT.maxEdges(), Limits.DEFAULT.maxCandidates()));
            assertEquals(1, oneHop.size());
            assertEquals(f.direct(), oneHop.get(0).edges().get(0).factId());
            // 反向问同样走得通
            List<Path> back = paths.pathsBetween(f.kb(), f.beta(), f.acme(), null, null,
                    Limits.DEFAULT);
            assertEquals(4, back.size());
            assertEquals(List.of(f.beta(), f.acme()), back.get(0).nodes());
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 带 at 时每一条边都得在那一刻成立：2021 年中只剩 Carol–Gamma 那条。 */
    @Test
    void aPathHoldsOnlyWhenEveryEdgeHolds() {
        Fixture f = seed();
        try {
            List<Path> mid2021 = paths.pathsBetween(f.kb(), f.acme(), f.beta(),
                    t("2021-06-15T00:00:00Z"), null, Limits.DEFAULT);
            assertEquals(1, mid2021.size(), "Bob 已离开、Dan 已离开、直连还没签");
            assertEquals(List.of(f.carol(), f.gamma()), via(mid2021.get(0)));

            List<Path> in2018 = paths.pathsBetween(f.kb(), f.acme(), f.beta(),
                    t("2018-06-15T00:00:00Z"), null, Limits.DEFAULT);
            assertTrue(in2018.isEmpty(), "2018 年 Beta 还没被谁创办，什么都连不上");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 枢纽不往下展：Carol 一多几条边就不再是三跳路径的中转。 */
    @Test
    void aHubIsNotWalkedThrough() {
        Fixture f = seed();
        try {
            Limits tight = new Limits(Limits.DEFAULT.maxHops(), Limits.DEFAULT.maxPaths(), 2,
                    Limits.DEFAULT.maxEdges(), Limits.DEFAULT.maxCandidates());
            List<Path> before = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null, tight);
            assertEquals(4, before.size(), "Carol 两条边，不算枢纽");
            // Carol 再顾问三家：度数 5，超过上限
            for (int i = 0; i < 3; i++) {
                UUID other = Uuids.nowV7();
                jdbc.update(
                        "INSERT INTO entities (id, kb_id, canonical_name) VALUES (?, ?, 'Other')",
                        other, f.kb());
                writes.insertFact(f.kb(), f.carol(), f.advises, other,
                        new Validity(t("2020-01-01T00:00:00Z"), "year", null, null, null, null),
                        0.9f);
            }
            List<Path> after = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null, tight);
            assertEquals(3, after.size(), "经 Carol 的三跳没了");
            assertTrue(after.stream().noneMatch(p -> via(p).contains(f.carol())));
            List<Path> loose = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null,
                    Limits.DEFAULT);
            assertEquals(4, loose.size(), "默认上限下她还只是个普通节点");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    /** 记录轴：直连那条边今天才录入，问「昨天的记录」走不到它。 */
    @Test
    void aPathReadsTheBaseAsItWas() {
        Fixture f = seed();
        try {
            var twoDaysAgo = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(2);
            jdbc.update("UPDATE facts SET recorded_at = ? WHERE kb_id = ? AND id <> ?",
                    twoDaysAgo, f.kb(), f.direct());
            var yesterday = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).minusDays(1);
            List<Path> then = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, yesterday,
                    Limits.DEFAULT);
            assertEquals(3, then.size(), "直连那条今天才进账本");
            assertTrue(then.stream().allMatch(p -> p.hops() >= 2));
            List<Path> today = paths.pathsBetween(f.kb(), f.acme(), f.beta(), null, null,
                    Limits.DEFAULT);
            assertEquals(4, today.size(), "不带 as_of 是此刻的记录");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    @Test
    void oppositeDirectionsRemainDistinctAtEveryHopCount() {
        Fixture f = seed();
        try {
            UUID[] nodes = {Uuids.nowV7(), Uuids.nowV7(), Uuids.nowV7(), Uuids.nowV7()};
            for (UUID node : nodes) {
                jdbc.update(
                        "INSERT INTO entities(id,kb_id,canonical_name,created_at)"
                                + " VALUES (?,?," + "?::text, '2020-01-01')",
                        node, f.kb(), node.toString());
            }
            String[][] facts = {
                    {"2021-01-01T00:00:00Z", "0.9"},
                    {"2022-01-01T00:00:00Z", "0.7"},
                    {"2021-01-01T00:00:00Z", "0.8"},
                    {"2021-01-01T00:00:00Z", "0.9"},
                    {"2021-01-01T00:00:00Z", "0.9"},
            };
            UUID[] factIds = new UUID[facts.length];
            Object[][] edges = {
                    {nodes[0], nodes[1]}, {nodes[0], nodes[1]}, {nodes[1], nodes[0]},
                    {nodes[1], nodes[2]}, {nodes[2], nodes[3]},
            };
            for (int i = 0; i < edges.length; i++) {
                var wrote = writes.insertFact(f.kb(), (UUID) edges[i][0], f.partner,
                        (UUID) edges[i][1],
                        new Validity(t(facts[i][0]), "day", null, null, null, null),
                        Float.parseFloat(facts[i][1]));
                factIds[i] = wrote.id();
            }
            jdbc.update("UPDATE facts SET recorded_at='2022-01-01' WHERE id IN (?,?,?,?,?)",
                    factIds[0], factIds[1], factIds[2], factIds[3], factIds[4]);
            String snapshotBefore = jdbc.queryForObject(
                    "SELECT jsonb_agg(to_jsonb(f) ORDER BY id) FROM facts f WHERE kb_id = ?",
                    String.class, f.kb());
            var at = t("2023-01-01T00:00:00Z");
            for (int hops = 1; hops <= 3; hops++) {
                for (UUID[] pair : new UUID[][]{{nodes[0], nodes[hops]},
                        {nodes[hops], nodes[0]}}) {
                    Limits limits = new Limits(hops, Limits.DEFAULT.maxPaths(),
                            Limits.DEFAULT.hubDegree(), Limits.DEFAULT.maxEdges(),
                            Limits.DEFAULT.maxCandidates());
                    List<Path> found = paths.pathsBetween(f.kb(), pair[0], pair[1], at, at,
                            limits);
                    org.junit.jupiter.api.Assertions.assertTrue(found.size() == 2,
                            hops + "-hop query lost an opposite direction: " + found);
                    java.util.Set<String> signatures = new java.util.HashSet<>();
                    for (Path p : found) {
                        StringBuilder sig = new StringBuilder();
                        for (int i = 0; i < p.edges().size(); i++) {
                            sig.append(p.edges().get(i).subjectId()).append(">")
                                    .append(p.edges().get(i).objectId()).append(";");
                        }
                        signatures.add(sig.toString());
                    }
                    org.junit.jupiter.api.Assertions.assertTrue(signatures.size() == 2,
                            "duplicate direction survived");
                    org.junit.jupiter.api.Assertions.assertTrue(
                            found.stream().noneMatch(p -> p.edges().stream()
                                    .anyMatch(e -> e.factId().equals(factIds[1]))),
                            "lower-confidence duplicate displaced the preferred observation");
                    Limits cappedLimits = new Limits(hops, 1, Limits.DEFAULT.hubDegree(),
                            Limits.DEFAULT.maxEdges(), Limits.DEFAULT.maxCandidates());
                    List<Path> capped = paths.pathsBetween(f.kb(), pair[0], pair[1], at, at,
                            cappedLimits);
                    org.junit.jupiter.api.Assertions.assertTrue(capped.size() == 1
                            && capped.get(0).edges().stream().map(Path0FactId).toList()
                                    .equals(found.get(0).edges().stream().map(Path0FactId)
                                            .toList()),
                            "cap/ranking changed");
                    org.junit.jupiter.api.Assertions.assertTrue(
                            paths.pathsBetween(f.kb(), Uuids.nowV7(), pair[0], at, at, limits).isEmpty(), "cross-KB path escaped isolation");
                }
            }
            String snapshotAfter = jdbc.queryForObject(
                    "SELECT jsonb_agg(to_jsonb(f) ORDER BY id) FROM facts f WHERE kb_id = ?",
                    String.class, f.kb());
            org.junit.jupiter.api.Assertions.assertEquals(snapshotBefore, snapshotAfter,
                    "path reads changed facts");
            jdbc.update("UPDATE facts SET invalidated_at='2024-01-01' WHERE id = ?", factIds[2]);
            List<Path> historic = paths.pathsBetween(f.kb(), nodes[0], nodes[1], at, at,
                    Limits.DEFAULT);
            List<Path> current = paths.pathsBetween(f.kb(), nodes[0], nodes[1], at,
                    t("2025-01-01T00:00:00Z"), Limits.DEFAULT);
            org.junit.jupiter.api.Assertions.assertTrue(historic.size() == 2 && current.size() == 1,
                    "record-axis retraction changed");
            org.junit.jupiter.api.Assertions.assertTrue(
                    paths.pathsBetween(f.kb(), nodes[0], nodes[1], t("2019-01-01T00:00:00Z"), at,
                            Limits.DEFAULT).isEmpty(), "world-axis filter changed");
        } finally {
            jdbc.update("DELETE FROM organizations WHERE id = ?", f.org());
        }
    }

    private static final java.util.function.Function<
            tech.seexun.jknow.store.PathsService.PathEdge, UUID> Path0FactId =
            tech.seexun.jknow.store.PathsService.PathEdge::factId;
}
