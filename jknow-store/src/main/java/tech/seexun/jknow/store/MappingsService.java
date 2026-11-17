package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.Rrf;
import tech.seexun.jknow.store.entity.ConceptMapping;
import tech.seexun.jknow.store.entity.ExplorationRun;
import tech.seexun.jknow.store.entity.MappingRevision;
import tech.seexun.jknow.store.entity.MappingText;
import tech.seexun.jknow.store.mapper.MappingsMapper;
import tech.seexun.jknow.store.mapper.MappingsMapper.EmbeddedText;
import tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText;
import tech.seexun.jknow.store.NameVectorsService.Embedder;

/**
 * 语义层口径读面（对齐旧仓库 mapping_routes.rs:36-73 list 的组装与
 * mapping_index.rs:85-120 relevant 的降级链）。
 *
 * <p>relevant 的检索两路（M3 任务 10 补齐向量路）：向量（concept_mappings.embedding +
 * pgvector）与词面（全部确认口径拉回在内存打分），RRF 融合——词面恒在第一位、向量有就
 * 在第二位（mapping_index.rs:117-121）。降级链：向量+词面 → 只词面（没配嵌入模型——
 * embedder 传 null，与 Rust 「vector_channel 返回 None」同一档）→ 两路都空时按字典序取
 * 前几条（confirmed(k) 兜底）。每一级都比上一级差一点，没有一级是「没有口径」。
 *
 * <p>索引是<b>懒的</b>：relevant 先把没嵌的补上再查（refresh）。一次问数通常没有要补的；
 * 刚确认了一批才会有，那一次多等一个嵌入调用。谁都不用记得去刷新。
 */
@Service
public class MappingsService {

    private static final Logger log = LoggerFactory.getLogger(MappingsService.class);

    /** mapping_index.rs:28 DEFINITIONS_IN_PROMPT：进提示词几条（relevant 的 k 缺省）。 */
    public static final int DEFINITIONS_IN_PROMPT = 8;

    /** mapping_index.rs:29 RECALL_PER_CHANNEL：每路召回几条再合并。 */
    private static final int RECALL_PER_CHANNEL = 24;

    /** mapping_index.rs:32 BATCH：一次嵌几条。 */
    private static final int BATCH = 32;

    /**
     * mapping_index.rs:35 REFRESH_PER_TURN：一问最多补嵌几条。补嵌跑在问数的请求路径上：
     * 换了嵌入模型之后的第一问不该把几百条口径一口气嵌完才开口——补不完的下一问接着补，
     * 这期间少的那些由词面一路兜着。
     */
    private static final int REFRESH_PER_TURN = 64;

    private final MappingsMapper mappings;

    public MappingsService(MappingsMapper mappings) {
        this.mappings = mappings;
    }

    /** 列表页四段组装（mapping_routes.rs:57-72：items/total/counts/last_run）。 */
    public MappingPage page(UUID kbId, String status, String q, long limit, long offset) {
        List<ConceptMapping> items = mappings.selectMappingPage(kbId, status, q, limit, offset);
        long total = mappings.countMappingPage(kbId, status, q);
        MappingsMapper.StatusCounts counts = mappings.selectStatusCounts(kbId);
        ExplorationRun lastRun = mappings.selectRecentRuns(kbId, 1).stream().findFirst().orElse(null);
        return new MappingPage(items, total,
                new Counts(counts.proposed(), counts.confirmed(), counts.rejected()), lastRun);
    }

    public List<MappingRevision> revisions(UUID kbId, UUID mappingId) {
        return mappings.selectRevisions(kbId, mappingId);
    }

    /**
     * mappings.rs:179-201 {@code decide}：有人对一条映射表态了（confirmed|rejected，
     * 枚举校验在路由层——错误码 bad_status 属 HTTP 面）。
     *
     * <p><b>改状态不删行</b>：确认发生过、拒绝也发生过。而拒绝留痕当下就有用——下一轮
     * 探索会再次算出被拒绝过的那条，{@code propose} 的 {@code WHERE status = 'proposed'}
     * 据此不把它刷回待看。0 行（不存在/别的库）→ NotFound。
     */
    public void decide(UUID kbId, UUID mappingId, String status, UUID actor) {
        if (mappings.decideMapping(kbId, mappingId, status, actor) == 0) {
            throw AppException.NOT_FOUND;
        }
    }

