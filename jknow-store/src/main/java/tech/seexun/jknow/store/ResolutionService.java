package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.Resolution.TypeDrift;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.NameVectorsMapper.Near;
import tech.seexun.jknow.store.mapper.ResolutionMapper;
import tech.seexun.jknow.store.mapper.ResolutionMapper.Candidate;
import tech.seexun.jknow.store.mapper.ResolutionMapper.ContainRow;
import tech.seexun.jknow.store.mapper.ResolutionMapper.CrossCandidate;
import tech.seexun.jknow.store.mapper.ResolutionMapper.EntityFull;
import tech.seexun.jknow.store.mapper.ResolutionMapper.MergeRow;
import tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText;
import tech.seexun.jknow.store.mapper.ReviewMapper.ReviewRow;
import tech.seexun.jknow.store.temporal.Timeline;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 实体消解 v2 与合并执行（resolution.rs 全量对等）——同名≠同人（流程图与三次实测修
 * 洞见 docs/pipeline.md 第二节）。
 *
 * <p><b>漏斗</b>：名字候选召回（免费，含泛用后缀词干互推）→ 画像向量相似度分层（毫秒，
 * 复用摄入阶段的 chunk embedding）→ 灰区新建实体 + 疑似重复审核项（<b>宁分勿合</b>），
 * LLM 攒批裁决在独立后台任务里跑（M4），人工终审兜底。LLM 永远不在抽取写入的关键
 * 路径上。
 *
 * <p><b>类型漂移</b>：同名在同类型下无候选时再查其它类型（同一团队被抽成 organization/
 * project/concept 是常态），按类型对的互斥强度分流——concept 兜底型当召回候选走画像
 * 分层，易混具体类型照建实体但入队审核对，硬互斥（person vs organization）完全分开。
 *
 * <p><b>事务口径</b>：Rust 里各查询直接跑在池上（各自即事务）、create_entity 与
 * merge/revert 各自 begin/commit——Java 侧以 {@link TransactionTemplate}
 * （REQUIRES_NEW）对等；拿 tx 的段（merge 十步/撤回）内部不再另起事务。
 */
@Service
public class ResolutionService {

    private static final Logger log = LoggerFactory.getLogger(ResolutionService.class);

    private final ResolutionMapper mapper;
    private final NamesService names;
    private final NameVectorsService nameVectors;
    private final GovernanceService governanceReads;
    private final TemporalService temporal;
    private final ReviewService reviews;
    private final DocumentsWriteMapper documents;
    private final JobsService jobs;
    private final VectorIndexService vectorIndex;
    private final DataSource dataSource;
    private final TransactionTemplate newTx;
    /**
     * signature_breaks 钩子（#196：合并是第三条改写事实的路）——任务 5 接线真身：
     * 对搬动过的事实查 domain/range（递归 anc CTE 展开祖先），违规进 axiom_violations。
     */
    private final SignatureBreaksHook signatureBreaks;

    public ResolutionService(ResolutionMapper mapper, NamesService names,
                             NameVectorsService nameVectors, GovernanceService governanceReads,
                             TemporalService temporal, ReviewService reviews,
                             DocumentsWriteMapper documents, JobsService jobs,
                             ReasoningService reasoning, VectorIndexService vectorIndex,
                             DataSource dataSource,
                             PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.names = names;
        this.nameVectors = nameVectors;
        this.governanceReads = governanceReads;
        this.temporal = temporal;
        this.reviews = reviews;
        this.documents = documents;
        this.jobs = jobs;
        this.vectorIndex = vectorIndex;
        this.dataSource = dataSource;
        this.newTx = new TransactionTemplate(txManager);
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        // resolution.rs 合并十步第 10 步（事务外）：signature_breaks(only=moved)
        // + record_signature_breaks——抽取写入时的把关挡不住合并（#196），这里补量
        this.signatureBreaks = (kbId, movedFactIds) -> {
            List<UUID> breaks = reasoning.signatureBreaks(kbId, movedFactIds);
            reasoning.recordSignatureBreaks(kbId, breaks);
        };
    }

    /**
     * 合并后对搬动过的事实报签名违规（#196：合并是第三条改写事实的路）。构造处接的
     * 就是真身（signature_breaks + record_signature_breaks）；接口保留是为了测试
     * 与后续替换点。
     */
    public interface SignatureBreaksHook {

        void reportMoved(UUID kbId, List<UUID> movedFactIds);
    }

    /** resolution.rs:199-206 消解结果：mention 落到了哪个实体；附带待入队的审核对。 */
    public record ResolutionResult(UUID entityId, boolean created, List<ReviewRequest> reviews) {
    }

    /** resolution.rs:208-217 待入队的审核对：entityId vs otherId。 */
    public record ReviewRequest(UUID otherId, float score, String reason, ReviewStage stage) {
    }

    /**
     * resolution.rs:219-234 审核项由谁消费。普通画像灰区先交给批量裁决器（M4）；
     * 抽取器在同一回复里明确拆出的同名实体只有人能判断，不能让几乎相同的画像触发
     * 自动合并。
     */
    public enum ReviewStage {
        ADJUDICATING("adjudicating"), HUMAN("human");

        private final String stage;

        ReviewStage(String stage) {
            this.stage = stage;
        }

        public String asString() {
            return stage;
        }
    }

    /** Rust format! 的 {sim:.2}（Locale.ROOT 防本地化小数点）。 */
    static String sim2(float sim) {
        return String.format(Locale.ROOT, "%.2f", sim);
    }

    // ====================================================================
    // resolve_mention（resolution.rs:247-268）＝通道1 + 通道2 + 已分记忆

    /**
     * resolution.rs:247-268 {@code resolve_mention}：召回走两条通道（0041 决定 3）——
     * 名字字面相等（通道 1）和名字向量最近邻（通道 2，给了 nameVector 才走）。
     *
     * <p><b>通道 2 只提议，不决定。</b>它召回到的实体这一刀不参与归并——决定该由证据
     * 来做，那是 0041 的第 3 刀——只给裁决器排一对（{@code name_vector|<余弦>}），让它
     * 拿两份画像和先例去判是不是一个。这一刀加的是「多问一句」，不是「多合一次」：
     * 错合是静默的、要人回头拆，多问只是贵一点。
     */
    public ResolutionResult resolveMention(UUID kbId, UUID typeId, String rawName, float[] context,
                                           float[] nameVector, String text, List<UUID> exclude) {
        ResolutionResult r = resolveByName(kbId, typeId, rawName, context, text, exclude);
        if (nameVector != null) {
            proposeByNameVector(kbId, typeId, rawName, nameVector, exclude, r);
        }
        // 判过「不是一个」的对不再排。新建的实体没有过去，没东西要排的也不用问
        if (!r.created() && !r.reviews().isEmpty()) {
            Set<UUID> kept = keptApartFrom(kbId, r.entityId());
            r.reviews().removeIf(v -> kept.contains(v.otherId()));
        }
        return r;
    }

    /**
     * extraction.rs:202-241 resolve_uncached + create_namesake_reviews 的入队编排
     * （任务 8 抽取走这里）：审核对落库，回两个治理编排旗标——只有批量可裁的
     * （Adjudicating）才唤醒裁决任务，纯人工审核对（Human）不该唤醒裁决器。
     */
    public QueuedResolution resolveAndQueue(UUID kbId, UUID typeId, String rawName,
                                            float[] context, float[] nameVector, String text,
                                            List<UUID> exclude, List<UUID> namesakeOthers) {
        ResolutionResult r = resolveMention(kbId, typeId, rawName, context, nameVector, text,
                exclude);
        boolean needsAdjudication = false;
        for (ReviewRequest review : r.reviews()) {
            createReview(kbId, r.entityId(), review.otherId(), review.score(), review.reason(),
                    review.stage());
            if (review.stage() == ReviewStage.ADJUDICATING) {
                needsAdjudication = true;
            }
        }
        // 同一回复里拆出的同名实体（namesake）只有人分得开：1.0 分、Human 阶段
        boolean humanReviewsFound = false;
        for (UUID otherId : namesakeOthers) {
            if (otherId.equals(r.entityId())) {
                continue;
            }
            createReview(kbId, r.entityId(), otherId, 1.0f, "namesake", ReviewStage.HUMAN);
            humanReviewsFound = true;
        }
        return new QueuedResolution(r.entityId(), r.created(), needsAdjudication,
                humanReviewsFound);
    }

    /** 抽取侧要的两个治理编排旗标（extraction_open.rs:1173-1189 的判定输入）。 */
    public record QueuedResolution(UUID entityId, boolean created, boolean needsAdjudication,
                                   boolean humanReviewsFound) {
    }

    /**
     * resolution.rs:279-290 {@code kept_apart_from}：跟这个实体判过「不是一个」的那些。
     * <b>每次提到都会走到召回</b>，而审核表的唯一索引只管 pending 的行——判完的一对
     * 下次被提到就又是一条新的 pending（#1104）。分开这个决定要记得住。人仍然可以在
     * 审核页把分开过的一对合并：这里拦的是自动重提，不是合并本身。
     */
    Set<UUID> keptApartFrom(UUID kbId, UUID entityId) {
        return new HashSet<>(mapper.selectKeptApart(kbId, entityId));
    }

