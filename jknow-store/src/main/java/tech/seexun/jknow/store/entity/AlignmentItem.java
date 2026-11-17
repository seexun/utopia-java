package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 对齐队列条目（alignment_queue.rs:17-63 AlignmentItem 的 serde internally-tagged 形状：
 * {@code #[serde(tag = "kind", rename_all = "snake_case")]} → JSON 带 kind 键，
 * 值 phrase / kind_word / rule）。三种混排按 decided_at 升序、内存分页（SQL 不带 LIMIT）。
 */
public sealed interface AlignmentItem permits AlignmentItem.Phrase, AlignmentItem.KindWord, AlignmentItem.Rule {

    /** 等人定的短语签名（含 #966 的 bound-marks-NULL-需问形态）。 */
    record Phrase(
            String kind,
            UUID id,
            String phrase,
            String subjectClass,
            String objectClass,
            boolean objectIsValue,
            int statementCount,
            List<String> examples,
            JsonNode votes,
            OffsetDateTime decidedAt,
            String boundTo,
            String direction) implements AlignmentItem {
    }

    /** 等人定的类别词（例名/短语来自签名表 type_bindings.rs:57-110，按词对上）。 */
    record KindWord(
            String kind,
            String kindWord,
            List<String> words,
            List<String> examples,
            List<String> phrases,
            long entityCount,
            JsonNode votes,
            OffsetDateTime decidedAt) implements AlignmentItem {
    }

    /** 对齐器提的一条蕴含规则（0044 决定 3 第五片）。 */
    record Rule(
            String kind,
            UUID id,
            String trigger,
            String phrase,
            String subjectClass,
            String objectClass,
            boolean objectIsValue,
            String property,
            String propertyLabel,
            String reading,
            int statementCount,
            List<String> examples,
            JsonNode votes,
            OffsetDateTime decidedAt) implements AlignmentItem {
    }
}
