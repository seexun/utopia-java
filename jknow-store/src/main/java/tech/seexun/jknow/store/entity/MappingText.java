package tech.seexun.jknow.store.entity;

import java.util.UUID;

/** 口径检索文字行（mappings.rs:330-338 MappingText；confirmed_texts 485-496）。 */
public record MappingText(
        UUID id,
        String conceptName,
        String summary,
        String unit,
        String tableName,
        String expr) {

    /** mapping_index.rs:340-351 embed_text（向量文本；M1 无嵌入模型不使用，保留对等定义）。 */
    public String embedText() {
        StringBuilder s = new StringBuilder(conceptName);
        if (summary != null && !summary.trim().isEmpty()) {
            s.append(": ").append(summary.trim());
        }
        if (unit != null && !unit.trim().isEmpty()) {
            s.append(" (").append(unit.trim()).append(')');
        }
        return s.toString();
    }

    /** mapping_index.rs:353-363 lexical_text（词面检索的 haystack）。 */
    public String lexicalText() {
        StringBuilder s = new StringBuilder();
        for (String part : new String[] {conceptName, summary, tableName, expr}) {
            if (part != null) {
                if (s.length() > 0) {
                    s.append(' ');
                }
                s.append(part);
            }
        }
        return s.toString();
    }
}
