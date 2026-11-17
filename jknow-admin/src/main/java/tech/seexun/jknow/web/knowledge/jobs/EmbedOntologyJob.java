package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.TypeToEmbed;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * {@code embed_ontology} 任务（main.rs:456-464 → ontology_index.rs:63-144 refresh 逐语义；
 * M3 任务 10 落真身，替换 JobDispatcher NOOP 桩）：本体向量补齐——把类、关系、属性的
 * {@code label + description} 嵌成向量。它服务的不是某一个功能，而是所有要问「这个说法
 * 对应本体里的哪一个」的地方：本体提案、谓词消解、类型消解、短语对齐短名单。检索出
 * 候选之后再交给模型裁决，提示词才与本体规模脱钩。
 *
 * <p><b>自愈，不挂钩子</b>。填充按「当时嵌的原文与模型名跟现在对不对得上」来判，所以
 * 任何改了描述的写入点都不需要记得通知这里——漏挂一个钩子会悄悄烂掉，而对比原文不会。
 *
 * <p><b>后台建，不卡请求</b>（main.rs:453-455）：一份 965 类的本体首次要嵌 2600 行，六到
 * 八分钟；放在交互请求里就是导入完之后第一个用到检索的人干等。
 *
 * <p>没配嵌入模型直接成功收尾（返回 0）——检索是增强，不该拦住没配模型的部署；一批
 * 失败不拖垮其余（缺的下一轮自愈），但只要有失败就抛错让任务按预算重试——「留给下一轮」
 * 得真有下一轮。
 */
@Component
public class EmbedOntologyJob {

    private static final Logger log = LoggerFactory.getLogger(EmbedOntologyJob.class);

    /**
     * ontology_index.rs:46 BATCH：一次送多少条去嵌入（64 是实测站得住的那个数——真实类
     * 文本平均 151 字符，改小之后请求数翻四倍、每个都付约 2.5 秒固定开销，吞吐反而掉半）。
     */
    static final int BATCH = 64;

    /**
     * ontology_index.rs:59 EMBED_JOBS：同时在飞的嵌入批数上限。<b>关键不是快，是不能把
     * 闸门占死</b>——这是个后台补齐任务，而嵌入模型的并发闸门是全局共享的（文档分块
     * 入库也要走它）。一次挂满会变成常驻深队列，5 篇文档全卡在 embedding 状态。4 是
     * 实测能跑满吞吐又留有余量的。
     */
    static final int EMBED_JOBS = 4;

    /**
     * ontology_index.rs:27 PER_KB：同一个库的补齐一次只跑一个（#282）。建库装三个包，
     * 每个包各排一个 embed_ontology 任务，三个任务同时起跑、同时读到同一批待嵌条目、
     * 各嵌一遍：嵌入调用翻三倍，而结果一模一样。所以按库排队：后到的等前一个跑完再进，
     * 进来时再查一遍待嵌，通常只剩前一个读完集合之后才装进来的那几条。<b>等而不是跳过
     * </b>——跳过的话，前一个没看见的条目要等下一次不知何时的补齐。表不清理：一个库
     * 一把锁，几十上百把不值得为它加一轮回收。
     */
    private static final Map<UUID, Object> PER_KB_LOCKS = new ConcurrentHashMap<>();

    /** 同库串行的那把锁（refreshes_of_one_base_take_turns 断言用）。 */
    static Object lockFor(UUID kbId) {
        return PER_KB_LOCKS.computeIfAbsent(kbId, k -> new Object());
    }

    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final OntologyService ontology;
    private final LlmSupport llm;

    public EmbedOntologyJob(KbMapper kbs, SettingsService settingsService,
                            OntologyService ontology, LlmSupport llm) {
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.ontology = ontology;
        this.llm = llm;
    }