    /**
     * resolution.rs:293-346 通道 2：mention 名字向量的近邻，各排一对（规矩见
     * {@link #resolveMention}）。同一个字面名字不走通道 2——那是通道 1 的地盘，再排一对
     * 等于让裁决器复议一个刚做过的决定；大类对不上的也不提议（海探1 是设备，不会是
     * 一个人）；只差一个数的名字向量上几乎重合，却几乎从不是同一个（numbers_differ）。
     */
    private void proposeByNameVector(UUID kbId, UUID typeId, String rawName, float[] query,
                                     List<UUID> exclude, ResolutionResult r) {
        String mentionName = Names.normalizeName(rawName).toLowerCase(Locale.ROOT);
        GovernanceService.TypeFamily mentionFamily = null;
        if (typeId != null) {
            String label = mapper.selectTypeLabel(typeId);
            mentionFamily = label != null ? GovernanceService.typeFamily(label) : null;
        }
        Set<UUID> seen = new HashSet<>();
        for (ReviewRequest v : r.reviews()) {
            seen.add(v.otherId());
        }
        seen.add(r.entityId());
        seen.addAll(exclude);
        List<ReviewRequest> extra = new ArrayList<>();
        for (Near near : nameVectors.nearest(kbId, query, NameVectorsService.TOP_K)) {
            if (near.similarity() < NameVectorsService.SIM_FLOOR) {
                break; // 降序：后面的更远
            }
            if (near.name().toLowerCase(Locale.ROOT).equals(mentionName) || !seen.add(near.entityId())) {
                continue;
            }
            if (Names.numbersDiffer(rawName, near.name())) {
                continue;
            }
            GovernanceService.TypeFamily nearFamily = near.typeLabel() != null
                    ? GovernanceService.typeFamily(near.typeLabel()) : null;
            if (mentionFamily != null && nearFamily != null && mentionFamily != nearFamily) {
                continue;
            }
            extra.add(new ReviewRequest(near.entityId(), near.similarity(),
                    "name_vector|" + sim2(near.similarity()), ReviewStage.ADJUDICATING));
        }
        r.reviews().addAll(extra);
    }

    // ====================================================================
    // 通道 1（resolution.rs:361-585）

    /**
     * resolution.rs:361-585 {@code resolve_by_name}：名字字面相等的候选，按画像分层归并
     * 或新建。context 为 mention 所在分块的向量（无 embedding 模型时 null，退化为 v1
     * 行为：同名归并到事实最多的候选）。
     */
    private ResolutionResult resolveByName(UUID kbId, UUID typeId, String rawName,
                                           float[] context, String text, List<UUID> exclude) {
        String name = Names.normalizeName(rawName);
        // 召回键 = 本名 + 泛用后缀词干及其增广（"星尘"↔"星尘项目"互为候选）。
        // 只扩召回，归并与否仍由下方画像相似度分层定夺
        List<String> keys = Resolution.recallKeys(name);
        List<Candidate> all = mapper.selectCandidates(kbId, typeId, keys);
        List<Candidate> candidates = all.stream().filter(c -> !exclude.contains(c.id())).toList();

        if (candidates.isEmpty()) {
            // 同类型无候选 ≠ 新名字：类型标签会漂（同一团队被抽成 organization/project/
            // concept），先查其它类型下的同名实体，按类型对的互斥强度分流
            return resolveTypeDrift(kbId, typeId, name, keys, context, exclude);
        }

        if (context == null) {
            // 无向量可比：v1 兼容——归并到事实最多的同名候选（Rust max_by_key(degree)
            // 平局取最后一个最大者：循环用 >= 与之对齐）
            Candidate best = candidates.get(0);
            for (Candidate c : candidates) {
                if (c.degree() >= best.degree()) {
                    best = c;
                }
            }
            mapper.touchEntity(best.id());
            return new ResolutionResult(best.id(), false, new ArrayList<>());
        }

        // 这一次调用绝不能归上去的全部：调用方点名排除的（exclude），加上这一轮看过、
        // 并且会被判「不是同一个」的同名候选。下面无论走哪条分支决定新建，都要把这份
        // 名单递给 createEntity——锁里那条回捞按名字捞，不给名单就会把它们捞回来
        List<UUID> weighed = new ArrayList<>(exclude);
        for (Candidate c : candidates) {
            weighed.add(c.id());
        }

        // 有画像的候选算相似度；无画像（历史数据/无 embedding 期创建）单独归类
        List<Map.Entry<Candidate, Float>> scored = new ArrayList<>();
        Candidate unprofiled = null;
        for (Candidate c : candidates) {
            float[] profile = c.profile();
            Float sim = profile != null ? Resolution.cosine(profile, context) : null;
            if (sim != null) {
                scored.add(Map.entry(c, sim));
            } else if (unprofiled == null || c.degree() > unprofiled.degree()) {
                unprofiled = c;
            }
        }
        // 最高分候选：并列时保留先遇到的那个（与旧的 sim > s 严格大于一致）
        Map.Entry<Candidate, Float> bestScored = null;
        for (Map.Entry<Candidate, Float> e : scored) {
            if (bestScored == null || e.getValue() > bestScored.getValue()) {
                bestScored = e;
            }
        }

        if (bestScored != null && bestScored.getValue() >= Resolution.SIM_ATTACH) {
            Candidate best = bestScored.getKey();
            float sim = bestScored.getValue();
            // 同名并列的灰区：另有一个同名候选的分数贴着最高分（差 ≤ SIM_TIE_MARGIN），
            // 画像分不出谁是谁——attach 到分高的那个只是候选顺序掷出的硬币（#270）。
            // 宁分勿合：不 attach，新建实体，对并列的两个候选各入一条人工审核对。
            // 分数无论多高都拦：高分并列正是静默错并的危险区，不能因为「够像」就放行
            Map.Entry<Candidate, Float> runnerEntry = null;
            for (Map.Entry<Candidate, Float> e : scored) {
                if (e.getKey().id().equals(best.id())) {
                    continue;
                }
                // 同名比较要与召回一致地忽略大小写：召回用 SQL lower()，「Zhang Wei」
                // 与「zhang wei」本就是一对同名候选，不能漏
                if (!e.getKey().canonicalName().toLowerCase(Locale.ROOT)
                        .equals(best.canonicalName().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                if (sim - e.getValue() > Resolution.SIM_TIE_MARGIN) {
                    continue;
                }
                if (runnerEntry == null || e.getValue() > runnerEntry.getValue()) {
                    runnerEntry = e;
                }
            }
            if (runnerEntry != null) {
                // 画像掷硬币之前，先问事实（#331）：这句话里提到了其中一个人的部门或
                // 职位吗？提到了就不是硬币，是线索
                if (text != null) {
                    Candidate corroborated = corroboratingCandidate(kbId,
                            List.of(best, runnerEntry.getKey()), text);
                    if (corroborated != null) {
                        updateProfile(corroborated.id(), corroborated.profileN(), context);
                        return new ResolutionResult(corroborated.id(), false, new ArrayList<>());
                    }
                }
                CreateOutcome outcome = createEntity(kbId, typeId, name, context, weighed);
                if (!outcome.created()) {
                    // 并行的另一份文档刚建好它：用它的，审核对也是它排的
                    return new ResolutionResult(outcome.id(), false, new ArrayList<>());
                }
                refreshDisambiguators(kbId, name);
                List<ReviewRequest> tied = new ArrayList<>(List.of(
                        new ReviewRequest(best.id(), sim, "namesake_tie|" + sim2(sim),
                                ReviewStage.HUMAN),
                        new ReviewRequest(runnerEntry.getKey().id(), runnerEntry.getValue(),
                                "namesake_tie|" + sim2(runnerEntry.getValue()), ReviewStage.HUMAN)));
                return new ResolutionResult(outcome.id(), true, tied);
            }
            updateProfile(best.id(), best.profileN(), context);
            return new ResolutionResult(best.id(), false, new ArrayList<>());
        }
        if (unprofiled != null) {
            // 无画像候选无从判别：v1 兼容归并，并用本次上下文初始化画像
            updateProfile(unprofiled.id(), unprofiled.profileN(), context);
            return new ResolutionResult(unprofiled.id(), false, new ArrayList<>());
        }

        // 走到这里：所有候选都有画像且最高分 < ATTACH → 新建实体（同名不同人）。
        // 但先问一次事实（#331）：灰区的意思是"向量说不好"，不是"一定是新人"——
        // 这句话里若正好写着某一个候选的部门或职位，那比余弦值可靠。
        // 只认灰区（≥ SIM_NEW）：分数低于它的候选连审核对都不配入队，拿一条旁证把它
        // 拉成同一人，等于绕过阈值而不是补充它
        if (text != null) {
            List<Candidate> inGrey = scored.stream()
                    .filter(e -> e.getValue() >= Resolution.SIM_NEW)
                    .map(Map.Entry::getKey).toList();
            Candidate corroborated = corroboratingCandidate(kbId, inGrey, text);
            if (corroborated != null) {
                updateProfile(corroborated.id(), corroborated.profileN(), context);
                return new ResolutionResult(corroborated.id(), false, new ArrayList<>());
            }
        }
        CreateOutcome outcome = createEntity(kbId, typeId, name, context, weighed);
        if (!outcome.created()) {
            return new ResolutionResult(outcome.id(), false, new ArrayList<>());
        }
        refreshDisambiguators(kbId, name);
        List<ReviewRequest> out = new ArrayList<>();
        if (bestScored != null && bestScored.getValue() >= Resolution.SIM_NEW) {
            out.add(new ReviewRequest(bestScored.getKey().id(), bestScored.getValue(),
                    "ambiguous_name|" + sim2(bestScored.getValue()), ReviewStage.ADJUDICATING));
        }
        // 名字互相包含的既有实体：等值召回看不见它们（前缀枚举不完），于是简称会静默
        // 变成第二个实体。只入队，不合并
        out.addAll(containmentReviews(kbId, typeId, name, outcome.id(), context));
        return new ResolutionResult(outcome.id(), true, out);
    }

    /**
     * resolution.rs:603-669 {@code corroborating_candidate}：<b>事实旁证</b>——这次提及
     * 的原文里，出现了某个候选的消歧宾语吗（#331）。
     *
     * <p><b>证据只往一个方向走。</b>一致是"同一人"的弱证据；不一致<b>什么都不是</b>——
     * 在 Acme 上班又在 Zenith 兼职的人有两个 works_for 值，他仍是一个人。所以这里只回答
     * "哪个候选被原文提到了"，永远不回答"哪个候选被排除了"。
     *
     * <p><b>恰好一个候选命中才算数。</b>两个都命中说明这条线索在他们之间也分不开。
     */
    Candidate corroboratingCandidate(UUID kbId, List<Candidate> candidates, String text) {
        // 单个候选也算数：「其他候选都没命中」在只有一个候选时天然成立
        if (candidates.isEmpty() || text.strip().isEmpty()) {
            return null;
        }
        List<UUID> ids = candidates.stream().map(Candidate::id).toList();
        String haystack = text.toLowerCase(Locale.ROOT);
        Candidate hit = null;
        for (var row : mapper.selectCorroboratingObjects(kbId, ids,
                Resolution.CORROBORATION_OBJECTS)) {
            String needle = row.name() == null ? "" : row.name().strip().toLowerCase(Locale.ROOT);
            if (needle.codePointCount(0, needle.length()) < Resolution.MIN_CORROBORATION_CHARS
                    || !haystack.contains(needle)) {
                continue;
            }
            Candidate c = null;
            for (Candidate x : candidates) {
                if (x.id().equals(row.subjectId())) {
                    c = x;
                    break;
                }
            }
            if (c == null) {
                continue;
            }
            if (hit == null) {
                hit = c;
            } else if (!hit.id().equals(c.id())) {
                // 命中了第二个候选：这条线索分不开他们
                return null;
            }
            // 同一个候选的第二条宾语也命中：仍是同一个候选，不算分歧
        }
        return hit;
    }

    /**
     * resolution.rs:716-814 {@code containment_reviews}：新建实体之后，找出名字互相包含
     * 的既有实体，作为审阅候选。<b>只出候选，永不自动合并</b>——华瑞集团技术中心与
     * 星云科技技术中心都含「技术中心」，同一篇文档里上下文相似度很容易过线，而它们
     * 是两个部门。
     */
    List<ReviewRequest> containmentReviews(UUID kbId, UUID typeId, String name, UUID newId,
                                           float[] ctx) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.codePointCount(0, lower.length()) < Resolution.MIN_CONTAIN_CHARS) {
            return List.of();
        }
        // 实体可能还没判出类型（0009），那时没有 key 可查
        String mentionKey = typeId != null ? mapper.selectTypeKey(typeId) : null;
        // 本体声明了跟这个类互斥的那些类（含继承），一次取出，逐行比 id
        Set<UUID> disjoint = new HashSet<>(mapper.selectDeclaredDisjointFrom(kbId, typeId));
        List<ReviewRequest> out = new ArrayList<>();
        for (ContainRow row : mapper.selectContainRows(kbId, newId, lower,
                Resolution.MIN_CONTAIN_CHARS, Resolution.CONTAIN_SCAN_LIMIT)) {
            // 哪些类型对可能指同一个东西，既有规则已经想清楚了，别另发明一套：本体声明
            // 互斥的永不合并，person vs organization 永不合并，concept 兜底与谁都可能是
            // 一个。numbers_differ 筛在 take 之前：带别的数的兄弟不该占掉真候选的名额
            if (row.typeId() != null && disjoint.contains(row.typeId())) {
                continue;
            }
            if (Resolution.classifyTypeDrift(mentionKey, row.typeKey()) == TypeDrift.DISJOINT) {
                continue;
            }
            if (Names.numbersDiffer(name, row.canonicalName())) {
                continue;
            }
            if (out.size() >= Resolution.MAX_CONTAIN_REVIEWS) {
                break;
            }
            // 分数只是给队列排序用的参考，不参与是否合并的判断——那个判断本来就不在
            // 这条路上
            float[] profile = row.profile();
            Float sim = ctx != null && profile != null ? Resolution.cosine(profile, ctx) : null;
            out.add(new ReviewRequest(row.id(), sim != null ? sim : 0f,
                    "contains|" + row.canonicalName(), ReviewStage.ADJUDICATING));
        }
        return out;
    }

