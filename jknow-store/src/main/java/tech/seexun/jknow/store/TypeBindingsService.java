package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
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
import tech.seexun.jknow.store.mapper.TypeBindingsMapper;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.Binding;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.BoundRow;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.ClassRow;
import tech.seexun.jknow.store.mapper.TypeBindingsMapper.KindWordSignature;

/**
 * 类别词绑到类的账本侧（type_bindings.rs 624 行对等，0044 决定 3–4 的第一片）。判定
 * 本身——问模型、两票一致才绑——在对齐器里，这里不认识模型。人的判定不被代理覆盖。
 *
 * <p>类别词在 Java 与 SQL 两侧用同一种归一：空白折一、去两端、小写。{@link #normalize}
 * 与 SQL 侧的 {@code lower(btrim(regexp_replace(...)))} 必须说同一件事。
 */
@Service
public class TypeBindingsService {

    private final TypeBindingsMapper mapper;
    private final JobsService jobs;
    private final TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            new com.fasterxml.jackson.databind.ObjectMapper();

    public TypeBindingsService(TypeBindingsMapper mapper, JobsService jobs,
                               PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.jobs = jobs;
        this.tx = new TransactionTemplate(txManager);
    }

    /** type_bindings.rs:33-39 归一一个类别词。 */
    public static String normalize(String kindWord) {
        return kindWord == null ? "" : String.join(" ", kindWord.trim().split("\\s+")).toLowerCase();
    }

    /** type_bindings.rs:57-109 库里每个 distinct 的类别词的签名。 */
    public List<KindWordSignature> signatures(UUID kbId) {
        return mapper.selectSignatures(kbId);
    }

    /** type_bindings.rs:129-140 库里全部绑定，按词序。 */
    public List<Binding> bindings(UUID kbId) {
        return mapper.selectBindings(kbId);
    }

    /**
     * type_bindings.rs:228-243 {@code stale}：不再成立的绑定（按时间戳的粗信号）。
     * 对齐的 worker 已不读它：时间戳看不见模型答题期间的编辑（#795），过期改按指纹判。
     */
    public List<String> stale(UUID kbId) {
        return mapper.selectStale(kbId);
    }

    /** type_bindings.rs:146-149 一个库此刻的类：每个类的 updated_at 与直接父类。 */
    public ClassSnapshot classSnapshot(UUID kbId) {
        ClassSnapshot snapshot = new ClassSnapshot();
        for (ClassRow r : mapper.selectClassSnapshot(kbId)) {
            snapshot.versions.put(r.id(), r.updatedAt());
            snapshot.parents.put(r.id(), r.parents() == null ? List.of() : r.parents());
        }
        return snapshot;
    }

    /**
     * type_bindings.rs:145-209 {@code ClassSnapshot}：判定的指纹从它算——开跑时在调模型
     * 之前的快照事务里读一份，写判定时在写的事务里再读一份，两份算出来不一样就是答题
     * 期间候选类变了。
     */
    public static final class ClassSnapshot {
        final Map<UUID, java.time.OffsetDateTime> versions = new HashMap<>();
        final Map<UUID, List<UUID>> parents = new HashMap<>();

        /**
         * type_bindings.rs:176-190 {@code basis}：给模型看的每个候选类的 id、updated_at
         * 与祖先闭包。候选的先后不算；候选已经不在了记成 gone，同样算变了。父边的增删
         * 不碰 updated_at，所以闭包要单独算进来。
         */
        public String basis(List<UUID> candidates) {
            List<String> parts = new ArrayList<>();
            for (UUID id : candidates) {
                java.time.OffsetDateTime at = versions.get(id);
                if (at == null) {
                    parts.add(id + "@gone");
                } else {
                    List<String> up = new ArrayList<>();
                    for (UUID a : ancestors(id)) {
                        up.add(a.toString());
                    }
                    parts.add(id + "@" + PhraseBindingsService.rfc3339(at)
                            + "^" + String.join(",", up));
                }
            }
            parts.sort(null);
            // dedup（Rust parts.dedup()：sort 后相邻去重）
            List<String> distinct = new ArrayList<>();
            for (String p : parts) {
                if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(p)) {
                    distinct.add(p);
                }
            }
            return fingerprint(String.join(";", distinct));
        }