    // ====================================================================
    // 写面（mappings.rs:92-132 create / 209-262 revise / 166-193 ensure_concept_types；
    // M5 任务 3 随 mapping_routes 的 create/revise/preview 落地）
    // ====================================================================

    /**
     * mappings.rs:92-132 {@code create}：人从零写一条口径（#562）。<b>落下来就是确认的</b>：
     * 写的人就是表态的人，不需要再过一遍审。
     *
     * <p>同一个 (概念, 源) 已经有一条时报冲突，而不是悄悄覆盖：那一条可能是探索提的、
     * 人已经确认过的，覆盖等于抹掉一次表态。要改用 {@link #revise}。探索反过来也盖不掉
     * 这条：{@code propose} 只刷新 proposed 的行。
     */
    public UUID create(UUID kbId, UUID conceptId, String source, String tableName, String expr,
                       String sql, String unit, String summary, boolean derived, UUID actor) {
        UUID id = tech.seexun.jknow.common.uuids.Uuids.nowV7();
        try {
            mappings.insertMapping(id, kbId, conceptId, source, tableName, expr, sql, unit,
                    summary, derived, actor);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw AppException.conflict(
                    "A definition for this concept on this source already exists;"
                            + " revise it instead");
        }
        return id;
    }

    /**
     * mappings.rs:209-262 {@code revise}：改一条确认过的口径。<b>改之前那一版先进
     * revisions</b>——问数回溯历史报表时要答得出「上季度这个数是怎么算的」。存整版快照
     * 而不是差异：读的时候要的就是「当时是什么」。0 行（不存在/别的库）→ NotFound。
     * 快照+覆写同一事务。
     */
    @org.springframework.transaction.annotation.Transactional
    public void revise(UUID kbId, UUID mappingId, String tableName, String expr, String sql,
                       String unit, String summary, boolean derived, UUID actor) {
        String beforeText = mappings.selectMappingBefore(kbId, mappingId);
        if (beforeText == null) {
            throw AppException.NOT_FOUND;
        }
        com.fasterxml.jackson.databind.JsonNode before;
        try {
            before = new com.fasterxml.jackson.databind.ObjectMapper().readTree(beforeText);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("mapping before-snapshot is not JSON", e);
        }
        mappings.insertMappingRevision(tech.seexun.jknow.common.uuids.Uuids.nowV7(), mappingId,
                before, actor);
        mappings.reviseMapping(kbId, mappingId, tableName, expr, sql, unit, summary, derived);
    }

    /**
     * mappings.rs:166-193 {@code ensure_concept_types}：Metric/Dimension 两个内置概念类，
     * 缺了补（幂等）。create 与探索（propose）共用——概念实体落在这两个类下。
     */
    public void ensureConceptTypes(UUID kbId) {
        // 描述串原样照抄 Rust 字面量（含换行折叠留下的长空格——已入库的数据长这样，
        // 改写成「干净」的文案会让新旧库的描述对不上）
        String[][] types = {
                {"metric", "Metric",
                        "An aggregatable business quantity (revenue, order count, average ticket)"
                                + " that              maps to a definition in a mounted database."},
                {"dimension", "Dimension",
                        "A group-by attribute (region, month, product line) that maps to a column"
                                + " in a              mounted database."},
        };
        for (String[] t : types) {
            mappings.insertConceptTypeIfMissing(tech.seexun.jknow.common.uuids.Uuids.nowV7(),
                    kbId, t[0], t[1], t[2]);
        }
    }

    /** mapping_routes.rs:291-296：概念类的 id（ensureConceptTypes 之后必在）。 */
    public UUID entityTypeId(UUID kbId, String key) {
        UUID id = mappings.selectEntityTypeId(kbId, key);
        if (id == null) {
            throw new IllegalStateException("concept type '" + key + "' missing for kb " + kbId);
        }
        return id;
    }

