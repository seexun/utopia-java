package tech.seexun.jknow.store;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.mapper.GraphMapper;
import tech.seexun.jknow.store.mapper.GraphMapper.DegreeRow;
import tech.seexun.jknow.store.mapper.GraphMapper.PathEdgeRow;
import tech.seexun.jknow.store.mapper.GraphMapper.PathTouchRow;

/**
 * 两点之间的路径（#558，paths.rs:115-336 全量对等）。「OpenAI 和 Anthropic 的关系」是
 * 一道图谱题——回的是<b>链</b>不是节点集，短的在前、途经节点越冷门越靠前。
 *
 * <p>两根时间轴都要过：带 at 时路径上每一条边都得在那一刻成立；带 as_of 时按当时的
 * 记录走，包括当时还没合并的实体归谁。搜索是两头对中间：三跳以内的路径只要知道 a 的
 * 两跳邻接和 b 的一跳邻居就能拼出来；枢纽（度数超过上限的中间节点）不往下展。
 */
@Service
public class PathsService {

    /** 搜索的边界（paths.rs:30-52）。测试把 hubDegree 调小来验证枢纽不被穿越。 */
    public record Limits(int maxHops, int maxPaths, long hubDegree, long maxEdges,
                         int maxCandidates) {

        public static Limits DEFAULT = new Limits(3, 10, 150, 20_000, 400);

        public static Limits of(int maxHops) {
            return new Limits(maxHops, DEFAULT.maxPaths, DEFAULT.hubDegree, DEFAULT.maxEdges,
                    DEFAULT.maxCandidates);
        }
    }

    /** 路径上的一条边（paths.rs:57-83）：方向由调用方按走向判断。 */
    public record PathEdge(UUID factId, UUID subjectId, String subjectName, UUID objectId,
                           String objectName, String predicate, OffsetDateTime validFrom,
                           String validFromPrecision, OffsetDateTime validTo,
                           String validToPrecision, OffsetDateTime holdsFrom,
                           OffsetDateTime holdsTo, float confidence, boolean endDerived,
                           boolean timeCorrected, String correctedEnds, UUID closedById,
                           String closedBy, Object closedByValue, String correctionNote) {

        static PathEdge of(PathEdgeRow r) {
            return new PathEdge(r.factId(), r.subjectId(), r.subjectName(), r.objectId(),
                    r.objectName(), r.predicate(), r.validFrom(), r.validFromPrecision(),
                    r.validTo(), r.validToPrecision(), r.holdsFrom(), r.holdsTo(),
                    r.confidence(), r.endDerived(), r.timeCorrected(), r.correctedEnds(),
                    r.closedById(), r.closedBy(), r.closedByValue(), r.correctionNote());
        }
    }

    /** 一条路径（paths.rs:85-97）：节点序列含两端；specificity 是途经节点度数的 ln(1+d) 之和。 */
    public record Path(List<UUID> nodes, List<PathEdge> edges, double specificity) {

        public int hops() {
            return edges.size();
        }
    }

    /** 候选：事实 id 序列与节点序列，排序前先攒起来。 */
    private record Candidate(List<UUID> nodes, List<UUID> facts) {
    }

    private final GraphMapper graph;

    public PathsService(GraphMapper graph) {
        this.graph = graph;
    }