        /**
         * type_bindings.rs:194-208 一个类的全部祖先（不含自己），排好序。多继承与菱形按
         * 并集走；编辑器不允许环，见过就不再走，万一有环也走得完。
         */
        List<UUID> ancestors(UUID id) {
            List<UUID> seen = new ArrayList<>();
            List<UUID> stack = new ArrayList<>(parents.getOrDefault(id, List.of()));
            while (!stack.isEmpty()) {
                UUID p = stack.remove(stack.size() - 1);
                if (p.equals(id) || seen.contains(p)) {
                    continue;
                }
                seen.add(p);
                stack.addAll(parents.getOrDefault(p, List.of()));
            }
            seen.sort(null);
            return seen;
        }
    }

    /**
     * type_bindings.rs:213-220 与 {@code phrase_bindings::basis_of} 同一个哈希：FNV-1a
     * 64 位。只是缓存失效的键，不是安全用途。
     */
    static String fingerprint(String text) {
        long h = 0xcbf29ce484222325L;
        for (byte b : text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return String.format("%016x", h);
    }

    /**
     * type_bindings.rs:251-265 {@code decide}：记下一个类别词的判定（有则改）。返回是否
     * 写入了。这里写下的判定不带指纹：代理的判定走 {@link #decideAndApplyIfCurrent}。
     */
    public boolean decide(UUID kbId, String kindWord, List<String> words, UUID typeId,
                          String status, JsonNode votes, String decidedBy) {
        return decideWithBasis(kbId, kindWord, words, typeId, status, votes, decidedBy, null);
    }

    private boolean decideWithBasis(UUID kbId, String kindWord, List<String> words, UUID typeId,
                                    String status, JsonNode votes, String decidedBy, String basis) {
        if (!"bound".equals(status) && !"none".equals(status) && !"undecided".equals(status)) {
            throw AppException.validation("unknown binding status " + status);
        }
        if ("bound".equals(status) != (typeId != null)) {
            throw AppException.validation(
                    "a bound kind word needs a class and an unbound one must not have one");
        }
        if (!"agent".equals(decidedBy) && !"person".equals(decidedBy)) {
            throw AppException.validation("unknown decider " + decidedBy);
        }
        String word = normalize(kindWord);
        if (word.isEmpty()) {
            throw AppException.validation("an empty kind word binds nothing");
        }
        return mapper.upsertDecision(Uuids.nowV7(), kbId, word, words, typeId, status, votes,
                decidedBy, basis) > 0;
    }

    /**
     * type_bindings.rs:334-351 {@code decide_and_apply}：判定与实体投影一个事务——
     * 绑定行锁到投影写完为止，迟到的代理判定盖不了人刚提交的决定。
     */
    public boolean decideAndApply(UUID kbId, String kindWord, List<String> words, UUID typeId,
                                  String status, JsonNode votes, String decidedBy) {
        Boolean written = tx.execute(t -> writeDecisionAndProjection(kbId, kindWord, words,
                typeId, status, votes, decidedBy, null));
        return Boolean.TRUE.equals(written);
    }

    /** type_bindings.rs:354-363 Acceptance：代理判定交到 {@link #decideAndApplyIfCurrent} 的下场。 */
    public enum Acceptance {
        /** 判定与投影都写了，指纹一起存。 */
        WRITTEN,
        /** 人判过这个词：代理的答案不覆盖。 */
        KEPT_PERSON,
        /** 模型答题期间候选类变了：答案说的是已不存在的输入，什么也不写。 */
        MOVED
    }

    /**
     * type_bindings.rs:378-419 {@code decide_and_apply_if_current}（#795）：代理判定只为
     * 它真正读到的输入收下。候选类先 FOR SHARE 再写绑定行（与删类同一顺序，不会死锁）；
     * 指纹按当前行重算，对不上就不写。之后提交的父边或新类拦不住——存下的指纹从此对
     * 不上当前输入，下一轮对齐发现判定过期会再问。
     */
    public Acceptance decideAndApplyIfCurrent(UUID kbId, String kindWord, List<String> words,
                                              UUID typeId, String status, JsonNode votes,
                                              String basis, List<UUID> candidates) {
        Boolean written = tx.execute(t -> {
            mapper.lockCandidatesForShare(kbId, candidates);
            if (!classSnapshot(kbId).basis(candidates).equals(basis)) {
                return null;
            }
            return writeDecisionAndProjection(kbId, kindWord, words, typeId, status, votes,
                    "agent", basis);
        });
        if (written == null) {
            return Acceptance.MOVED;
        }
        return written ? Acceptance.WRITTEN : Acceptance.KEPT_PERSON;
    }

    /**
     * type_bindings.rs:429-489 {@code decide_and_apply_human}：人定一条类别词（lock_timeout
     * 2s——等锁可以但不许钉死一条连接），判定、投影与它引出的短语对齐任务一起提交。
     * 撞上 55P03（lock_timeout）翻译成 alignment_busy（CodedConflict）。
     */
    public boolean decideAndApplyHuman(UUID kbId, String kindWord, UUID typeId, JsonNode votes) {
        try {
            Boolean written = tx.execute(t -> {
                mapper.setTimeout2s();
                boolean w = writeDecisionAndProjection(kbId, kindWord, List.of(), typeId,
                        typeId != null ? "bound" : "none", votes, "person", null);
                if (w) {
                    jobs.enqueueUnlessQueuedTx("align_phrases",
                            json.createObjectNode().put("kb_id", kbId.toString()));
                }
                return w;
            });
            return Boolean.TRUE.equals(written);
        } catch (org.springframework.dao.DataAccessException e) {
            if (e.getCause() instanceof java.sql.SQLException sql
                    && "55P03".equals(sql.getSQLState())) {
                throw AppException.codedConflict("alignment_busy",
                        "This kind word is being updated by another operation. Please try again shortly.");
            }
            throw e;
        }
    }

    /**
     * type_bindings.rs:492-526 {@code write_decision_and_projection}：判定写入后，绑上
     * 就投影到实体（apply），否则解除投影（unapply）。加入调用方事务。
     */
    private boolean writeDecisionAndProjection(UUID kbId, String kindWord, List<String> words,
                                               UUID typeId, String status, JsonNode votes,
                                               String decidedBy, String basis) {
        boolean written = decideWithBasis(kbId, kindWord, words, typeId, status, votes, decidedBy,
                basis);
        if (written) {
            if (typeId != null) {
                applyToEntities(kbId, kindWord, typeId);
            } else {
                unapplyFromEntities(kbId, kindWord);
            }
        }
        return written;
    }

    /** type_bindings.rs:533-554 {@code apply}：把绑上的类写到该类别词下每个活着的、人没定过类的实体上。 */
    public int applyToEntities(UUID kbId, String kindWord, UUID typeId) {
        return mapper.applyToEntities(kbId, normalize(kindWord), typeId);
    }

    /** type_bindings.rs:558-576 {@code unapply}：解绑只动 aligned 的行。 */
    public int unapplyFromEntities(UUID kbId, String kindWord) {
        return mapper.unapplyFromEntities(kbId, normalize(kindWord));
    }

    /** type_bindings.rs:579-588 {@code bound_map}：抽取时用的绑定表。 */
    public Map<String, UUID> boundMap(UUID kbId) {
        Map<String, UUID> out = new HashMap<>();
        for (BoundRow r : mapper.selectBoundMap(kbId)) {
            out.put(r.kindWord(), r.typeId());
        }
        return out;
    }

    /**
     * type_bindings.rs:595-621 {@code propose}：没有类对得上的类别词，把模型提的类名记在
     * 实体的 proposed_type 上（类型消解读它）。只写第一次、最长 60 字；spelling 是文档
     * 的写法（空写法没有提议）。
     */
    public int propose(UUID kbId, String kindWord, String spelling) {
        String trimmed = spelling == null ? "" : spelling.trim();
        if (trimmed.isEmpty()) {
            throw AppException.validation("a proposal needs the document's spelling");
        }
        return mapper.proposeType(kbId, normalize(kindWord), trimmed);
    }
}