    /**
     * resolution.rs:942-1098 {@code resolve_type_drift}：同类型召回为空时的跨类型处置。
     * concept 兜底型候选跑既有画像分层：高相似直接 ATTACH（漂移的召回修复，concept 侧
     * 类型升格为具体类型），灰区/无从判别 → 宁分勿合新建 + 审核对；易混具体类型一律
     * 新建 + 审核对（同名 + 类型摇摆本身就是信号，不设相似度门槛）；硬互斥忽略。
     */
    private ResolutionResult resolveTypeDrift(UUID kbId, UUID typeId, String name,
                                              List<String> keys, float[] context,
                                              List<UUID> exclude) {
        // 这一侧也可能还没判出类型（0009），那时没有 key 可查
        String mentionKey = typeId != null ? mapper.selectTypeKey(typeId) : null;
        List<CrossCandidate> cross = mapper.selectCrossCandidates(kbId, typeId, keys).stream()
                .filter(c -> !exclude.contains(c.id())).toList();

        // 本体声明了互斥的类，一次取出（含继承）。声明优先于下面所有启发式
        Set<UUID> disjoint = new HashSet<>(mapper.selectDeclaredDisjointFrom(kbId, typeId));
        List<CrossCandidate> recallCands = new ArrayList<>();
        List<CrossCandidate> reviewCands = new ArrayList<>();
        for (CrossCandidate c : cross) {
            TypeDrift drift = Resolution.classifyTypeDrift(mentionKey, c.typeKey());
            if (c.typeId() != null && disjoint.contains(c.typeId())) {
                // 本体说这两类互斥：哪怕硬表说易混、类层级说一家，也分开。
                // 声明是人写下的判断，启发式只是没声明时的猜测
                drift = TypeDrift.DISJOINT;
            } else if (drift == TypeDrift.DISJOINT && typeId != null && c.typeId() != null) {
                // 硬表判不上的，再看类层级：同一支系下的同名当易混，进审阅队列
                if (Boolean.TRUE.equals(mapper.selectTypesAreKin(typeId, c.typeId()))) {
                    drift = TypeDrift.REVIEW;
                }
            }
            switch (drift) {
                case RECALL -> recallCands.add(c);
                case REVIEW -> reviewCands.add(c);
                case DISJOINT -> {
                }
            }
        }

        if (context != null) {
            // Rust max_by(total_cmp)：平局取最后一个最大者——循环用 >= 对齐
            CrossCandidate bestCand = null;
            float bestSim = Float.NEGATIVE_INFINITY;
            for (CrossCandidate c : recallCands) {
                float[] profile = c.profile();
                if (profile == null) {
                    continue;
                }
                Float sim = Resolution.cosine(profile, context);
                if (sim != null && (bestCand == null || sim >= bestSim)) {
                    bestCand = c;
                    bestSim = sim;
                }
            }
            if (bestCand != null && bestSim >= Resolution.SIM_ATTACH) {
                updateProfile(bestCand.id(), bestCand.profileN(), context);
                // 候选还没判出类型而这次抽取判出来了 → 升格。不是合并（没有第二个
                // 实体），不入 entity_merges；本体页可手工改回。
                // 人说过的「没有类型」不算「还没判出来」（0009 之后两者都是 NULL，
                // 只看 type_key 分不出）
                if (bestCand.typeKey() == null && !"human".equals(bestCand.typeSource())
                        && typeId != null) {
                    mapper.upgradeEntityType(bestCand.id(), typeId);
                    // 类型标签兜底的消歧后缀可能已过时
                    refreshDisambiguators(kbId, bestCand.canonicalName());
                }
                // mention 已定居到召回实体；同名易混类型实体的疑点仍在 → 照常入队
                List<ReviewRequest> out = confusableReviews(mentionKey, reviewCands, context);
                truncate(out, Resolution.MAX_DRIFT_REVIEWS);
                return new ResolutionResult(bestCand.id(), false, out);
            }
        }

        // 同上：点名排除的，加上跨类型同名里掂量过的
        List<UUID> weighed = new ArrayList<>(exclude);
        for (CrossCandidate c : cross) {
            weighed.add(c.id());
        }
        CreateOutcome outcome = createEntity(kbId, typeId, name, context, weighed);
        if (!outcome.created()) {
            // 并行的另一份文档刚建好它：用它的，审核对也是它排的
            return new ResolutionResult(outcome.id(), false, new ArrayList<>());
        }
        if (!cross.isEmpty()) {
            // 跨类型同名并存：消歧后缀按名字分组（不分类型），需要刷新
            refreshDisambiguators(kbId, name);
        }
        List<ReviewRequest> out = confusableReviews(mentionKey, reviewCands, context);
        for (CrossCandidate c : recallCands) {
            float[] profile = c.profile();
            Float sim = context != null && profile != null
                    ? Resolution.cosine(profile, context) : null;
            if (sim != null && sim < Resolution.SIM_NEW) {
                // 画像明确不像：完全分开，不打扰审核队列
                continue;
            }
            // 灰区或无从判别（无 embedding / 候选无画像）：宁分勿合 + 审核对
            out.add(new ReviewRequest(c.id(), sim != null ? sim : 0f,
                    Resolution.driftReason(mentionKey, c.typeKey(), sim), ReviewStage.ADJUDICATING));
        }
        truncate(out, Resolution.MAX_DRIFT_REVIEWS);
        // 漂移这条路一样会新建实体，包含关系照查
        out.addAll(containmentReviews(kbId, typeId, name, outcome.id(), context));
        return new ResolutionResult(outcome.id(), true, out);
    }