    /**
     * ontology_index.rs:63-144 refresh 的分档版（{@code refresh_scoped}——类型消解的
     * preview 用：这一步用不到关系，而一份大本体的关系有一千多条）。only=null 全量。
     */
    public int refreshScoped(UUID kbId, OntologyService.TypeKind only) {
        Object lock = lockFor(kbId);
        synchronized (lock) {
            KbRow kb = kbs.selectById(kbId);
            if (kb == null) {
                return 0;
            }
            LlmSettingsRow settings = settingsService.get(kb.workspaceId());
            if (settings == null) {
                return 0;
            }
            EmbedClient client = LlmSupport.embedClient(settings);
            String model = settings.embedModel();
            if (client == null || model == null || model.isEmpty()) {
                return 0;
            }
            List<TypeToEmbed> stale = ontology.typesNeedingEmbedding(kbId, model, only);
            if (stale.isEmpty()) {
                return 0;
            }
            int done = 0;
            for (int from = 0; from < stale.size(); from += BATCH) {
                List<TypeToEmbed> batch = stale.subList(from, Math.min(from + BATCH, stale.size()));
                try {
                    done += embedBatch(kbId, settings, client, model, batch);
                } catch (Exception e) {
                    log.warn("kb={} 一批本体向量没嵌上，留给下一轮: {}", kbId, e.getMessage());
                }
            }
            return done;
        }
    }