    /**
     * mappings.rs:24-90 {@code propose} 的返回：(映射行 id, 写没写进待看)。
     */
    public record ProposedMapping(UUID id, boolean written) {
    }

    /**
     * mappings.rs:24-90 {@code propose}：探索提议一条口径（status=proposed）。
     * 只刷新 proposed 的行——已确认/已拒绝的那一行是人的表态，探索盖不掉。
     */
    public ProposedMapping propose(UUID kbId, UUID conceptId, String source, String tableName,
                                   String expr, String sql, String unit, String summary,
                                   boolean derived) {
        // DO UPDATE ... WHERE 不满足时 RETURNING 一行都不回——id 单独查（Rust 注释原由）
        UUID id = mappings.selectMappingRowId(kbId, conceptId, source);
        if (id == null) {
            id = tech.seexun.jknow.common.uuids.Uuids.nowV7();
        }
        int written = mappings.upsertProposedMapping(id, kbId, conceptId, source, tableName,
                expr, sql, unit, summary, derived);
        return new ProposedMapping(id, written > 0);
    }

    /**
     * relevant（mapping_index.rs:85-128 逐字语义）：q 空 → confirmed(k)；否则先补嵌
     * （失败只降级不拦路），两路召回（词面/向量）RRF 融合取前 k；两路都没命中 →
     * confirmed(k) 兜底。
     *
     * @param embedder 嵌入端（由 server 层从工作区设置构造）；null = 没配嵌入模型，
     *                 只剩词面一路（静默降级）
     * @param model    嵌入模型名（只比同一个模型嵌出来的口径）；embedder 非 null 时必给
     */
    public List<ConceptMapping> relevant(UUID kbId, String rawQuery, int k, Embedder embedder,
                                         String model) {
        return relevantInternal(kbId, rawQuery, k, embedder, model);
    }

    /** relevant 的单通道形（M1 兼容入口）：embedder 传 null = 没配嵌入模型，只剩词面一路。 */
    public List<ConceptMapping> relevant(UUID kbId, String rawQuery, int k) {
        return relevantInternal(kbId, rawQuery, k, null, null);
    }

    private List<ConceptMapping> relevantInternal(UUID kbId, String rawQuery, int k,
                                                  Embedder embedder, String model) {
        String query = rawQuery == null ? "" : rawQuery.trim();
        if (query.isEmpty()) {
            return mappings.selectConfirmed(kbId, k);
        }
        if (embedder != null && model != null && !model.isEmpty()) {
            try {
                refresh(kbId, embedder, model);
            } catch (Exception e) {
                // 补不上就用已有的向量和词面，不让一次嵌入失败把问数拖没（mapping_index.rs:97-100）
                log.warn("kb={} 口径向量没补上，按已有的检索: {}", kbId, e.getMessage());
            }
        }

        List<MappingText> texts = mappings.selectConfirmedTexts(kbId);
        List<String> lexical = new ArrayList<>();
        for (UUID id : lexicalRank(query, texts, RECALL_PER_CHANNEL)) {
            lexical.add(id.toString());
        }

        // 向量那一路的任何失败（嵌入请求也好、近邻查询也好）都只用词面——问数不该
        // 被模型或库的一时故障拖没（mapping_index.rs:107-113 对 vector_channel 的
        // match Err→warn→None，与分块检索 retrieval.rs 只放行嵌入失败不同，这里是整路）
        List<String> vector;
        try {
            vector = vectorChannel(kbId, query, embedder, model);
        } catch (Exception e) {
            log.warn("kb={} 口径向量检索失败，只用词面: {}", kbId, e.getMessage());
            vector = null;
        }

        // 送进 RRF 的列表：词面恒在第一位，向量有就在第二位（mapping_index.rs:117-121）
        List<List<String>> lists = new ArrayList<>();
        lists.add(lexical);
        if (vector != null) {
            lists.add(vector);
        }
        List<String> fused = Rrf.fuse(lists, k);
        List<UUID> ids = new ArrayList<>(fused.size());
        for (String s : fused) {
            try {
                ids.add(UUID.fromString(s));
            } catch (IllegalArgumentException notAUuid) {
                // 理论不可达（两路召回的 id 都来自本库行），防御对齐 Rust parse().ok()
            }
        }
        if (ids.isEmpty()) {
            // 两路都没命中（问题里一个词都对不上）：退回今天的行为，总比空着好
            return mappings.selectConfirmed(kbId, k);
        }
        return byIds(kbId, ids);
    }

