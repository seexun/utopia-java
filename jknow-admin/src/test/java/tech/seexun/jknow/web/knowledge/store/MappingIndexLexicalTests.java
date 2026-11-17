package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.entity.MappingText;

/**
 * 移植自 mapping_index.rs 内联 2 例（断言逐字不改写）：tokens 英文按空白/标点、中文按
 * 两字；lexical_rank 命中词最多的口径排最前（同分按 id，一个词都对不上给空——调用方
 * 降级到按字典序）。
 */
class MappingIndexLexicalTests {

    private static MappingText t(long id, String name, String summary, String expr) {
        return new MappingText(new UUID(0, id), name, summary, null, "dw.dwd_ord_dtl", expr);
    }

    @Test
    void aQuestionIsCutIntoWordsInEitherLanguage() {
        // 输出是排好序去了重的；期望值同样排一遍，不靠手排码点
        List<String> expected = new ArrayList<>(List.of("what", "is", "our", "total", "gmv"));
        expected.sort(Comparator.naturalOrder());
        assertEquals(expected, MappingsService.tokens("What is our total GMV?"));
        // 中文按两字；单个 ASCII 字符不算词
        List<String> cjk = new ArrayList<>(List.of("扣掉", "掉退", "退款", "款之", "之后",
                "后的", "的净", "净销", "销售", "售额", "额是", "是多", "多少"));
        cjk.sort(Comparator.naturalOrder());
        assertEquals(cjk, MappingsService.tokens("扣掉退款之后的净销售额是多少？"));
        assertEquals(List.of("gmv"), MappingsService.tokens("a x GMV"));
    }

    @Test
    void theDefinitionThatSharesTheMostWordsComesFirst() {
        List<MappingText> texts = List.of(
                t(1, "GMV", "已支付、非测试单的实付金额，元", "sum(amt_pay)"),
                t(2, "净销售额", "GMV 扣掉退款", "sum(amt_pay - amt_rfnd)"),
                t(3, "客单价", "净销售额除以有效订单数", "…"),
                t(4, "运费收入", "已支付订单的运费", "sum(amt_frght)"));
        List<UUID> got = MappingsService.lexicalRank("扣掉退款之后的净销售额是多少", texts, 8);
        assertEquals(new UUID(0, 2), got.get(0),
                "净销售额那条命中「扣掉」「退款」「净销」「销售」「售额」");
        assertTrue(got.contains(new UUID(0, 3)), "客单价的说明里也有「净销售额」");
        assertFalse(got.contains(new UUID(0, 4)), "运费一个词都不沾");
        // 一个词都对不上：空，让调用方降级到按字典序
        assertTrue(MappingsService.lexicalRank("zzz", texts, 8).isEmpty());
    }
}