    /**
     * ontology_index.rs:150-196 {@code nearest_for_each}：一批说法各自最像本体里的哪几
     * 个类/关系。<b>一次嵌完再逐个查库</b>，不是每个说法各发一次嵌入请求——本体提案
     * 一轮要处理十几到几十个说法，逐个发就是几十次往返。没配嵌入模型/嵌不上返回空
     * 清单（调用方按「词表按结构裁」退路走）。
     */
    public List<List<OntologyService.TypeCandidate>> nearestForEach(UUID kbId, List<String> queries,
                                                                    long limit, Target target) {
        if (queries.isEmpty()) {
            return List.of();
        }
        List<List<OntologyService.TypeCandidate>> empty = queries.stream()
                .map(q -> List.<OntologyService.TypeCandidate>of()).toList();
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            return empty;
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        if (settings == null) {
            return empty;
        }
        EmbedClient client = LlmSupport.embedClient(settings);
        if (client == null) {
            return empty;
        }
        List<float[]> vectors = new ArrayList<>(queries.size());
        try {
            for (int from = 0; from < queries.size(); from += BATCH) {
                List<String> batch = queries.subList(from, Math.min(from + BATCH, queries.size()));
                Semaphore permit = llm.acquirePermit(settings.embedBaseUrl(),
                        settings.embedModel());
                List<float[]> got;
                try {
                    got = client.embed(batch);
                } finally {
                    if (permit != null) {
                        permit.release();
                    }
                }
                if (got.size() != batch.size()) {
                    throw new IllegalStateException(
                            "嵌入返回 " + got.size() + " 条，送去的是 " + batch.size() + " 条");
                }
                vectors.addAll(got);
            }
        } catch (Exception e) {
            log.warn("kb={} 形状向量没算出来: {}", kbId, e.getMessage());
            return empty;
        }
        List<List<OntologyService.TypeCandidate>> out = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            out.add(switch (target) {
                case CLASS -> ontology.nearestEntityTypes(kbId, v, limit, false);
                case CLASS_LABEL -> ontology.nearestEntityTypes(kbId, v, limit, true);
                case PREDICATE -> ontology.nearestRelationTypes(kbId, v, limit, null);
            });
        }
        return out;
    }

    /**
     * 检索的是本体的哪一半（ontology_index.rs:186-196 Target）。必须分开：类进
     * entity_types、关系与属性进 relation_types，两边描述的是完全不同的东西。
     * {@code PREDICATE} 的 Rust 分档（attribute/relation/全部）由调用方自行用
     * {@link OntologyService#nearestRelationTypes} 的 onlyKind——本体代理的词表裁剪
     * 用全部。
     */
    public enum Target {
        /** 整段索引（label + 描述）。长画像走这一路。 */
        CLASS,
        /** 只有 label 的索引（entity_types.label_embedding）。短说法走这一路。 */
        CLASS_LABEL,
        /** 关系与属性都找（onlyKind=null）。 */
        PREDICATE
    }

    /** main.rs:456-464：载荷取 kb_id，本体向量补齐即成功（计数只进日志）。 */
    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        refresh(UUID.fromString(kbNode.asText()));
    }

    /**
     * ontology_index.rs:63-144 refresh：把这个库里陈掉的本体向量补齐，返回补了多少条。
     * 没配嵌入模型就直接返回 0；一批失败不拖垮其余，但失败数要出现在日志里，且只要有
     * 失败就抛错（任务按预算重试——从前这里照样报成功，没人再排它，缺向量的属性就一直
     * 缺，对齐的短名单退回全部候选，台子等到超时）。
     */
    public int refresh(UUID kbId) {
        // 同库串行（PER_KB）：锁在这个方法里一直握到返回
        Object lock = lockFor(kbId);
        synchronized (lock) {
            return refreshLocked(kbId);
        }
    }

    private int refreshLocked(UUID kbId) {
        // 库可能已经被删了——任务是导入时排的，中间隔着几分钟。这不是失败，是没事可做；
        // 当成错误的话它还要重试三次才放弃
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            return 0;
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        if (settings == null) {
            return 0;
        }
        EmbedClient client = LlmSupport.embedClient(settings);
        String model = settings.embedModel();
        if (client == null || model == null || model.isEmpty()) {
            return 0;
        }

        List<TypeToEmbed> stale = ontology.typesNeedingEmbedding(kbId, model, null);
        if (stale.isEmpty()) {
            return 0;
        }
        int done = 0;
        int failed = 0;
        // 批间并发（EMBED_JOBS 个同时在飞），一批一把许可（acquire_embed——全局闸门）
        ExecutorService pool = Executors.newFixedThreadPool(EMBED_JOBS, r -> {
            Thread t = new Thread(r, "utopia-embed-ontology");
            t.setDaemon(true);
            return t;
        });
        try {
            List<CompletableFuture<Integer>> futures = new ArrayList<>();
            for (int from = 0; from < stale.size(); from += BATCH) {
                List<TypeToEmbed> batch =
                        stale.subList(from, Math.min(from + BATCH, stale.size()));
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return embedBatch(kbId, settings, client, model, batch);
                    } catch (Exception e) {
                        // 一批失败不拖垮其余：这是补齐任务，缺的下一轮自愈（判据是
                        // 「当时嵌的原文对不对得上」，不是时间戳）
                        log.warn("kb={} 一批本体向量没嵌上，留给下一轮: {}", kbId, e.getMessage());
                        return -1;
                    }
                }, pool));
            }
            for (CompletableFuture<Integer> f : futures) {
                Integer n = f.join();
                if (n < 0) {
                    failed++;
                } else {
                    done += n;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        // 失败数要出现在这一行：从前它只报补齐了多少，24 批全挂的那次日志照样写着
        // 「已补齐」，看日志的人以为没事
        if (failed > 0) {
            log.warn("kb={} 本体向量补了一部分（done={} failed={}），其余留给下一轮", kbId,
                    done, failed);
            // 「留给下一轮」得真有下一轮：报错让任务按预算重试
            throw new IllegalStateException(
                    failed + " batches of ontology vectors failed; the job retries");
        }
        log.info("kb={} 本体向量已补齐 count={}", kbId, done);
        return done;
    }

    /** ontology_index.rs:105-121 一批的主体：嵌、验数、回写（三者一批一事务）。 */
    private int embedBatch(UUID kbId, LlmSettingsRow settings, EmbedClient client, String model,
                           List<TypeToEmbed> batch) throws Exception {
        Semaphore permit = llm.acquirePermit(settings.embedBaseUrl(), settings.embedModel());
        List<float[]> vectors;
        try {
            vectors = client.embed(batch.stream().map(TypeToEmbed::text).toList());
        } finally {
            if (permit != null) {
                permit.release();
            }
        }
        // 数量对不上就整批放弃：按位置配对，错位会把 person 的向量写到 organization 上，
        // 而这种错一旦落库就再也看不出来了
        if (vectors.size() != batch.size()) {
            throw new IllegalStateException(
                    "嵌入返回 " + vectors.size() + " 条，送去的是 " + batch.size() + " 条");
        }
        List<Map.Entry<TypeToEmbed, float[]>> items = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            items.add(new AbstractMap.SimpleEntry<>(batch.get(i), vectors.get(i)));
        }
        ontology.setTypeEmbeddings(model, items);
        return batch.size();
    }
}