    /** Rust Vec::truncate。 */
    private static void truncate(List<?> list, int max) {
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    /** resolution.rs:914-936 confusable_reviews：易混类型对逐个入队（Adjudicating）。 */
    private static List<ReviewRequest> confusableReviews(String mentionKey,
                                                          List<CrossCandidate> cands,
                                                          float[] ctx) {
        List<ReviewRequest> out = new ArrayList<>();
        for (CrossCandidate c : cands) {
            float[] profile = c.profile();
            Float sim = ctx != null && profile != null ? Resolution.cosine(profile, ctx) : null;
            out.add(new ReviewRequest(c.id(), sim != null ? sim : 0f,
                    Resolution.driftReason(mentionKey, c.typeKey(), sim), ReviewStage.ADJUDICATING));
        }
        return out;
    }

    // ====================================================================
    // 建实体（resolution.rs:1100-1216）

    /** create_entity 的产出：(id, 是否真的新建)。 */
    record CreateOutcome(UUID id, boolean created) {
    }

    /**
     * resolution.rs:1107-1176 {@code create_entity}：新建一个实体。
     *
     * <p><b>同名同类的新建串行化。</b>上面的查找不在事务里：两份文档并行抽取，同一个
     * 名字各自查一遍都没有、各自建一个——实测「澜图数据」在同一秒里建了两个。这里按
     * （库，名字）拿事务级咨询锁，锁里再查一次：别人刚建好的，就用它的。不同类型的
     * 同名不在此列——那是消歧的事，不是竞态。
     */
    CreateOutcome createEntity(UUID kbId, UUID typeId, String name, float[] context,
                               List<UUID> weighed) {
        // 名字属性在锁外取：它自己有一次插入，放进锁里会让所有建实体的人排同一把队
        UUID knownAs = names.ensureKnownAs(kbId);
        return newTx.execute(tx -> {
            mapper.lockNameCreation(kbId.toString(), name);
            List<UUID> existing = mapper.selectExistingNameEntity(kbId, name, typeId, weighed);
            if (!existing.isEmpty()) {
                return new CreateOutcome(existing.get(0), false);
            }
            UUID id = Uuids.nowV7();
            mapper.insertEntity(id, kbId, typeId, name,
                    context != null ? VectorText.literal(context) : null,
                    context != null ? 1 : 0);
            // 与实体同一个事务：召回按名字事实找它，建出来却查不到名字的那一瞬间不能
            // 有。出处（哪一块、哪句话）由抽取随后给这条事实补证据
            mapper.insertNameFact(Uuids.nowV7(), kbId, id, knownAs, name);
            return new CreateOutcome(id, true);
        });
    }

    /**
     * resolution.rs:1191-1216 {@code create_described}：一个被描述、没有名字的东西
     * （0044 第一刀，#729）——"the buyer's parent company"、「那家上海的子公司」。
     * 它是一个实体（陈述要指着它），但它没有名字：canonical_name 写成描述本身只为
     * 显示；description 是它真正的身份；<b>不写 known_as</b>——名字事实是召回的桥，
     * 一段描述做桥会把两篇文档里碰巧描述得一样的两个东西接到一起。
     *
     * <p>朴素插入：不加锁、不找候选、不去重——同一篇文档里同一段描述由调用方并成一个。
     * 两篇文档各描述一次，就是两个实体，等对齐来判它们是不是一个。
     */
    /**
     * resolution.rs:2736-2740 set_specific_type（服务层壳）：模型说它是什么——存成它自己
     * 的说法（specific_type 首写不覆盖、截 80），类的绑定等对齐来做。
     */
    public void setSpecificType(UUID entityId, String value) {
        mapper.setSpecificType(entityId, value);
    }

    public UUID createDescribed(UUID kbId, String description, String kindWord) {
        String trimmed = description.strip();
        if (trimmed.isEmpty()) {
            throw AppException.invalid("description_missing",
                    "a described thing needs the document's description");
        }
        UUID id = Uuids.nowV7();
        String kind = kindWord == null ? null : kindWord.strip();
        mapper.insertDescribed(id, kbId, trimmed, kind == null || kind.isEmpty() ? null : kind);
        return id;
    }

    /**
     * resolution.rs:1228-1261 {@code update_profile}：画像增量质心
     * {@code profile ← (profile·n + ctx) / (n+1)}。维度不匹配（换过 embedding 模型）
     * 时用新向量重置画像。
     */
    void updateProfile(UUID id, int n, float[] ctx) {
        String existing = mapper.selectProfileEmbedding(id);
        float[] old = VectorText.parse(existing);
        float[] newVec;
        int newN;
        if (old != null && old.length == ctx.length && n > 0) {
            float nf = n;
            newVec = new float[ctx.length];
            for (int i = 0; i < ctx.length; i++) {
                newVec[i] = (old[i] * nf + ctx[i]) / (nf + 1.0f);
            }
            newN = n + 1;
        } else {
            newVec = ctx;
            newN = 1;
        }
        mapper.updateProfile(id, VectorText.literal(newVec), newN);
        // 画像表也要索引（0035 / #514）：类型消解按主语逐个扫它。在了的话这一句是查找
        requestEntityProfileIndex(newVec.length);
    }

    /** vector_index.rs:116-118 index_name（Target::EntityProfiles）。 */
    private static String entityProfilesIndexName(int dims) {
        return "entities_profile_embedding_hnsw_" + dims;
    }

    /** vector_index.rs:70 MAX_DIMS。 */
    private static final int MAX_DIMS = 2000;

    /** 进程里记住「已经在了」的索引（vector_index.rs:130-141 known/remember）。 */
    private static final Set<String> KNOWN_VECTOR_INDEXES = ConcurrentHashMap.newKeySet();

    /** vector_index.rs:183-201 request（Target::EntityProfiles）：在的话不排。 */
    private void requestEntityProfileIndex(int dims) {
        if (dims == 0 || dims > MAX_DIMS) {
            return;
        }
        String name = entityProfilesIndexName(dims);
        if (KNOWN_VECTOR_INDEXES.contains(name)) {
            return;
        }
        if (Boolean.TRUE.equals(documents.selectEmbeddingIndexValid(name))) {
            KNOWN_VECTOR_INDEXES.add(name);
            return;
        }
        var payload = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        payload.put("table", "entities");
        payload.put("dims", dims);
        jobs.enqueueUnlessQueuedTx("build_vector_index", payload);
    }

    /**
     * resolution.rs:1290-1315 {@code existing_by_name}：跟这个实体最像的已定类实体…
     * 抽取用它判断一个没声明的主宾是不是已知的东西（#559）：库里有没有叫这个名字的
     * 实体，不问类型、并掉的不算；同名多个时取事实最多的那个——这里只回答「有没有」，
     * 谁是谁交给消解。
     */
    public UUID existingByName(UUID kbId, String rawName) {
        String name = Names.normalizeName(rawName);
        return mapper.selectExistingByName(kbId, Resolution.recallKeys(name));
    }

    /**
     * resolution.rs:2429-2475 {@code unadopt_types}（M5 任务 6 切流演练落位——HTTP 撤销
     * 一次类型消解）：把那一批实体放回原来的类。from_type_id 可能是 NULL——0009 之后
     * 最常见的一次改类正是「从没有类到有类」，撤销就是把它推回没有类。逐条 UPDATE 挪回
     * （proposed_type 标回采信的 key），整批打 reverted_at，最后逐名刷消歧后缀。
     *
     * @return 撤回的实体数；批不存在/已撤 → NotFound
     */
    public int unadoptTypes(UUID kbId, UUID batchId) {
        List<Object[]> rows = new org.springframework.jdbc.core.JdbcTemplate(dataSource).query(
                """
                SELECT r.entity_id, r.from_type_id, t.key
                  FROM entity_retypes r JOIN entity_types t ON t.id = r.to_type_id
                 WHERE r.batch_id = ? AND r.kb_id = ? AND r.reverted_at IS NULL
                """,
                (rs, i) -> new Object[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3)},
                batchId, kbId);
        if (rows.isEmpty()) {
            throw tech.seexun.jknow.common.AppException.NOT_FOUND;
        }
        org.springframework.jdbc.core.JdbcTemplate jdbc =
                new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        List<String> names = new java.util.ArrayList<>();
        int reverted = 0;
        for (Object[] row : rows) {
            java.util.List<String> got = jdbc.query(
                    """
                    UPDATE entities SET type_id = ?, proposed_type = ?, updated_at = now()
                     WHERE id = ? RETURNING canonical_name
                    """,
                    (rs, i) -> rs.getString(1), row[1], row[2], row[0]);
            names.addAll(got);
            reverted++;
        }
        jdbc.update("UPDATE entity_retypes SET reverted_at = now()"
                + " WHERE batch_id = ? AND kb_id = ? AND reverted_at IS NULL", batchId, kbId);
        for (String n : names) {
            refreshDisambiguators(kbId, n);
        }
        return reverted;
    }