    /**
     * mapping_index.rs:38-82 refresh：把还没嵌的确认口径补上。有上限（REFRESH_PER_TURN），
     * 一问补不完的下一问接着补。数量对不上就整批放弃：配对是按位置的，少一条就全体错位。
     */
    private int refresh(UUID kbId, Embedder client, String model) throws Exception {
        List<MappingText> stale = needingEmbedding(kbId, model, REFRESH_PER_TURN);
        if (stale.size() >= REFRESH_PER_TURN) {
            log.info("kb={} 口径向量这一问只补一部分，剩下的下一问接着补", kbId);
        }
        if (stale.isEmpty()) {
            return 0;
        }
        int done = 0;
        for (int from = 0; from < stale.size(); from += BATCH) {
            List<MappingText> batch = stale.subList(from, Math.min(from + BATCH, stale.size()));
            List<float[]> vectors = client.embed(
                    batch.stream().map(MappingText::embedText).toList());
            // 数量对不上就整批放弃：配对是按位置的，少一条就全体错位（分块那边同一句话）
            if (vectors == null || vectors.size() != batch.size()) {
                throw new IllegalStateException("嵌入返回 " + (vectors == null ? 0 : vectors.size())
                        + " 条，送去的是 " + batch.size() + " 条");
            }
            for (int i = 0; i < batch.size(); i++) {
                MappingText t = batch.get(i);
                mappings.updateMappingEmbedding(t.id(), VectorText.literal(vectors.get(i)), model,
                        t.embedText());
            }
            done += batch.size();
        }
        log.info("kb={} 口径向量已补齐 embedded={}", kbId, done);
        return done;
    }

    /**
     * mappings.rs:367-430 {@code needing_embedding}：确认口径里还没嵌、或嵌的时候文本/
     * 模型跟现在不一样的（#574），最多 {@code limit} 条。四查：第一查没嵌/模型不符（按 id
     * 序取上限），第二三四查同模型嵌过但<b>文本变了</b>的行——在 Java 里用
     * {@link MappingText#embedText} 比，不在 SQL 里重拼（#672 教训：trim 与 btrim 去的
     * 字符集不一样，SQL 拼出来的永远不等，每轮都判成陈的）。文本变化组按第二查的 id 序
     * 追加在第一查之后，上限在整个名单拼完之后裁（mappings.rs:429 truncate）。
     */
    public List<MappingText> needingEmbedding(UUID kbId, String model, long limit) {
        List<MappingText> out = new ArrayList<>(
                mappings.selectUnembeddedOrModelChanged(kbId, model, limit));
        List<UUID> matching = mappings.selectModelMatchingIds(kbId, model);
        if (!matching.isEmpty()) {
            Map<UUID, MappingText> current = new HashMap<>();
            for (MappingText t : mappings.selectMappingTextsByIds(matching)) {
                current.put(t.id(), t);
            }
            Map<UUID, String> was = new HashMap<>();
            for (EmbeddedText e : mappings.selectEmbeddedTexts(matching)) {
                was.put(e.id(), e.embeddedText());
            }
            for (UUID id : matching) {
                MappingText t = current.get(id);
                if (t != null && !java.util.Objects.equals(was.get(id), t.embedText())) {
                    out.add(t);
                }
            }
        }
        if (out.size() > limit) {
            out = out.subList(0, (int) Math.max(0, limit));
        }
        return out;
    }