    /** paths.rs:115-336。 */
    public List<Path> pathsBetween(UUID kbId, UUID a, UUID b, OffsetDateTime at,
                                   OffsetDateTime asOf, Limits limits) {
        int maxHops = Math.max(1, Math.min(3, limits.maxHops()));
        if (a.equals(b)) {
            return List.of();
        }

        // a 的一跳：邻居 → 连到它的边
        Map<UUID, List<UUID>> aEdges = new LinkedHashMap<>();
        for (PathTouchRow t : touching(kbId, List.of(a), at, asOf)) {
            UUID other = t.subjectId().equals(a) ? t.objectId() : t.subjectId();
            aEdges.computeIfAbsent(other, k -> new ArrayList<>()).add(t.factId());
        }

        // b 的一跳（两跳以上才用得上）
        Map<UUID, List<UUID>> bEdges = new LinkedHashMap<>();
        if (maxHops >= 2) {
            for (PathTouchRow t : touching(kbId, List.of(b), at, asOf)) {
                UUID other = t.subjectId().equals(b) ? t.objectId() : t.subjectId();
                bEdges.computeIfAbsent(other, k -> new ArrayList<>()).add(t.factId());
            }
        }

        // a 的第二层：只展非枢纽的一跳邻居，按度数从小到大读到 maxEdges 为止
        Map<UUID, List<Map.Entry<UUID, UUID>>> adj2 = new LinkedHashMap<>();
        if (maxHops >= 3) {
            List<UUID> mids = new ArrayList<>();
            for (UUID x : aEdges.keySet()) {
                if (!x.equals(a) && !x.equals(b)) {
                    mids.add(x);
                }
            }
            Map<UUID, Long> degree = degrees(kbId, mids, at, asOf);
            List<Map.Entry<UUID, Long>> byDegree = new ArrayList<>();
            for (UUID x : mids) {
                long d = degree.getOrDefault(x, 0L);
                if (d <= limits.hubDegree()) {
                    byDegree.add(Map.entry(x, d));
                }
            }
            byDegree.sort(Comparator.comparingLong(Map.Entry::getValue));
            long budget = limits.maxEdges();
            List<UUID> expand = new ArrayList<>();
            for (Map.Entry<UUID, Long> e : byDegree) {
                if (budget < e.getValue()) {
                    break;
                }
                budget -= e.getValue();
                expand.add(e.getKey());
            }
            if (!expand.isEmpty()) {
                Set<UUID> expandSet = new HashSet<>(expand);
                for (PathTouchRow t : touching(kbId, expand, at, asOf)) {
                    for (Map.Entry<UUID, UUID> dir : List.of(
                            Map.entry(t.subjectId(), t.objectId()),
                            Map.entry(t.objectId(), t.subjectId()))) {
                        if (expandSet.contains(dir.getKey())) {
                            adj2.computeIfAbsent(dir.getKey(), k -> new ArrayList<>())
                                    .add(Map.entry(dir.getValue(), t.factId()));
                        }
                    }
                }
            }
        }

        // 拼候选：一跳直连，两跳交集，三跳经第二层碰到 b 的邻居
        List<Candidate> candidates = new ArrayList<>();
        int cap = Math.max(1, limits.maxCandidates());
        for (UUID f : aEdges.getOrDefault(b, List.of())) {
            candidates.add(new Candidate(new ArrayList<>(List.of(a, b)),
                    new ArrayList<>(List.of(f))));
        }
        if (maxHops >= 2) {
            List<UUID> mids = new ArrayList<>();
            for (UUID x : aEdges.keySet()) {
                if (!x.equals(a) && !x.equals(b) && bEdges.containsKey(x)) {
                    mids.add(x);
                }
            }
            mids.sort(Comparator.naturalOrder());
            outer2:
            for (UUID x : mids) {
                for (UUID fa : aEdges.get(x)) {
                    for (UUID fb : bEdges.get(x)) {
                        if (candidates.size() >= cap) {
                            break outer2;
                        }
                        candidates.add(new Candidate(new ArrayList<>(List.of(a, x, b)),
                                new ArrayList<>(List.of(fa, fb))));
                    }
                }
            }
        }
        if (maxHops >= 3) {
            List<UUID> xs = new ArrayList<>(adj2.keySet());
            xs.sort(Comparator.naturalOrder());
            outer3:
            for (UUID x : xs) {
                for (Map.Entry<UUID, UUID> xy : adj2.get(x)) {
                    UUID y = xy.getKey();
                    if (y.equals(a) || y.equals(b) || y.equals(x)) {
                        continue;
                    }
                    List<UUID> fbs = bEdges.get(y);
                    if (fbs == null) {
                        continue;
                    }
                    for (UUID fa : aEdges.get(x)) {
                        for (UUID fb : fbs) {
                            if (candidates.size() >= cap) {
                                break outer3;
                            }
                            candidates.add(new Candidate(
                                    new ArrayList<>(List.of(a, x, y, b)),
                                    new ArrayList<>(List.of(fa, xy.getValue(), fb))));
                        }
                    }
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 排序：短的在前；同长的按途经节点的冷门程度；再按置信度
        List<UUID> midNodes = new ArrayList<>();
        for (Candidate c : candidates) {
            midNodes.addAll(c.nodes().subList(1, c.nodes().size() - 1));
        }
        midNodes.sort(Comparator.naturalOrder());
        List<UUID> uniqueMids = new ArrayList<>();
        for (UUID m : midNodes) {
            if (uniqueMids.isEmpty() || !uniqueMids.get(uniqueMids.size() - 1).equals(m)) {
                uniqueMids.add(m);
            }
        }
        Map<UUID, Long> degree = degrees(kbId, uniqueMids, at, asOf);
        java.util.function.ToDoubleFunction<Candidate> specificity = c -> {
            double sum = 0;
            for (UUID n : c.nodes().subList(1, c.nodes().size() - 1)) {
                sum += Math.log(1.0 + degree.getOrDefault(n, 0L));
            }
            return sum;
        };
        List<UUID> allFacts = new ArrayList<>();
        for (Candidate c : candidates) {
            allFacts.addAll(c.facts());
        }
        allFacts.sort(Comparator.naturalOrder());
        List<UUID> uniqueFacts = new ArrayList<>();
        for (UUID f : allFacts) {
            if (uniqueFacts.isEmpty() || !uniqueFacts.get(uniqueFacts.size() - 1).equals(f)) {
                uniqueFacts.add(f);
            }
        }
        Map<UUID, PathEdge> detail = details(kbId, uniqueFacts, asOf);
        java.util.function.ToDoubleFunction<Candidate> confidence = c -> {
            double n = Math.max(1, c.facts().size());
            double sum = 0;
            for (UUID f : c.facts()) {
                PathEdge e = detail.get(f);
                sum += e == null ? 0.0 : e.confidence();
            }
            return sum / n;
        };
        record Scored(int hops, double spec, double conf, Candidate candidate) {
        }
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            scored.add(new Scored(c.facts().size(), specificity.applyAsDouble(c),
                    confidence.applyAsDouble(c), c));
        }
        scored.sort(Comparator.<Scored>comparingInt(Scored::hops)
                .thenComparingDouble(Scored::spec)
                .thenComparing(Comparator.comparingDouble(Scored::conf).reversed()));

        // 同一串节点、同一串有向谓词只回一条：同向的重复观察折叠，但 A → B 和 A ← B
        // 不是同一条关系，不能因遍历时两端相同而丢掉一个方向
        Set<String> seen = new HashSet<>();
        List<Path> out = new ArrayList<>();
        for (Scored s : scored) {
            if (out.size() >= limits.maxPaths()) {
                break;
            }
            List<PathEdge> edges = new ArrayList<>();
            boolean complete = true;
            for (UUID f : s.candidate().facts()) {
                PathEdge e = detail.get(f);
                if (e == null) {
                    // 细节查不到的边（as_of 时刻两端之一不可见）整条路径不要：链断了一环
                    complete = false;
                    break;
                }
                edges.add(e);
            }
            if (!complete) {
                continue;
            }
            List<String> directed = new ArrayList<>();
            for (int i = 0; i < edges.size(); i++) {
                String predicate = edges.get(i).predicate() == null ? ""
                        : edges.get(i).predicate();
                directed.add(predicate + "|" + edges.get(i).subjectId().equals(
                        s.candidate().nodes().get(i)));
            }
            String key = s.candidate().nodes() + "|" + directed;
            if (!seen.add(key)) {
                continue;
            }
            out.add(new Path(s.candidate().nodes(), edges, s.spec()));
        }
        return out;
    }

    /** paths.rs:370-397 touching：碰到这批实体的边（两个方向），两轴都过，自环不要。 */
    private List<PathTouchRow> touching(UUID kbId, List<UUID> ids, OffsetDateTime at,
                                       OffsetDateTime asOf) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return asOf == null
                ? graph.selectPathTouching(kbId, ids, at)
                : graph.selectPathTouchingAsOf(kbId, ids, at, asOf);
    }

    /** paths.rs:400-419 degrees。 */
    private Map<UUID, Long> degrees(UUID kbId, List<UUID> ids, OffsetDateTime at,
                                    OffsetDateTime asOf) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Long> out = new HashMap<>();
        for (DegreeRow r : asOf == null
                ? graph.selectPathDegrees(kbId, ids, at)
                : graph.selectPathDegreesAsOf(kbId, ids, at, asOf)) {
            out.put(r.id(), r.degree());
        }
        return out;
    }

    /** paths.rs:422-452 details：这批事实的两端名字、谓词与区间。 */
    private Map<UUID, PathEdge> details(UUID kbId, List<UUID> factIds, OffsetDateTime asOf) {
        if (factIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PathEdge> out = new HashMap<>();
        for (PathEdgeRow r : asOf == null
                ? graph.selectPathDetails(kbId, factIds)
                : graph.selectPathDetailsAsOf(kbId, factIds, asOf)) {
            out.put(r.factId(), PathEdge.of(r));
        }
        return out;
    }
}
