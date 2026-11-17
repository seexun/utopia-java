package tech.seexun.jknow.store.mapper;

import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 时间提及 SQL（time_mentions.rs 逐字搬迁——任务 2 只落 record/for_facts 两刀；
 * 解算/解释（set_resolution/set_interpretation）随任务 8 落）。
 */
public interface TimeMentionsMapper {

    /**
     * time_mentions.rs:70-85 record：记一条时间提及。char_start 是字符偏移，由服务端
     * 在块里搜出来，不取模型报的数；role 是 when/ended。同一条陈述在同一块的同一位置、
     * 同一个槽只有一行；再记一次回那一行的 id。
     */
    UUID insertMention(@Param("id") UUID id, @Param("kbId") UUID kbId,
                       @Param("factId") UUID factId, @Param("chunkId") UUID chunkId,
                       @Param("text") String text, @Param("charStart") int charStart,
                       @Param("role") String role);

    /**
     * time_mentions.rs:97-114 for_facts：一批陈述各自提到的时间词，按事实 id 取回；
     * 块内按出现位置排。
     */
    List<TimeMentionRow> selectForFacts(@Param("factIds") List<UUID> factIds);

    /** time_mentions.rs:14-29 TimeMention（解算/解释列没读过为空）。 */
    record TimeMentionRow(UUID id, UUID factId, UUID chunkId, String text, int charStart,
                          String role, String shape, JsonNode reference, String granularity,
                          String grade, java.time.OffsetDateTime resolvedFrom,
                          String resolvedFromPrecision, java.time.OffsetDateTime resolvedTo,
                          String resolvedToPrecision) {
    }

    /** time_mentions.rs:105-111 MentionToResolve：字、位置、槽，和它所在的那句话。 */
    record MentionToResolveRow(UUID id, UUID factId, UUID chunkId, String text, int charStart,
                                String role, String sentence) {
    }

    /**
     * time_mentions.rs:117-130 for_document 逐字：一篇文档里所有等着读的提及——每条活着的
     * 开放陈述在这篇文档的活分块上的每一条提及，按分块顺序、块内位置排（解算沿文档读下去，
     * 锚点从前一块带到后一块，0045 决定 3）。
     */
    List<MentionToResolveRow> selectForDocument(@Param("documentId") UUID documentId);

    /**
     * time_mentions.rs:133-145 set_interpretation 逐字：模型对这条提及的解释（形状、参照、
     * 粒度），照存。取值是抽取合同的词汇，库不认；日期算术不在这里。
     */
    void setInterpretation(@Param("id") UUID id, @Param("shape") String shape,
                           @Param("reference") JsonNode reference,
                           @Param("granularity") String granularity);

    /**
     * time_mentions.rs:147-166 set_resolution 逐字（值先按精度截断——调用方传已截断的值，
     * 与 {@code graph::truncate_to} 同一套）：等级和两端各自的时刻与精度，resolved_at 记
     * 此刻。grade C 四列全空；结束端 (None, Some("unknown")) 是结束了不知哪天。
     */
    void setResolution(@Param("id") UUID id, @Param("grade") String grade,
                       @Param("from") java.time.OffsetDateTime from,
                       @Param("fromPrecision") String fromPrecision,
                       @Param("to") java.time.OffsetDateTime to,
                       @Param("toPrecision") String toPrecision);
}