    /**
     * resolution.rs:1317-1386 {@code refresh_disambiguators}：同名组展示消歧——组内
     * ≥2 个存活实体时，各自取最能把自己和同名者分开的那条事实的宾语名，否则退回类型
     * 标签；组内唯一则清空。排序理由都来自本体自己的声明（#299，不认手写词表）。
     */
    public void refreshDisambiguators(UUID kbId, String name) {
        List<UUID> group = mapper.selectDisambiguatorGroup(kbId, name);
        if (group.size() < 2) {
            for (UUID id : group) {
                mapper.clearDisambiguator(id);
            }
            return;
        }
        for (UUID id : group) {
            // LEFT JOIN（0044）：开放陈述指着的宾语也能当消歧后缀——「张三 · 星云科技」
            // 不因为「任职于」没进本体就写不出来
            String label = mapper.selectDisambiguatingLabel(kbId, id, group);
            // 关联事实找不着就退到类型标签；类型也可能没有（0009），那就留 null——
            // 界面按同名并列显示，别编一个出来
            if (label == null) {
                label = mapper.selectTypeLabelOfEntity(id);
            }
            mapper.setDisambiguator(id, label);
        }
    }

    // ====================================================================
    // 审核队列写面（resolution.rs:1393-1421/1719-1772/1778-1870）

    /** resolution.rs:1393-1421 {@code create_review}：灰区疑似重复对入队（幂等 upsert）。 */
    public void createReview(UUID kbId, UUID leftId, UUID rightId, float score, String reason,
                             ReviewStage stage) {
        mapper.insertResolutionReview(Uuids.nowV7(), kbId, leftId, rightId, score, reason,
                stage.asString());
    }

    /**
     * resolution.rs:1719-1735 {@code pending_adjudications}：等待 LLM 裁决的审核项
     * （后台裁决任务消费，M4；测试与本任务共用读面）。
     */
    public List<ReviewItem> pendingAdjudications(UUID kbId, long limit) {
        return reviews.assembleReviews(kbId, mapper.selectPendingAdjudications(kbId, limit));
    }

    /**
     * resolution.rs:1742-1752 {@code escalate_review}：LLM 不确定 / 未配模型 → 转人工。
     * reason 存的是 code，可选地跟 |detail——措辞归界面 i18n（0004）。
     */
    public void escalateReview(UUID reviewId, String reason) {
        mapper.escalateReview(reviewId, reason);
    }

    /**
     * resolution.rs:1756-1772 {@code close_review_auto}：自动定夺（LLM 高置信）。
     * 合并动作本身由调用方先执行。回关上几行：0 = 这一对已经不是 pending
     * （人裁了，或另一条路先到），调用方别再记一条一样的裁决。
     */
    public long closeReviewAuto(UUID reviewId, String status, String reason) {
        return mapper.closeReviewAuto(reviewId, status, reason);
    }

    // ====================================================================
    // LLM 裁决缓存（resolution.rs:2386-2415）——键由调用方（裁决任务）按
    // 名字/类型/事实摘要+先例拼，这里只管存取

    /** 缓存的一笔：same 可空（模型也不确定——unsure 也缓存，不重复付费）。 */
    public record CachedVerdict(Boolean same, float confidence) {
    }

    /** resolution.rs:2386-2394 {@code get_verdict}：这一对名字上一次问出来的答案。 */
    public CachedVerdict getVerdict(UUID kbId, String pairKey) {
        ResolutionMapper.VerdictRow row = mapper.selectVerdict(kbId, pairKey);
        return row == null ? null : new CachedVerdict(row.same(), row.confidence());
    }

    /** resolution.rs:2396-2415 {@code put_verdict}：UPSERT 同键重写（先例进键，见任务 3）。 */
    public void putVerdict(UUID kbId, String pairKey, Boolean same, float confidence,
                           String model) {
        mapper.upsertVerdict(kbId, pairKey, same, confidence, model);
    }

    /** #428 批量裁决的逐条结果：一条失败不拖累其余的。 */
    public record ReviewBatchOutcome(UUID id, String error) {
    }

    /**
     * resolution.rs:1696-1716 {@code decide_reviews}：批量裁决 = 逐条的人工裁决（#428）
     * ——每一条走 {@link #decideReview}，一条失败（不存在、已经裁过、并到一半出错）
     * 不拖累其余的，结果逐条带回；动作不认识整批拒绝——那不是某一条的问题。
     */
    public List<ReviewBatchOutcome> decideReviews(UUID kbId, List<UUID> ids, String action,
                                                  UUID userId, String rationale) {
        // Rust 前置整批校验（resolution.rs:1705-1707）：动作不认识直接 Validation 拒整批，
        // 一条都不动（审查 M-4 补齐——之前版本漏了这道门，非法动作会变成逐条 error）。
        // M4 任务 2 复核改回 Validation（无 code）——与 Rust 逐字（此前多出的
        // code:"action" 键属形状偏差，无测试依赖）
        if (!"merge".equals(action) && !"keep".equals(action)) {
            throw AppException.validation("action must be merge or keep");
        }
        List<ReviewBatchOutcome> out = new ArrayList<>(ids.size());
        for (UUID id : ids) {
            String error = null;
            try {
                decideReview(kbId, id, action, userId, rationale);
            } catch (RuntimeException e) {
                error = e.getMessage();
            }
            out.add(new ReviewBatchOutcome(id, error));
        }
        return out;
    }

    /**
     * resolution.rs:1778-1851 {@code decide_review}：人工定夺。merge 方向：度数高
     * （事实多）的一方作为存活目标，平局取更早创建的。rationale：人拍板时写的那一句
     * （0026）——空着是允许的；但只要写了，它就跟着这一行和台账一起留下。
     */
    public void decideReview(UUID kbId, UUID reviewId, String action, UUID userId,
                             String rationale) {
        String trimmed = rationale == null || rationale.strip().isEmpty() ? null : rationale.strip();
        ReviewRow row = mapper.selectPendingReview(reviewId, kbId);
        if (row == null) {
            throw AppException.NOT_FOUND;
        }
        // agent 正在裁的一对（0025）：等它的建议，或者关掉开关
        if (governanceReads.lockedByAgent(kbId, reviewId)) {
            throw AppException.conflict(
                    "The agent is deciding this pair right now; wait for its proposal"
                            + " or turn governance off.");
        }
        switch (action) {
            case "merge" -> {
                // 同名连锁（A-B 合了，B-C 还等着）：B 已并进 A，这一对实际上是 A-C。
                // 跟着 merged_into 走到活着的那个再合；两边走到同一个，就只剩把审核行关上
                UUID l = survivor(kbId, row.leftId());
                UUID r = survivor(kbId, row.rightId());
                if (!l.equals(r)) {
                    MergeDirection dir = mergeDirection(l, r);
                    // 合并日志的 reason 也记这一句：Review › Merges 那一列读的是它
                    mergeEntities(kbId, dir.source(), dir.target(), userId,
                            trimmed != null ? trimmed : "review decision");
                }
                mapper.markReviewMerged(reviewId, userId, trimmed);
            }
            case "keep" -> mapper.markReviewKept(reviewId, userId, trimmed);
            default -> throw AppException.validation("action must be merge or keep");
        }
    }

