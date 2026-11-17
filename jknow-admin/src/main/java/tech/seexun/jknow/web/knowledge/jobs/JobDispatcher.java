package tech.seexun.jknow.web.knowledge.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.web.knowledge.extraction.AlignPhrasesJob;
import tech.seexun.jknow.web.knowledge.extraction.ExtractionJob;
import tech.seexun.jknow.web.knowledge.extraction.MemoryIngestJob;
import tech.seexun.jknow.web.knowledge.extraction.ResolveTimeJob;
import tech.seexun.jknow.web.knowledge.pipeline.ProcessDocumentJob;
import tech.seexun.jknow.web.knowledge.pipeline.SyncSourceJob;
import tech.seexun.jknow.store.entity.Job;

/**
 * 任务分发注册表（对齐旧仓库 crates/utopia-server/src/main.rs:392-680 dispatch 的
 * match 表 + :680 未知类型 bail）。
 *
 * <p><b>kind 注册表纪律</b>：各 kind 按里程碑逐一落真身（M5 任务 5 收口后 21 个 kind
 * 全部真身——NOOP 桩退役，见构造器逐条注记）。
 * <b>未知 kind 照 Rust bail</b>（{@code 未知任务类型: …}）：落 mark_failed 被人看见，
 * 而不是静默成功。
 */
