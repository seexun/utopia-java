package tech.seexun.jknow.ingest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 解析器到了安全上限、正文有意只保留一部分（parsers.rs ParseWarning）。detail 说明
 * 省略的范围：spreadsheet.rows_truncated（前 2000 行）/ csv.records_truncated（前 1 万条）。
 */
public record ParseWarning(String kind, JsonNode detail) {

    public static final String SPREADSHEET_ROWS_TRUNCATED = "spreadsheet.rows_truncated";

    public static final String CSV_RECORDS_TRUNCATED = "csv.records_truncated";
}