    /**
     * resolution.rs:1855-1870 {@code survivor}：跟着 merged_into 走到还活着的那个实体。
     * 同簇连锁合并之后，一对里的一侧可能已经并进了别人；合并要合的是活着的那个，
     * 不是那条已经空了的行（≤16 跳，超出报环）。
     */
    public UUID survivor(UUID kbId, UUID id) {
        for (int i = 0; i < 16; i++) {
            List<UUID> next = mapper.selectMergedInto(id, kbId);
            if (next.isEmpty()) {
                throw AppException.NOT_FOUND;
            }
            UUID mergedInto = next.get(0);
            if (mergedInto == null) {
                return id;
            }
            id = mergedInto;
        }
        throw AppException.conflict("merge chain too long");
    }

    // ====================================================================
    // 合并方向与十步事务（resolution.rs:1873-2198）

    /** merge_direction 的产出：(target 存活, source 被并)。 */
    public record MergeDirection(UUID target, UUID source) {
    }

    /**
     * resolution.rs:1873-1898 {@code merge_direction}：度数（facts 标量子查询取 kb 后
     * OR subject/object + invalidated_at IS NULL——<b>这里不排除名字事实</b>：合并里
     * 名字一样要搬，量「身上挂着多少东西」不能把名字剔出去）高者存活，平局 uuidv7
     * 早者（a &lt; b 是无符号字节序；uuidv7 时间有序 = 更早创建的一方存活）。
     */
    public MergeDirection mergeDirection(UUID a, UUID b) {
        long degA = mapper.selectFactDegree(a);
        long degB = mapper.selectFactDegree(b);
        return degA > degB || (degA == degB && Timeline.UUID_BYTES.compare(a, b) < 0)
                ? new MergeDirection(a, b)
                : new MergeDirection(b, a);
    }

    /**
     * resolution.rs:1931-2198 {@code merge_entities}：合并 source → target——事实改挂
     * target、互指事实与合并后的重复事实作废、画像加权合并、source 标记 merged_into。
     * 全程记日志可回滚。<b>名字不用特判</b>（0041）：source 的名字是它身上的
     * known_as 事实，跟别的事实一起搬到 target、记进 moved_subject_facts；同名的那条
     * 按重复事实作废。撤回合并时一起搬回去。
     */
    public UUID mergeEntities(UUID kbId, UUID sourceId, UUID targetId, UUID mergedBy,
                              String reason) {
        if (sourceId.equals(targetId)) {
            throw AppException.invalid("self_merge", "Cannot merge an entity into itself");
        }
        EntityFull source = mapper.selectEntityFull(kbId, sourceId);
        if (source == null) {
            throw AppException.NOT_FOUND;
        }
        EntityFull target = mapper.selectEntityFull(kbId, targetId);
        if (target == null) {
            throw AppException.NOT_FOUND;
        }
        if (source.mergedInto() != null || target.mergedInto() != null) {
            throw AppException.conflict("Entity already merged");
        }

        List<UUID> movedSubject;
        UUID mergeId = Uuids.nowV7();
        record MergeTx(List<UUID> movedSubject, List<UUID> movedObject, List<UUID> invalidated) {
        }
        MergeTx done = newTx.execute(tx -> {
            // 搬动会牵连的时间线先按固定顺序锁上，再改任何一行（撤回合并同一个顺序，
            // 见 temporal 模块头）：不锁的话，合并与撤回、合并与落库对账会各拿一半行锁
            // 互相等
            List<UUID> moving = mapper.selectMovingFacts(kbId, sourceId);
            List<Timeline> timelines = temporal.timelinesOf(kbId, moving, List.of(sourceId),
                    targetId);
            temporal.lockTimelines(kbId, timelines);

            // 互指事实（合并后变自环）→ 作废
            List<UUID> invalidated = new ArrayList<>(mapper.invalidateCrossFacts(kbId, sourceId,
                    targetId));
            List<UUID> movedSubject1 = mapper.moveSubjectFacts(sourceId, targetId, kbId);
            List<UUID> movedObject = mapper.moveObjectFacts(sourceId, targetId, kbId);

            // 合并后 SPO+valid_from 重复的 live 事实：留最早 recorded_at 的一条，其余作废
            List<UUID> dupIds = mapper.selectDuplicateFactIds(kbId, targetId);
            if (!dupIds.isEmpty()) {
                mapper.invalidateFactIds(dupIds);
                invalidated.addAll(dupIds);
            }

            // 画像加权合并（维度一致才并；缺一侧就把那一侧的带上）
            float[] targetProfile = VectorText.parse(target.profileEmbedding());
            float[] sourceProfile = VectorText.parse(source.profileEmbedding());
            String profile;
            int profileN;
            if (targetProfile != null && sourceProfile != null
                    && targetProfile.length == sourceProfile.length) {
                float nt = Math.max(target.profileN(), 1);
                float ns = Math.max(source.profileN(), 1);
                float[] mergedVec = new float[targetProfile.length];
                for (int i = 0; i < targetProfile.length; i++) {
                    mergedVec[i] = (targetProfile[i] * nt + sourceProfile[i] * ns) / (nt + ns);
                }
                profile = VectorText.literal(mergedVec);
                profileN = target.profileN() + source.profileN();
            } else if (targetProfile != null) {
                profile = target.profileEmbedding();
                profileN = target.profileN();
            } else if (sourceProfile != null) {
                profile = source.profileEmbedding();
                profileN = source.profileN();
            } else {
                profile = null;
                profileN = 0;
            }
            // 类型调和：没有类型的一侧让位（0009 之后「还没判出来」是 null，一个 or
            // 就说完；从前要查库找 concept 那行的 id）
            UUID newTypeId = target.typeId() != null ? target.typeId() : source.typeId();
            mapper.updateMergedEntity(targetId, profile, profileN, newTypeId);
            mapper.markMergedInto(sourceId, targetId);

            // 其余涉及 source 的 pending 审核项：改指到合并目标，不是关掉
            // （「关掉就是永远关掉」——包含召回只在新建时跑一次，不会再被重提；
            // Mr. Holmes 案例）。先关两类真正过时的，剩下的改指
            mapper.supersedeObsoleteReviews(kbId, sourceId, targetId);
            mapper.redirectReviews(kbId, sourceId, targetId);

            mapper.insertEntityMerge(mergeId, kbId, sourceId, targetId, movedSubject1, movedObject,
                    invalidated, target.profileEmbedding(), target.profileN(), target.typeId(),
                    mergedBy, reason);
            return new MergeTx(movedSubject1, movedObject, invalidated);
        });
        movedSubject = done.movedSubject();

        // 搬移后的时态对账：换了主/宾的事实等价于新观察落库——两个对象折成一个后，
        // 唯一性不变量才第一次看得到旧开放区间与继任者相撞（如"星尘"并入"星尘项目"，
        // 旧负责人的 leads 应在新任起点闭合）。修正行 id 记入合并账本，供审计。
        // 回滚不靠它：回滚按搬走之后剩下的行重算时间线，引擎画的终点自然跟着变（0057）。
        // 注：本步在事务外，失败有自愈性——这条时间线下一次有事实落库时整条重算
        List<UUID> movedAll = new ArrayList<>(movedSubject);
        movedAll.addAll(done.movedObject());

        // 合并是第三条改写事实的路（#196）：换掉主语或宾语，就可能把一条合法事实变成
        // 签名违规。放在事务之后：目标实体的类型在事务里刚调和过，提交了才看得见。
        // 失败不拖垮合并也不跳过时态对账：Rust（resolution.rs:2168-2179）对 Err 只
        // tracing::warn! 继续——下一轮公理检查自愈（审查 M-1）
        try {
            signatureBreaks.reportMoved(kbId, movedAll);
        } catch (RuntimeException e) {
            log.warn("合并后签名违规上报失败 kb={} moved={}：{}", kbId, movedAll.size(),
                    e.getMessage());
        }

        var report = temporal.reconcileMovedFacts(kbId, movedAll);
        if (!report.corrected.isEmpty()) {
            mapper.setMergeCorrections(mergeId, report.corrected);
        }

        refreshDisambiguators(kbId, source.canonicalName());
        if (!asciiEqualsIgnoreCase(source.canonicalName(), target.canonicalName())) {
            refreshDisambiguators(kbId, target.canonicalName());
        }
        return mergeId;
    }

