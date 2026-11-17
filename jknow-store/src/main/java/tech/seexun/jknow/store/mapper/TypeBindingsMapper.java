package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 类别词绑定 SQL（type_bindings.rs 624 行逐字搬迁，0044 决定 3–4 的第一片）：
 * signatures（一趟聚出写法/例名/短语，#1096）、bindings、class_snapshot 行、stale、
 * decide_with_basis 的 upsert、apply/unapply/propose、bound_map。每条 XML 内注明 file:line。
 */
public interface TypeBindingsMapper {

    /** type_bindings.rs:57-109 每个类别词的签名：活着的、带类别词的实体分组。 */
    List<KindWordSignature> selectSignatures(@Param("kbId") UUID kbId);

    /** type_bindings.rs:42-54 KindWordSignature。 */
    record KindWordSignature(String kindWord, List<String> words, long count,
                             List<String> examples, List<String> phrases) {
    }

    /** type_bindings.rs:129-140 库里全部绑定（按词序）。 */
    List<Binding> selectBindings(@Param("kbId") UUID kbId);

    /** type_bindings.rs:113-126 Binding。 */
    record Binding(String kindWord, UUID typeId, String status, OffsetDateTime decidedAt,
                   String decidedBy, String basis) {
    }

    /** type_bindings.rs:156-160 class_snapshot 行：类的 updated_at 与直接父类。 */
    List<ClassRow> selectClassSnapshot(@Param("kbId") UUID kbId);

    /** type_bindings.rs:157-159 (id, updated_at, parents)。 */
    record ClassRow(UUID id, OffsetDateTime updatedAt, List<UUID> parents) {
    }

    /** type_bindings.rs:229-242 不再成立的绑定（词序）。 */
    List<String> selectStale(@Param("kbId") UUID kbId);

    /**
     * type_bindings.rs:300-326 decide_with_basis 的 upsert（words 空保留已有写法；
     * 人的判定不被代理覆盖）。返回行数。
     */
    int upsertDecision(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("kindWord") String kindWord, @Param("words") List<String> words,
                       @Param("typeId") UUID typeId, @Param("status") String status,
                       @Param("votes") JsonNode votes, @Param("decidedBy") String decidedBy,
                       @Param("basis") String basis);

    /** type_bindings.rs:390-394 候选类 FOR SHARE（先锁类再写判定行，与删类同一顺序）。 */
    List<UUID> lockCandidatesForShare(@Param("kbId") UUID kbId,
                                      @Param("candidates") List<UUID> candidates);

    /** type_bindings.rs:438-439 {@code SET LOCAL lock_timeout = '2s'}（人定词的那次点击）。 */
    void setTimeout2s();

    /**
     * type_bindings.rs:539-548 apply：把绑上的类写到这个类别词下每个活着的、人没定过类
     * 的实体上（proposed_type 一并清掉）。返回改动数。
     */
    int applyToEntities(@Param("kbId") UUID kbId, @Param("kindWord") String kindWord,
                        @Param("typeId") UUID typeId);

    /**
     * type_bindings.rs:563-571 unapply：绑定变成 none 或失效时，按它对齐上去的实体失去
     * 那个类（只动 aligned 的行）。返回改动数。
     */
    int unapplyFromEntities(@Param("kbId") UUID kbId, @Param("kindWord") String kindWord);

    /** type_bindings.rs:581-585 抽取时用的绑定表：归一过的类别词 → 类 id（只有绑上的）。 */
    List<BoundRow> selectBoundMap(@Param("kbId") UUID kbId);

    /** type_bindings.rs:581-584 (kind_word, type_id)。 */
    record BoundRow(String kindWord, UUID typeId) {
    }

    /**
     * type_bindings.rs:607-613 propose：没有类对得上的类别词，把模型提的类名记在实体的
     * proposed_type 上（只写第一次、最长 60 字）。返回写上的行数。
     */
    int proposeType(@Param("kbId") UUID kbId, @Param("kindWord") String kindWord,
                    @Param("spelling") String spelling);
}