    /**
     * mapping_index.rs:132-174 vector_channel：向量那一路。嵌入失败/空回复 → null（降级
     * 不是失败）；只比同一个模型嵌出来的行——模型名还是要读设置。<b>失败上抛、由
     * {@link #relevant} 整路降级</b>——mapping_index 的向量路任何错误都只是「这一路
     * 缺席」，与分块检索（retrieval.rs 只降级嵌入失败、库错误照抛）不同。
     */
    private List<String> vectorChannel(UUID kbId, String query, Embedder embedder, String model)
            throws Exception {
        if (embedder == null || model == null || model.isEmpty()) {
            return null;
        }
        List<float[]> vectors = embedder.embed(List.of(query));
        if (vectors == null || vectors.isEmpty()) {
            return null;
        }
        List<UUID> ids = mappings.selectMappingVectorSearch(kbId,
                VectorText.literal(vectors.get(0)), RECALL_PER_CHANNEL, model);
        List<String> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            out.add(id.toString());
        }
        return out;
    }

    /**
     * mappings.rs:497-516 {@code by_ids}：按 id 取回保持给定顺序——顺序是检索排出来的
     * （SQL IN 的行序不保证，HashMap 重排后按 ids 序还原）。
     */
    public List<ConceptMapping> byIds(UUID kbId, List<UUID> ids) {
        Map<UUID, ConceptMapping> byId = new HashMap<>();
        for (ConceptMapping m : mappings.selectMappingsByIds(kbId, ids)) {
            byId.put(m.id(), m);
        }
        List<ConceptMapping> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            ConceptMapping m = byId.remove(id);
            if (m != null) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * mapping_index.rs:220-233 lexical_rank：每条口径的文字命中了问题里几个词，多的在前；
     * 同分按 id 定序（两次查同一个问题拿到同一个顺序）；一个都没命中的不要。
     */
    public static List<UUID> lexicalRank(String query, List<MappingText> texts, int limit) {
        List<String> qs = tokens(query);
        if (qs.isEmpty()) {
            return List.of();
        }
        record Scored(int hits, UUID id) {
        }
        List<Scored> scored = new ArrayList<>();
        for (MappingText t : texts) {
            String hay = t.lexicalText().toLowerCase();
            int hits = 0;
            for (String q : qs) {
                if (hay.contains(q)) {
                    hits++;
                }
            }
            if (hits > 0) {
                scored.add(new Scored(hits, t.id()));
            }
        }
        scored.sort(Comparator.comparing(Scored::hits).reversed().thenComparing(Scored::id));
        List<UUID> out = new ArrayList<>(Math.min(limit, scored.size()));
        for (Scored s : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(s.id());
        }
        return out;
    }

    /**
     * mapping_index.rs:176-204 tokens：英文按空白与标点切、转小写；中文按两字（一个字太碎，
     * 三个字漏太多）；单个 ASCII 字符不算词（「a」「x」命中一切）。输出排序去重。
     */
    public static List<String> tokens(String s) {
        TreeSet<String> out = new TreeSet<>();
        StringBuilder word = new StringBuilder();
        Character prevCjk = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean cjk = (c >= '\u4E00' && c <= '\u9FFF') || (c >= '\u3400' && c <= '\u4DBF');
            if (cjk) {
                if (word.length() > 1) {
                    out.add(word.toString().toLowerCase());
                }
                word.setLength(0);
                if (prevCjk != null) {
                    out.add("" + prevCjk + c);
                }
                prevCjk = c;
                continue;
            }
            prevCjk = null;
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else {
                if (word.length() > 1) {
                    out.add(word.toString().toLowerCase());
                }
                word.setLength(0);
            }
        }
        if (word.length() > 1) {
            out.add(word.toString().toLowerCase());
        }
        return new ArrayList<>(out);
    }

    /** mapping_routes.rs:67-72 的四段（counts 三态 + last_run 可空）。 */
    public record MappingPage(List<ConceptMapping> items, long total, Counts counts,
                              ExplorationRun lastRun) {
    }

    public record Counts(long proposed, long confirmed, long rejected) {
    }
}