    /** Rust eq_ignore_ascii_case：只忽略 ASCII 大小写（非 ASCII 字母按不同字符处理）。 */
    private static boolean asciiEqualsIgnoreCase(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        for (int i = 0; i < a.length(); i++) {
            char x = a.charAt(i);
            char y = b.charAt(i);
            if (x == y) {
                continue;
            }
            if (x >= 'A' && x <= 'Z') {
                x += 32;
            }
            if (y >= 'A' && y <= 'Z') {
                y += 32;
            }
            if (x != y) {
                return false;
            }
        }
        return true;
    }

    // ====================================================================
    // revert_merge（resolution.rs:2213-2355）

    /**
     * resolution.rs:2244-2355 {@code revert_merge}：精确回滚一次合并——事实原路搬回、
     * 作废撤销、target 画像与类型恢复快照、source 复活。
     *
     * <p><b>搬回去的是搬过来的事实连同从它们改写出来的每一行。</b>合并之后引擎把它
     * 关上过、人改过它的区间、人驳回过它——那些行都是它的，跟着回源实体，各自保持
     * 原样：人改的区间还在，驳回的仍是驳回的。
     *
     * <p>怎么搬分两种，为的是记录轴回放合并窗口时仍答得对（0027 的 fact_owner_at 只认
     * 账本）：账本上的行原地改回主语/宾语；合并之后才改写出来的行不在账本上，活着的
     * 作废、在源实体上另起一行接着它（rehome），作废了的留在目标实体上——它们在那段
     * 窗口里确实挂在那里。
     */
    public void revertMerge(UUID kbId, UUID mergeId) {
        MergeRow m = mapper.selectMergeRow(mergeId, kbId);
        if (m == null) {
            throw AppException.NOT_FOUND;
        }
        if (m.revertedAt() != null) {
            throw AppException.conflict("Merge already reverted");
        }
        EntityFull source = mapper.selectEntityFull(kbId, m.sourceId());
        if (source == null) {
            throw AppException.NOT_FOUND;
        }
        newTx.executeWithoutResult(tx -> {
            // 事实此刻挂在谁身上：目标实体，或者目标后来又并进去的实体（S 并进 T、
            // T 再并进 C，撤回 S→T 时 S 的事实在 C 身上。只认目标的话，连环合并里
            // 撤回头一环，源实体复活了、它的事实却留在链尾——#679 第四轮评审）
            List<UUID> holders = mapper.selectHolderChain(m.targetId());
            List<UUID> touched = new ArrayList<>(mapper.selectWithRewrites(m.movedSubjectFacts()));
            touched.addAll(mapper.selectWithRewrites(m.movedObjectFacts()));
            touched.addAll(m.invalidatedFacts());
            List<Timeline> timelines = temporal.timelinesOf(kbId, touched, holders,
                    m.sourceId());
            temporal.lockTimelines(kbId, timelines);
            // 锁上之后再走一遍：等锁的时候，引擎可能刚从它们改写出新的一行
            List<UUID> subjectRows = mapper.selectWithRewrites(m.movedSubjectFacts());
            List<UUID> objectRows = mapper.selectWithRewrites(m.movedObjectFacts());

            mapper.revertSubjectFacts(m.sourceId(), m.movedSubjectFacts(), holders);
            mapper.revertObjectFacts(m.sourceId(), m.movedObjectFacts(), holders);
            for (UUID id : mapper.selectLiveRewriteSubjects(subjectRows, m.movedSubjectFacts(),
                    holders)) {
                temporal.rehomeTx(id, m.sourceId(), null);
            }
            for (UUID id : mapper.selectLiveRewriteObjects(objectRows, m.movedObjectFacts(),
                    holders)) {
                temporal.rehomeTx(id, null, m.sourceId());
            }
            mapper.reviveFacts(m.invalidatedFacts());
            temporal.tidyTimelinesTx(kbId, timelines);

            // source 的名字是它的名字事实，已经跟着 moved_subject_facts 搬回去了；
            // target 的画像/类型恢复快照（coalesce：撤回之后人又改过类型的话保人的）
            mapper.restoreTargetEntity(m.targetId(), m.targetProfileBefore(),
                    m.targetProfileNBefore(), m.targetTypeBefore());
            mapper.reviveSource(m.sourceId());
            mapper.markMergeReverted(mergeId);
        });

        refreshDisambiguators(kbId, source.canonicalName());
    }

    // ====================================================================
    // 类型消解候选与近邻（resolution.rs:2575-2896；M4 任务 1 随治理测试移植——
    // a_judged_entity_waits_its_turn 与 a_batch_gathers_its_neighbours_in_order 的面）

    /**
     * resolution.rs:2617-2696 {@code entities_for_type_resolution}：值得送去精化类型的
     * 实体——还没有类的、模型报过词表外类型的、现类本身还有子类的（第三种是主力）。
     * {@code unattended}=自动跑（0016 C2）跳过引擎已经看过的（type_resolved_at）；
     * 人点的那条路传 false。人拍过板的两条路都不碰（SQL 里 type_source &lt;&gt; 'human'）。
     */
    public List<ResolutionMapper.TypeCandidateSubject> entitiesForTypeResolution(UUID kbId,
                                                                                long limit,
                                                                                boolean unattended) {
        return mapper.selectTypeCandidates(kbId, limit, unattended);
    }

    /**
     * resolution.rs:2700-2708 {@code mark_type_judged}：引擎看过了——三档（改了/留给人/
     * 不动）都算看过，自动跑不再回头。人手工改类、合并之类的写路径不清这个标记。
     */
    public long markTypeJudged(UUID kbId, List<UUID> ids) {
        return mapper.markTypeJudged(kbId, ids);
    }

    /** retype 的 picks 行（entity, 目标类型）。 */
    public record EntityRetype(UUID entityId, UUID typeId) {
    }

    /** retype 的产出：(batch_id, moved)。 */
    public record RetypeOutcome(UUID batchId, int moved) {
    }

