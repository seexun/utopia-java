package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.query.SchemaColumn;

/** 探索的纯函数族（server/mappings.rs 内联 mod tests 3 例逐字移植，断言不改写）。 */
class ExploreMappingsPureTests {

    private static SchemaColumn column(boolean pk, String fk, String comment) {
        return new SchemaColumn("a", "t", "id", "integer", comment, pk, fk);
    }

    @Test
    void a_column_line_marks_its_keys_before_the_comment() {
        assertEquals("  id integer\n", ExploreMappingsJob.columnLine(column(false, null, null)));
        assertEquals("  id integer [PK] -- Order id\n",
                ExploreMappingsJob.columnLine(column(true, null, "Order id")));
        assertEquals("  id integer [FK→a.orders]\n",
                ExploreMappingsJob.columnLine(column(false, "a.orders", null)));
        // 一对一的扩展表：主键同时是外键，两个都标
        assertEquals("  id integer [PK, FK→a.p1]\n",
                ExploreMappingsJob.columnLine(column(true, "a.p1", null)));
        // 注释里写着 PK 的普通列，与标记分得开
        assertEquals("  id integer -- PK\n",
                ExploreMappingsJob.columnLine(column(false, null, "PK")));
    }

    @Test
    void a_description_is_kept_only_when_it_says_something() {
        Map.Entry<String, List<String>> parsed = ExploreMappingsJob.parseDescription(
                "```json\n{\"description\": \"dw.dwd_ord_dtl: one row per order line.\", "
                        + "\"questions\": [\" Which ord_st values count? \", \"\", "
                        + "\"Exclude is_test = 1?\"]}\n```");
        assertEquals("dw.dwd_ord_dtl: one row per order line.", parsed.getKey());
        // 空问题丢掉、首尾空白剥掉，顺序保留
        assertEquals(List.of("Which ord_st values count?", "Exclude is_test = 1?"),
                parsed.getValue());
        // 空描述整个不要：一个空描述盖掉上一轮的好描述，比没写更糟
        assertNull(ExploreMappingsJob.parseDescription(
                "{\"description\": \"  \", \"questions\": []}"));
        assertNull(ExploreMappingsJob.parseDescription("not json"));
        // 问题没给也行，描述照收
        assertEquals(0, ExploreMappingsJob.parseDescription("{\"description\": \"x\"}")
                .getValue().size());
    }

    @Test
    void a_bigger_schema_gets_a_bigger_cap() {
        // 小库不缩水：从前写死的 12 在这一端是对的
        assertEquals(12, ExploreMappingsJob.proposalCap(1));
        assertEquals(12, ExploreMappingsJob.proposalCap(4));
        // 八张表的 TPC-H：从前 12 条封顶，二十四条真值只覆盖了 6 条（#501）
        assertEquals(24, ExploreMappingsJob.proposalCap(8));
        // TPC-DS 二十四张表
        assertEquals(60, ExploreMappingsJob.proposalCap(24));
        // 再大也到此为止：提示词与一轮人工审阅都有自己的上限
        assertEquals(60, ExploreMappingsJob.proposalCap(300));
        // 一张表都没扫见（源连不上、schema 是空的）也不该是 0——0 条上限会把「连不上」
        // 变成「模型什么都没提」，两件事又混在一起了
        assertEquals(12, ExploreMappingsJob.proposalCap(0));
    }
}
