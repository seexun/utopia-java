package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 审查收口 M-2/M-3 的对价测试：码点遍历（CJK 扩展 B / 代理对文件名）与直收 markdown
 * 的链接消毒（百分号编码的危险目标）。
 */
class HydrationMinorTests {

    // ---- M-3：normalizeMarkdown = 归一 + sanitize_markdown_links（html.rs:537-605） ----

    /** %6Aavascript 经百分号解码后是 javascript——必须只留锚文本（Rust 同款用例）。 */
    @Test
    void normalizeMarkdownStripsPercentEncodedDangerousDestinations() throws Exception {
        String raw = "body text [click](%6Aavascript:evil) more";
        String out = HydrateRssEntryJob.normalizeMarkdown(raw);
        assertEquals("body text click more", out, "危险目标只剩锚文本");
        assertFalse(out.contains("%6Aavascript"), out);
        assertFalse(out.contains("]("), out);
    }

    @Test
    void sanitizeKeepsSafeLinksImagesAndPlainBrackets() {
        assertTrue(HydrateRssEntryJob.sanitizeMarkdownLinks("[a](https://example.com)")
                .contains("[a](https://example.com)"), "安全链接原样");
        assertTrue(HydrateRssEntryJob.sanitizeMarkdownLinks("![img](data:image/png;base64,x)")
                .contains("![img](data:"), "图片（! 前缀）不是链接消毒对象");
        assertEquals("[unclosed", HydrateRssEntryJob.sanitizeMarkdownLinks("[unclosed"));
        String dangerous = HydrateRssEntryJob.sanitizeMarkdownLinks("[t]( javascript:alert)");
        assertEquals("t", dangerous, "控制/空白前缀剥掉后仍认得出 scheme");
        assertFalse(HydrateRssEntryJob.sanitizeMarkdownLinks("[x](vbscript:e)")
                .contains("vbscript"));
        assertFalse(HydrateRssEntryJob.sanitizeMarkdownLinks("[x](file:///etc/passwd)")
                .contains("file:"));
    }

    /** 空 markdown 仍走 NON_SUBSTANTIVE（消毒不改变空判）。 */
    @Test
    void normalizeMarkdownStillRejectsEmpty() {
        assertThrows(HydrateRssEntryJob.ContentError.class,
                () -> HydrateRssEntryJob.normalizeMarkdown("   "));
    }

    // ---- M-2：codePoint 遍历 ----

    /** CJK 扩展 B（U+20000+，Java 里是代理对）按码点数——char 循环会数两遍且区间够不到。 */
    @Test
    void supplementaryPlaneCjkCountsOncePerCodePoint() {
        // 30 个互不相同的扩展 B 码点 ×4 = 120 个码点：够 80 词门也过「反复一小把
        // 导航词」查重（unique ≥ 20）
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            String ch = new String(Character.toChars(0x20000 + i * 7));
            body.append(ch.repeat(4));
        }
        assertTrue(HydrateRssEntryJob.isCjk(0x20000), "扩展 B 走得到区间");
        assertTrue(HydrateRssEntryJob.isSubstantive(body.toString(), "", false),
                "120 个 CJK 码点过 80/500 门（char 循环只会数出 240 个错值并误判代理对）");
    }

    /**
     * 文件名预算按<b>码点</b>：增补面 CJK（U+20BB7 𠮷，代理对、是字母）预算 80 码点
     * = 保留 80 个字（160 个 char）；按 char 数预算的旧形态只留 40 个字——一半的
     * 标题内容被无声截掉（M-2 的可观测面）。
     */
    @Test
    void hydrationFilenameCountsCodePointsForItsBudget() {
        String extB = new String(Character.toChars(0x20BB7));
        String name = HydrateRssEntryJob.hydrationFilename(extB.repeat(200));
        assertEquals(80, name.codePointCount(0, name.length()), "预算是 80 个码点");
        assertEquals(160, name.length(), "80 个增补面字符 = 160 个 char（不是 40 个字/80 char）");
        assertTrue(HydrateRssEntryJob.isCjk(0x20BB7), "扩展 B 走得到区间");
    }
}