@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);

    /** 单任务处理器（对齐 dispatch 每个 match 臂：异常=失败，正常返回=done）。 */
    @FunctionalInterface
    public interface JobHandler {

        void handle(Job job) throws Exception;
    }

    /** 占位处理器：记录日志即完成（真实现按里程碑替换注册表条目）。 */
    public static final JobHandler NOOP_STUB = job -> log.info(
            "NOOP_STUB 执行 kind={} id={}（执行器属后续里程碑，按成功收尾）", job.kind(), job.id());

    private final java.util.Map<String, JobHandler> registry = new java.util.concurrent.ConcurrentHashMap<>();

    public JobDispatcher(ProcessDocumentJob processDocument, SyncSourceJob syncSource,
                         MaterializeInferencesJob materializeInferences,
                         MaterializeTypedJob materializeTyped, ReadPhrasesJob readPhrases,
                         AlignTypesJob alignTypes, ExtractionJob extractDocument,
                         MemoryIngestJob memoryIngest, ResolveTimeJob resolveTime,
                         AlignPhrasesJob alignPhrases,
                         tech.seexun.jknow.web.knowledge.pipeline.HydrateRssEntryJob hydrateRssEntry,
                         EmbedOntologyJob embedOntology, BuildVectorIndexJob buildVectorIndex,
                         AdjudicateEntitiesJob adjudicateEntities, GovernJob govern,
                         ErrataReviewJob errataReview, ProposeOntologyJob proposeOntology,
                         ProposeQuestionsJob proposeQuestions, ResolveTypesJob resolveTypes,
                         ExploreMappingsJob exploreMappings) {
        // 真实现（main.rs:394-397 + pipeline.rs/ingest_sources.rs；materialize_inferences=main.rs:417-448）
        register("noop", job -> log.info("noop 任务执行成功 id={}", job.id()));
        register("process_document", processDocument::handle);
        register("sync_source", syncSource::handle);
        // 定时重推（0002 R1）：先 mark_inference_ran 再 reasoning::materialize——真身（任务 6）
        register("materialize_inferences", materializeInferences::handle);
        // 人定短语签名随判定排下的按绑定重算（main.rs:491-537；0051 试锁不等）——真身（任务 7）
        register("materialize_typed", materializeTyped::handle);
        // 已批准蕴含规则要的读数（main.rs:557-560 → implication.rs:234-316）——问模型段
        // 真身（M4 任务 5）：批 24/build_reading_messages/解析容错（null 落缓存、年份四位）
        // /resolveOrCreateNamed/批失败>0 bail/收尾必排 materialize_typed
        register("read_phrases", readPhrases::handle);
        // 类别词绑到类（main.rs:570-584 → type_alignment.rs:112-159）——不碰模型的三件
        // 事真身（任务 7）：embed_ontology 门/单库单份锁/通道未就绪失败；问模型随任务 8
        register("align_types", alignTypes::handle);
        // full_new_items 的单条补全真身（任务 9：Content 档抓取 + 质量门 + 发布闸事务）
        register("hydrate_rss_entry", hydrateRssEntry::handle);
        // 抽取执行器真身（任务 8：epoch 所有权 + 容错解析 + LLM 适配）
        register("extract_document", extractDocument::handle);
        // 记忆摄入（pipeline.rs:263-336：episode 补嵌入/重建索引/触发增量抽取）
        register("memory_ingest", memoryIngest::handle);
        // 时间提及按文档解析（0045：模型读代码算；推送陈述的写明值代码直读 #1089）
        register("resolve_time", resolveTime::handle);
        // 勘误复审 agent（errata.rs 399 行）——真身（M4 任务 5）：文档一份一份看（20/任务），
        // 有旗全要无旗补 10，属性表按文档嵌入裁（nearest 24 ∪ 候选已用，无向量回全量），
        // 两票协议（只认显式 not_stated，没进集合的撤改判 keep）、每票 record（keep 也记
        // ——没行等于没看过）、finish_run usage、单文档失败计数最后 bail、applied/held>0
        // 记 errata.reviewed + emit、due 有余再排
        register("errata_review", errataReview::handle);
        // 短语按签名绑到属性（任务 8：两票一致才绑；短名单/提规则按退化路走）
        register("align_phrases", alignPhrases::handle);
        // 本体向量补齐（main.rs:456-464 → ontology_index.rs:63-144）——真身（任务 10）：
        // 它收尾后 align_types/align_phrases 的 pendingForKb("embed_ontology") 门自然打通
        register("embed_ontology", embedOntology::handle);
        // 向量索引构建（main.rs:467-499 → vector_index.rs:243-301）——真身（任务 10）：
        // 事务外 CONCURRENTLY、会话锁互斥、维度超限 Terminal
        register("build_vector_index", buildVectorIndex::handle);
        // 实体消解攒批裁决 agent（adjudication.rs 566 行）——真身（M4 任务 3）：PER_KB
        // 进程内串行（等而不跳）、resolution_verdicts 缓存（先例进键）、apply_verdict
        // 三档（auto_merged/kept_apart/escalate_unsure 族）；第二眼经 SecondLookPort，
        // 任务 4 的 govern 真身注册 bean 即自动接管
        register("adjudicate_entities", adjudicateEntities::handle);
        // 治理任务（governance.rs 1042 行）——真身（M4 任务 4）：BASE_TRY_LOCK 单任务
        // （等而不跳：抢不到锁 60s 后再排一个）/rounds 每轮看开关/queue 挑簇 3×12 互不
        // 共享/precedents→settled_by_people 快路（人裁过照人办不问模型）/攒批+第二层
        // 带五工具循环/apply 四档（governed|conf 合并、kept、闸门拦、proposed）/
        // LOOP_DAILY_CALLS 预算；GovernJob 即 SecondLookPort 真身（裁决器懒取自动接管）
        register("govern", govern::handle);
        // 探索跑批（main.rs:406-416 → mappings.rs 623 行）——真身（M5 任务 5）：读挂载
        // 源 schema（12k 跨源预算）→ LLM 写数据描述+问题 → 提议 Metric/Dimension 口径
        // → drop 六码分类账；accepted=0 告警 MAPPING_EXPLORATION_EMPTY + emit_review。
        // 无探索游标，纯单轮 LLM 辅助
        register("explore_mappings", exploreMappings::handle);
        // 类型消解自动一轮（main.rs:607-616 → type_resolution.rs:434-482）——真身
        // （M5 任务 5）：kb.auto_type_resolution 开关；10 轮×一次模型×60 实体；候选
        // 双查询名字单独发+近邻投票；分档看跨没跨分类轴（≥0.85 且不跨轴→自动 retype，
        // 否则留候选等人）；unattended 打 type_resolved_at 防同批回流（连没给候选的
        // 也算看过）；审计 ontology.types_resolved{via:job}
        register("resolve_types", resolveTypes::handle);
        // 本体提案 agent（main.rs:634-643 → ontology_agent.rs:59-720）——真身（M5 任务
        // 5）：advisory 锁 hashtext('propose_ontology') 同库一份；词表指纹 basis（看过
        // 没提/答已有的词表不变不再送，declined 的陈述数翻倍再送）；输入=对齐判
        // none/undecided 的开放形状+未归类词+度数实体；词表按批裁（GLOSSARY_PER_SHAPE=10）
        // ；跨批 Merged 并形状；落 ontology_proposals(status=open)。对齐收尾的入队点在
        // AlignPhrasesJob 尾部（TRIGGER_UNBOUND=20）
        register("propose_ontology", proposeOntology::handle);
        // 胜任问题提案 agent（main.rs:645-652 → ontology_agent.rs:1171-1338）——真身
        // （M5 任务 5）：TOP_SIGNATURES/KIND_WORDS/ENTITIES=40/20/20 三样素材，全空
        // 返回 0；既有非 rejected 去重；needs 只收本体里真有的键；origin=agent
        // status=proposed。本体代理发现库里一条问题都没有时也走这里开场
        register("propose_questions", proposeQuestions::handle);
    }

    /** 注册/替换一个 kind 的处理器（后续里程碑以真实现替换占位）。 */
    public final void register(String kind, JobHandler handler) {
        registry.put(kind, handler);
    }

    /** 分发一条任务；未知 kind 照 Rust bail（main.rs:680 文案逐字）。 */
    public void dispatch(Job job) throws Exception {
        JobHandler handler = registry.get(job.kind());
        if (handler == null) {
            throw new IllegalStateException("未知任务类型: " + job.kind());
        }
        handler.handle(job);
    }
}
