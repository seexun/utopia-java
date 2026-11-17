package tech.seexun.jknow.ingest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * csv/tsv（parsers.rs csv_text 的对等）：首条记录当列头（csv 的惯例，哪怕列头是年份），
 * 限前 1 万条记录（超出告警 csv.records_truncated），渲染成 Markdown 表。
 * 解析按 RFC4180 精神：引号包裹、"" 转义、字段内可含分隔符与换行；行宽可变（flexible）。
 */
final class CsvText {

    static final int RECORD_LIMIT = 10_000;

    private CsvText() {
    }

    static Result parse(byte[] bytes, boolean tsv) throws IOException {
        String decoded = TextCodecs.plainText(bytes);
        char delim = tsv ? '\t' : ',';
        List<List<String>> rows = new ArrayList<>();
        int total = 0;
        Parser p = new Parser(decoded, delim);
        List<String> record = p.nextRecord();
        while (record != null) {
            total++;
            if (rows.size() < RECORD_LIMIT) {
                rows.add(record);
            }
            record = p.nextRecord();
        }
        List<ParseWarning> warnings = new ArrayList<>();
        if (total > RECORD_LIMIT) {
            warnings.add(new ParseWarning(ParseWarning.CSV_RECORDS_TRUNCATED,
                    Json.values(new String[][] {
                        {"records_read", String.valueOf(rows.size())},
                        {"records_total", String.valueOf(total)},
                        {"records_omitted", String.valueOf(total - rows.size())}})));
        }
        String md = MarkdownTables.renderRecords(rows);
        String text = md.isEmpty()
                ? rows.stream().map(r -> String.join(" | ", r)).reduce((a, b) -> a + "\n" + b).orElse("")
                        + "\n"
                : md + "\n";
        return new Result(text, warnings);
    }

    record Result(String text, List<ParseWarning> warnings) {
    }

    /** 手写记录读取器：引号态/裸态两档，坏引号宽容（Rust csv crate 同样不因缺引号失败）。 */
    private static final class Parser {
        private final String s;
        private final char delim;
        private int i;

        Parser(String s, char delim) {
            this.s = s;
            this.delim = delim;
        }

        List<String> nextRecord() {
            if (i >= s.length()) {
                return null;
            }
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean any = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (quoted) {
                    if (c == '"') {
                        if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                            field.append('"');
                            i += 2;
                        } else {
                            quoted = false;
                            i++;
                        }
                    } else {
                        field.append(c);
                        i++;
                    }
                    continue;
                }
                if (c == '"' && field.length() == 0 && !any) {
                    quoted = true;
                    any = true;
                    i++;
                    continue;
                }
                if (c == delim) {
                    fields.add(field.toString());
                    field.setLength(0);
                    any = false;
                    i++;
                    continue;
                }
                if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    i++;
                    fields.add(field.toString());
                    return fields;
                }
                field.append(c);
                any = true;
                i++;
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
