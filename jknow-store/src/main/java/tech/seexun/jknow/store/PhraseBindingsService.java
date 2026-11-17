package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 关系短语按签名绑到属性的账本侧（phrase_bindings.rs 500 行对等，0044 决定 3 的第二片）。
 * 数出一个库里有哪些签名（短语 × 主语的类 × 宾语的类或「值」）、记下每个签名判成了什么
 * （绑定）并判哪些过期了。判定本身——问模型、两票一致才绑——在抽取域的对齐器里，
 * 这里不认识模型。
 *
 * <p>短语的归一同类别词：空白折成一个空格、去两端、小写；{@link #normalize} 与 SQL 侧的
 * {@code lower(btrim(regexp_replace(col,'\s+',' ','g')))} 必须说同一件事。
 */
@Service
public class PhraseBindingsService {

    /**
     * phrase_bindings.rs:352 人的判定落库时随手排下的重算任务的种类（{@code materialize_typed}，
     * m2-recon §2 称 materialize_phrase_bindings）。载荷只有 {@code kb_id}——job 读的是
     * <b>当前</b>的绑定，不回放判定时的属性（0051：回放旧载荷会盖掉后来的人）。
     */
    public static final String MATERIALIZE_KIND = "materialize_typed";

    private final PhraseBindingsMapper mapper;
    private final OntologyRegressionsService regressions;
    private final JobsService jobs;
    private final TransactionTemplate joinTx;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public PhraseBindingsService(PhraseBindingsMapper mapper,
                                 OntologyRegressionsService regressions, JobsService jobs,
                                 PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.regressions = regressions;
        this.jobs = jobs;
        this.joinTx = new TransactionTemplate(txManager);
    }

    /**
     * phrase_bindings.rs:25-31 归一一个短语：空白折一、去两端、小写。
     *
     * <p>Rust 的 trim/split_whitespace/to_lowercase 全是 Unicode 语义——{@code (?U)}
     * 让 {@code \s} 吃 Unicode 空白，{@code Locale.ROOT} 防土耳其语等环境的大小写意外
     * （审查 M-5）。
     */
    public static String normalize(String phrase) {
        if (phrase == null) {
            return "";
        }
        return String.join(" ", phrase.strip().split("(?U)\\s+"))
                .toLowerCase(java.util.Locale.ROOT);
    }

    /** phrase_bindings.rs:55-111 库里每条 distinct 的签名（活着的开放陈述分组）。 */
    public List<PhraseSignature> signatures(UUID kbId) {
        return mapper.selectSignatures(kbId);
    }

    /** phrase_bindings.rs:115-133 按 id 取一条绑定的签名。 */
    public PhraseSignature signatureOf(UUID kbId, UUID id) {
        return mapper.selectSignatureOf(kbId, id);
    }

    /** phrase_bindings.rs:185-195 库里全部绑定。 */
    public List<Binding> bindings(UUID kbId) {
        return mapper.selectBindings(kbId);
    }

    /** phrase_bindings.rs:251-268 不再成立的绑定（按时间戳的粗信号——worker 改按指纹判）。 */
    public List<Binding> stale(UUID kbId) {
        return mapper.selectStale(kbId);
    }

    /**
     * phrase_bindings.rs:203-232 {@code basis_of}：一条判定看到的输入的指纹（0053）——
     * 两端类的祖先闭包（含自己）、宾语是不是字面值、按继承命中的候选属性与各自的
     * {@code updated_at}。只是缓存失效的键，不是安全用途：FNV-1a 64 位够用。
     */
    public static String basisOf(List<UUID> subjectClosure, List<UUID> objectClosure,
                                 boolean objectIsValue, List<Object[]> candidates) {
        String s = sortedIds(subjectClosure);
        String o = sortedIds(objectClosure);
        List<String> cands = new ArrayList<>();
        for (Object[] c : candidates) {
            cands.add(c[0] + "@" + rfc3339((OffsetDateTime) c[1]));
        }
        cands.sort(null);
        String text = "s=" + s + ";o=" + o + ";v=" + objectIsValue + ";c=" + String.join(",", cands);
        long h = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return String.format("%016x", h);
    }

    private static String sortedIds(List<UUID> ids) {
        List<String> v = new ArrayList<>();
        for (UUID u : ids) {
            v.add(u.toString());
        }
        v.sort(null);
        return String.join(",", v);
    }

    /**
     * 指纹用的 RFC 3339（type_bindings 的 basis 同一处复用）——<b>AutoSi 形态</b>，与
     * chrono {@code to_rfc3339()} 一字不差：亚秒按值去尾零组（0/3/6/9 位），PG 的时间戳
     * 只有微秒，于是至多 6 位；UTC 偏移写成 {@code +00:00}（chrono 对 {@code DateTime<Utc>}
     * 的写法，不是 ISO 的 {@code Z}）。
     *
     * <p><b>为什么必须逐字（M-4 前置）</b>：basis 是 Java worker 与 Rust 写进共享库的
     * 判定之间比对的缓存失效键。Rust 侧 {@code at.to_rfc3339()} 是 AutoSi；本侧早先
     * 固定 9 位纳秒，同一时刻两套写法，Java 每轮重判都会把 Rust 存下的 basis 判成
     * stale。{@code DateTimeFormatter.ISO_INSTANT} 的亚秒正是 0/3/6/9 去尾零组，只差把
     * 尾部的 {@code Z} 换回 {@code +00:00}。
     */
    public static String rfc3339(java.time.OffsetDateTime at) {
        String s = DateTimeFormatter.ISO_INSTANT.format(at.toInstant());
        return s.endsWith("Z") ? s.substring(0, s.length() - 1) + "+00:00" : s;
    }

    /** phrase_bindings.rs:236-246 库里每条属性最后一次改动的时刻，给指纹用。 */
    public Map<UUID, OffsetDateTime> propertyVersions(UUID kbId) {
        Map<UUID, OffsetDateTime> out = new HashMap<>();
        for (var r : mapper.selectPropertyVersions(kbId)) {
            out.put(r.id(), r.updatedAt());
        }
        return out;
    }

    /** phrase_bindings.rs:270-288 Decision：一次判定要写的东西。 */
    public record Decision(UUID relationTypeId, String direction, String status, JsonNode votes,
                           String decidedBy, String basis, String marks, boolean marksAsked) {
    }

    /**
     * phrase_bindings.rs:294-307 {@code decide}：记下一条签名的判定（有则改）。
     * 返回是否写入了。<b>人的判定不被代理覆盖</b>：已有行是人判的而这次是代理，原样
     * 留着、返回 false。反过来人可以改代理的。
     */
    public boolean decide(UUID kbId, PhraseSignature sig, Decision d) {
        Boolean changed = joinTx.execute(t -> decideOn(kbId, sig, d));
        return Boolean.TRUE.equals(changed);
    }

    /**
     * phrase_bindings.rs:363-370 {@code decide_with_delivery}：人的判定与它自己的重算
     * 任务<b>同一事务</b>提交（0051）。一条判定配一个自己的 job——正在跑的那次对齐可能
     * 已经做完最后一次读，这条判定就没有任何人替它算类型化行。返回 job id；代理不能盖人
     * （decide_on 的规则）时什么都没写，返回 null。
     */
    public Long decideWithDelivery(UUID kbId, PhraseSignature sig, Decision d) {
        return decideWithDeliveryBudget(kbId, sig, d, 3);
    }

    /**
     * phrase_bindings.rs:374-395 预算单独成参只为了测「排队失败要连判定一起回滚」：0 会被
     * {@code enqueue} 拒掉，那正是一次发生在判定写入之后的真实失败。生产入口固定给 3。
     */
    public Long decideWithDeliveryBudget(UUID kbId, PhraseSignature sig, Decision d,
                                         int maxAttempts) {
        return joinTx.execute(t -> {
            if (!decideOn(kbId, sig, d)) {
                return null;
            }
            return jobs.enqueueWithMaxAttemptsTx(MATERIALIZE_KIND,
                    json.createObjectNode().put("kb_id", kbId.toString()), maxAttempts);
        });
    }

    /**
     * phrase_bindings.rs:398-453 {@code decide_on}：在调用方的事务上写判定（相关持久工作
     * 共享同一次提交）——观测回归的结果属于这次判定，不晚于判定提交。写入后同事务记
     * 短语回归（ontology_regressions::record_for_signature）。
     */
    public boolean decideOn(UUID kbId, PhraseSignature sig, Decision d) {
        String phrase = validateDecision(sig, d);
        int changed = mapper.upsertDecision(Uuids.nowV7(), kbId, phrase,
                sig.subjectTypeId(),
                sig.objectIsValue() ? null : sig.objectTypeId(),
                sig.objectIsValue(),
                d.relationTypeId(), d.direction(), d.status(), d.votes(),
                sig.count() > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sig.count(),
                sig.examples(),
                d.decidedBy(), d.basis(), d.marks(), d.marksAsked());
        if (changed > 0) {
            regressions.recordForSignature(kbId, sig);
        }
        return changed > 0;
    }

    /** phrase_bindings.rs:309-348 validate_decision：形状校验（文案逐字），返回归一短语。 */
    private static String validateDecision(PhraseSignature sig, Decision d) {
        if (!"bound".equals(d.status()) && !"none".equals(d.status())
                && !"undecided".equals(d.status())) {
            throw AppException.validation("unknown binding status " + d.status());
        }
        boolean bound = "bound".equals(d.status());
        if (bound != (d.relationTypeId() != null) || bound != (d.direction() != null)) {
            throw AppException.validation(
                    "a bound signature needs a property and a direction and an unbound one must not have them");
        }
        if (d.direction() != null && !"forward".equals(d.direction())
                && !"reverse".equals(d.direction())) {
            throw AppException.validation("unknown direction " + d.direction());
        }
        if ((d.marks() != null || d.marksAsked()) && !bound) {
            throw AppException.validation("only a bound signature says which end a moment marks");
        }
        if (d.marks() != null && !"start".equals(d.marks()) && !"end".equals(d.marks())
                && !"none".equals(d.marks())) {
            throw AppException.validation("unknown marks " + d.marks());
        }
        if (!"agent".equals(d.decidedBy()) && !"person".equals(d.decidedBy())) {
            throw AppException.validation("unknown decider " + d.decidedBy());
        }
        String phrase = normalize(sig.phrase());
        if (phrase.isEmpty()) {
            throw AppException.validation("an empty phrase binds nothing");
        }
        return phrase;
    }

    /**
     * phrase_bindings.rs:461-493 {@code record_marks}：给这一列之前绑上的签名补问 marks
     * 的结果（0053 修订）。这一问只问一刻标哪一端，不重判绑定。只写问的时候那条绑定——
     * 还是代理绑的、属性与方向没变、还没有值；途中人判过或重判过的，这一问的答案不算数，
     * 返回 false。
     */
    public boolean recordMarks(UUID kbId, Binding asked, String value) {
        if (value != null && !"start".equals(value) && !"end".equals(value)
                && !"none".equals(value)) {
            throw AppException.validation("unknown marks " + value);
        }
        return mapper.recordMarks(kbId, asked.phrase(), asked.subjectTypeId(),
                asked.objectTypeId(), asked.objectIsValue(), asked.relationTypeId(),
                asked.direction(), value) > 0;
    }
}
