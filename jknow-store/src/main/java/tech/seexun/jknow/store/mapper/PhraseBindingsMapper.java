package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 关系短语绑定 SQL（phrase_bindings.rs 逐字搬迁）：signatures（一趟聚出计数与例句，
 * #1096）、signature_of、bindings、property_versions、stale、decide_on 的 upsert、
 * record_marks。每条 XML 内注明 file:line。判定编排在 {@code PhraseBindingsService}。
 */
public interface PhraseBindingsMapper {

    /** phrase_bindings.rs:55-111 签名：活着的开放陈述按短语、两端的类、宾语是不是字面值分组。 */
    List<PhraseSignature> selectSignatures(@Param("kbId") UUID kbId);

    /** phrase_bindings.rs:34-52 PhraseSignature（归一短语、两端类、计数、例句/引文/陈述 id）。 */
    record PhraseSignature(String phrase, UUID subjectTypeId, String subjectTypeKey,
                           UUID objectTypeId, String objectTypeKey, boolean objectIsValue,
                           long count, List<String> examples, List<String> quotes,
                           List<UUID> exampleStatementIds) {

        /**
         * phrase_bindings.rs:174-182 签名键：短语 + 两端的类 + 宾语是不是字面值。
         * 返回 List 而不是数组——数组当 Map 键是身份比较，两份同内容的键对不上
         * （Arrays.asList 的 equals/hashCode 按内容；两端类型可为 null，List.of 不收）。
         */
        public java.util.List<Object> key() {
            return java.util.Arrays.asList(phrase, subjectTypeId, objectTypeId, objectIsValue);
        }
    }

    /** phrase_bindings.rs:115-133 按 id 取一条绑定的签名（人在队列里定它时用）。 */
    PhraseSignature selectSignatureOf(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** phrase_bindings.rs:185-195 库里全部绑定（按短语序）。 */
    List<Binding> selectBindings(@Param("kbId") UUID kbId);

    /** phrase_bindings.rs:137-159 Binding（一条签名判成了什么）。 */
    record Binding(String phrase, UUID subjectTypeId, UUID objectTypeId, boolean objectIsValue,
                   UUID relationTypeId, String direction, String status,
                   OffsetDateTime decidedAt, String decidedBy, String basis,
                   String marks, OffsetDateTime marksAskedAt) {

        /** phrase_bindings.rs:162-170 与签名对上的键（List 的原因见 PhraseSignature.key）。 */
        public java.util.List<Object> key() {
            return java.util.Arrays.asList(phrase, subjectTypeId, objectTypeId, objectIsValue);
        }
    }

    /** phrase_bindings.rs:236-246 每条属性最后一次改动的时刻（给指纹用）。 */
    List<PropertyVersion> selectPropertyVersions(@Param("kbId") UUID kbId);

    /** phrase_bindings.rs:240-245 (id, updated_at)。 */
    record PropertyVersion(UUID id, OffsetDateTime updatedAt) {
    }

    /** phrase_bindings.rs:251-268 不再成立的绑定（绑到的属性改过/负向判定后库里有新属性）。 */
    List<Binding> selectStale(@Param("kbId") UUID kbId);

    /**
     * phrase_bindings.rs:405-447 decide_on 的 upsert（人的判定不被代理覆盖：
     * {@code WHERE NOT (decided_by='person' AND EXCLUDED.decided_by='agent')}）。
     * 返回行数（0 = 代理盖人被挡）。
     */
    int upsertDecision(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("phrase") String phrase,
                       @Param("subjectTypeId") UUID subjectTypeId,
                       @Param("objectTypeId") UUID objectTypeId,
                       @Param("objectIsValue") boolean objectIsValue,
                       @Param("relationTypeId") UUID relationTypeId,
                       @Param("direction") String direction, @Param("status") String status,
                       @Param("votes") JsonNode votes, @Param("statementCount") int statementCount,
                       @Param("examples") List<String> examples,
                       @Param("decidedBy") String decidedBy, @Param("basis") String basis,
                       @Param("marks") String marks, @Param("marksAsked") boolean marksAsked);

    /**
     * phrase_bindings.rs:472-492 record_marks：给这一列之前绑上的签名补问 marks 的结果
     * （只写问的时候那条绑定：还是代理绑的、属性与方向没变、还没有值）。返回行数。
     */
    int recordMarks(@Param("kbId") UUID kbId, @Param("phrase") String phrase,
                    @Param("subjectTypeId") UUID subjectTypeId,
                    @Param("objectTypeId") UUID objectTypeId,
                    @Param("objectIsValue") boolean objectIsValue,
                    @Param("relationTypeId") UUID relationTypeId,
                    @Param("direction") String direction, @Param("marks") String marks);
}