    /**
     * resolution.rs:2903-2982 {@code retype_entities}：类型消解的落库——一批
     * {@code (entity_id, type_id)} 各自改类、各进一笔 entity_retypes 账本，收尾对
     * 动过的名字重算消歧后缀（后缀的兜底值就是类型标签）。
     *
     * <p>{@code actor=null} = 引擎自动裁决（type_source='inferred'，不受保护、下一轮
     * 还要捞得回来）；有 actor = 人点的批准（'human'，{@code entities_for_type_resolution}
     * 的 WHERE 把它挡在外面）。一个实体在一批里只改一次（账本主键 (batch, entity)，
     * 重复的第二条本来也没有意义）；已在目标类上的不算改动、不进账本——撤销时不该
     * 把它们推回去。每条各自一个事务（Rust 原样：一条失败回滚它自己，已落的留着）。
     */
    /**
     * resolution.rs:2999-3018 {@code approve_refinement}（M5 任务 6 切流演练落位）：
     * 认可一个「粗类 → 细类」的配对——配对认可一次就不再问（跨轴分档的判据）。
     * ON CONFLICT DO NOTHING：已认可过的幂等。
     */
    public void approveRefinement(UUID kbId, UUID fromTypeId, UUID toTypeId, UUID by) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                """
                INSERT INTO type_refinement_pairs (kb_id, from_type_id, to_type_id, approved_by)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT (kb_id, from_type_id, to_type_id) DO NOTHING
                """, kbId, fromTypeId, toTypeId, by);
    }

    public RetypeOutcome retypeEntities(UUID kbId, List<EntityRetype> picks, UUID actor) {
        UUID batchId = Uuids.nowV7();
        int moved = 0;
        Set<String> names = new HashSet<>();
        // 一个实体在一批里只改一次：账本主键是 (batch_id, entity_id)，同一个 id 来两次
        // 会撞——重复的第二条本来也没有意义
        Set<UUID> seen = new HashSet<>();
        for (EntityRetype pick : picks) {
            if (!seen.add(pick.entityId())) {
                continue;
            }
            // 每条各自一个事务（Rust 每对 begin/commit）：改类与账本行同进同退，一条
            // 失败回滚它自己，已落的留着
            ResolutionMapper.RetypeRow row = newTx.execute(tx -> {
                ResolutionMapper.RetypeRow r = mapper.retypeOne(pick.entityId(), pick.typeId(),
                        kbId, actor);
                if (r != null) {
                    mapper.insertEntityRetype(batchId, kbId, pick.entityId(), r.fromType(),
                            pick.typeId(), actor);
                }
                return r;
            });
            // 已在目标类上的不算改动（CTE 条件回 null），不进账本
            if (row == null) {
                continue;
            }
            names.add(row.name());
            moved++;
        }
        // 消歧后缀的兜底值就是类型标签，改了类就得重算
        for (String name : names) {
            refreshDisambiguators(kbId, name);
        }
        return new RetypeOutcome(batchId, moved);
    }

    /**
     * resolution.rs:2715-2729 {@code descendants_of}：某个类的全部后代（含自身）。
     * <b>递归而不是一层</b>：本体是 DAG（software_application ⊂ creative_work ⊂ thing），
     * 只查一层就把绝大多数正确答案挡在门外。
     */
    public List<UUID> descendantsOf(UUID kbId, UUID root) {
        return mapper.selectDescendants(kbId, root);
    }

    /**
     * resolution.rs:2985-2995 {@code approved_refinements}：人认可过的 (粗类, 细类)
     * 配对——类型消解的分档用它豁免「跨轴」：同一对人点过头，就不再进人工。
     */
    public java.util.Set<java.util.Map.Entry<UUID, UUID>> approvedRefinements(UUID kbId) {
        java.util.Set<java.util.Map.Entry<UUID, UUID>> out = java.util.Collections.newSetFromMap(
                new java.util.HashMap<>());
        for (ResolutionMapper.ApprovedPair pair : mapper.selectApprovedRefinements(kbId)) {
            out.add(Map.entry(pair.fromTypeId(), pair.toTypeId()));
        }
        return out;
    }

    /**
     * 一批之内 {@code descendants_of} 的记忆（#514，resolution.rs:2766-2800）：粗类来自
     * 抽取的小词表，六十个主语里同一个 coarse_id 反复出现，同一个递归 CTE 就反复发。
     * 批内本体不动，同一输入同一结果，<b>记住不改答案</b>。没有粗类的主语不进这张表
     * （它没有「后代」这个轴，0009；用 null 当键会把它和某个真实的类混在一起）。
     */
    public static final class DescendantsMemo {
        private final Map<UUID, Set<UUID>> sets = new java.util.HashMap<>();

        /** root=null → 空集（没有「后代」这个轴）；否则查一次记住。 */
        public Set<UUID> get(ResolutionService resolution, UUID kbId, UUID root) {
            if (root == null) {
                return Set.of();
            }
            Set<UUID> cached = sets.get(root);
            if (cached != null) {
                return cached;
            }
            Set<UUID> set = new HashSet<>(resolution.descendantsOf(kbId, root));
            sets.put(root, java.util.Collections.unmodifiableSet(set));
            return set;
        }

        /** 记住了几个根。 */
        public int len() {
            return sets.size();
        }

        public boolean isEmpty() {
            return sets.isEmpty();
        }
    }

    /** 一个主语的近邻（resolution.rs:2802-2804）：语境相似的已定类实体，连同「是否同一篇文档」。 */
    public record Neighbour(String name, UUID typeId, String key, double distance,
                            boolean sameDocument) {
    }

    /**
     * resolution.rs:2806-2862 {@code nearest_typed_entities}：跟这个实体最像的<b>已定类</b>
     * 实体（类型消解的第二个候选来源——名字对名字、同语言，库越大越准）。主语自己的
     * 向量先取出来再查（维度要写进 SQL，vector_index 规矩 1）；没有向量的主语没有近邻。
     * 内连接就是那道门：没判出类型的实体不是答案，拿它当邻居只会把「没判出来」传染开。
     * 次序在外层再排一遍（RESORT，#652）。
     */
    public List<Neighbour> nearestTypedEntities(UUID kbId, UUID entityId, long limit) {
        String profile = mapper.selectProfileEmbeddingInKb(kbId, entityId);
        if (profile == null) {
            return List.of();
        }
        float[] queryVec = VectorText.parse(profile);
        int dims = queryVec.length;
        String literal = VectorText.literal(queryVec);
        // vector_index.rs:126-128 distance 的 $3（SELECT 与 ORDER BY 各引用一次）在 JDBC 下
        // 是两个独立 ?——同一向量按位绑两次（chunkVectorSearch 同一套写法）
        String distance = "e.profile_embedding::vector(" + dims + ") <=> ?::vector(" + dims + ")";
        String sql = "WITH my_docs AS ("
                + " SELECT DISTINCT ev.document_id FROM fact_evidence ev"
                + " JOIN facts f ON f.id = ev.fact_id"
                + " WHERE f.kb_id = ? AND (f.subject_id = ? OR f.object_id = ?)"
                + " ),"
                + " nearest AS MATERIALIZED ("
                + " SELECT e.id, e.canonical_name, t.id AS type_id, t.key,"
                + " (" + distance + ")::float8 AS distance,"
                + " EXISTS (SELECT 1 FROM fact_evidence ev2"
                + " JOIN facts f2 ON f2.id = ev2.fact_id"
                + " WHERE f2.kb_id = ? AND (f2.subject_id = e.id OR f2.object_id = e.id)"
                + " AND ev2.document_id IN (SELECT document_id FROM my_docs))"
                + " AS same_document"
                + " FROM entities e"
                + " JOIN entity_types t ON t.id = e.type_id"
                + " WHERE e.kb_id = ? AND e.merged_into IS NULL AND e.id <> ?"
                + " AND e.profile_embedding IS NOT NULL AND "
                + VectorIndexService.sameDims("e.profile_embedding", dims)
                + " ORDER BY " + distance
                + " LIMIT ?"
                + " )"
                + " SELECT canonical_name, type_id, key, distance, same_document"
                + " FROM nearest ORDER BY " + VectorIndexService.RESORT;
        // Rust：begin → relaxed_order（SET LOCAL 只在事务里生效）→ 查询 → commit。
        // JDBC 参数序：distance 的 $3（SELECT 与 ORDER BY 各一次）按 SQL 文本序绑两次
        try (java.sql.Connection conn = dataSource.getConnection()) {
            boolean original = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                vectorIndex.relaxedOrder(conn);
                List<Neighbour> out = new ArrayList<>();
                try (java.sql.PreparedStatement ps = conn.prepareStatement(sql)) {
                    // 绑定序 = SQL 文本里 ? 的出现序：my_docs(1,2,3) → SELECT 距离(4) →
                    // EXISTS 的 kb(5) → WHERE e.kb_id(6)/e.id<>(7) → ORDER BY 距离(8) → LIMIT(9)
                    int p = 1;
                    ps.setObject(p++, kbId);
                    ps.setObject(p++, entityId);
                    ps.setObject(p++, entityId);
                    ps.setString(p++, literal); // SELECT 里的距离
                    ps.setObject(p++, kbId); // EXISTS 子查询的 $1（同 kb）
                    ps.setObject(p++, kbId); // WHERE e.kb_id
                    ps.setObject(p++, entityId); // e.id <> $2
                    ps.setString(p++, literal); // ORDER BY 里的距离（SQL 文本序：先 ORDER 后 LIMIT）
                    ps.setLong(p++, limit);
                    try (java.sql.ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new Neighbour(rs.getString(1),
                                    (UUID) rs.getObject(2), rs.getString(3), rs.getDouble(4),
                                    rs.getBoolean(5)));
                        }
                    }
                }
                conn.commit();
                return out;
            } catch (java.sql.SQLException e) {
                try {
                    conn.rollback();
                } catch (java.sql.SQLException rollbackFailed) {
                    e.addSuppressed(rollbackFailed);
                }
                throw AppException.db("nearest_typed_entities", e);
            } finally {
                try {
                    conn.setAutoCommit(original);
                } catch (java.sql.SQLException ignored) {
                    // 归还池子前的善后失败：连接本身会被池子校验踢掉
                }
            }
        } catch (java.sql.SQLException e) {
            throw AppException.db("nearest_typed_entities", e);
        }
    }

    /** resolution.rs:2867 NEIGHBOUR_SCANS：有界并发上限（八个留足给请求的余量）。 */
    public static final int NEIGHBOUR_SCANS = 8;

    /**
     * resolution.rs:2873-2880 {@code nearest_typed_for_each}：一批主语各自的近邻。
     */
    public List<List<Neighbour>> nearestTypedForEach(UUID kbId, List<UUID> ids, long limit) {
        return nearestTypedForEachWith(kbId, ids, limit, NEIGHBOUR_SCANS);
    }

    /**
     * resolution.rs:2883-2896 {@code nearest_typed_for_each_with}：一批主语各自的近邻，
     * <b>按送进来的顺序回</b>（下游裁决按这个顺序读）。六十个查询彼此无关，有界并发地取，
     * 推理仍在原顺序上做；并发上限由调用方给（测试用它证明上限不改答案）。
     */
    public List<List<Neighbour>> nearestTypedForEachWith(UUID kbId, List<UUID> ids, long limit,
                                                         int atOnce) {
        int parallelism = Math.max(1, atOnce);
        List<List<Neighbour>> out = new ArrayList<>(ids.size());
        java.util.concurrent.ExecutorService pool =
                ids.isEmpty() ? null
                        : java.util.concurrent.Executors.newFixedThreadPool(
                                Math.min(parallelism, ids.size()));
        try {
            List<java.util.concurrent.Future<List<Neighbour>>> futures = new ArrayList<>();
            for (UUID id : ids) {
                futures.add(pool.submit(() -> nearestTypedEntities(kbId, id, limit)));
            }
            for (java.util.concurrent.Future<List<Neighbour>> f : futures) {
                try {
                    out.add(f.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw AppException.db("nearest_typed_for_each interrupted", e);
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof RuntimeException r ? r
                            : AppException.db("nearest_typed_for_each", e);
                }
            }
            return out;
        } finally {
            if (pool != null) {
                pool.shutdown();
            }
        }
    }
}
