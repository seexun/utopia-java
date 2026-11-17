package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 蕴含规则 SQL（implication_rules.rs 303 行逐字搬迁，0044 决定 3 第五片）：propose 的
 * upsert、list/get、decide_with_delivery 的 UPDATE、pending_readings、record_reading。
 * 每条 XML 内注明 file:line。
 */
public interface ImplicationRulesMapper {

    /**
     * implication_rules.rs:105-117 propose 的 upsert：同一条规则已经有人判过的原样留着
     * （返回 null）；代理自己的旧行被新提案覆盖。返回 id（未写入时 null）。
     */
    UUID upsertProposal(@Param("id") UUID id, @Param("kbId") UUID kbId,
                        @Param("trigger") String trigger, @Param("phrase") String phrase,
                        @Param("subjectTypeId") UUID subjectTypeId,
                        @Param("objectTypeId") UUID objectTypeId,
                        @Param("objectIsValue") boolean objectIsValue,
                        @Param("concludePropertyId") UUID concludePropertyId,
                        @Param("reading") String reading, @Param("status") String status,
                        @Param("votes") JsonNode votes, @Param("basis") String basis,
                        @Param("statementCount") int statementCount,
                        @Param("examples") List<String> examples);

    /** implication_rules.rs:138-149 list（status 可空）。 */
    List<Rule> selectList(@Param("kbId") UUID kbId, @Param("status") String status);

    /** implication_rules.rs:152-163 get。 */
    Rule selectGet(@Param("kbId") UUID kbId, @Param("id") UUID id);

    /** implication_rules.rs:41-58 Rule。 */
    record Rule(UUID id, String trigger, String phrase, UUID subjectTypeId, UUID objectTypeId,
                boolean objectIsValue, UUID concludePropertyId, String reading, String status,
                JsonNode votes, String decidedBy, String basis, int statementCount,
                List<String> examples, OffsetDateTime decidedAt) {
    }

    /**
     * implication_rules.rs:176-187 decide_with_delivery 的 UPDATE（人批/驳一条规则）。
     * 返回一行 reading（空列表 = 规则不在；元素可为 null——规则在而不要读数）。
     */
    java.util.List<String> decideByPerson(@Param("kbId") UUID kbId, @Param("id") UUID id,
                                          @Param("status") String status,
                                          @Param("votes") JsonNode votes);

    /**
     * implication_rules.rs:221-240 pending_readings：已批准的规则里还没有缓存的
     * (读数, 字)——短语规则读陈述的宾语，类别词规则读类别词自己。答过「读不出来」的
     * 也算缓存过。
     */
    List<PendingReading> selectPendingReadings(@Param("kbId") UUID kbId);

    /** implication_rules.rs:212-216 PendingReading。 */
    record PendingReading(String reading, String phrase) {
    }

    /**
     * implication_rules.rs:264-271 record_reading：记一条读数的答案（读不出来也记，
     * 别再问）。ON CONFLICT DO UPDATE。
     */
    int upsertReading(@Param("kbId") UUID kbId, @Param("reading") String reading,
                      @Param("phrase") String phrase, @Param("entityId") UUID entityId,
                      @Param("value") JsonNode value);
}
