package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 生成驱动的裁剪与纯函数：chat_generation.rs 内联 3 例中的 completedExchange 两例纯
 * 断言，外加 cited/carried 号判定。Rust 第三例
 * stopping_only_carries_sources_when_this_turn_did_not_gather 要真实驱动的停止档，
 * Java 侧未移植单列——collect 的「本轮没采集就沿用上一条 sources」分支因此没有直驱
 * 断言（停止的存档面由 ChatRegistryAndStreamTests 的停止两例覆盖，本类不碰驱动）。
 */
class ChatGenerationTests {

    private final ChatFrames frames = new ChatFrames();

    private JsonNode json(String raw) {
        return frames.parse(raw);
    }

    /// 并行工具只完成一部分就被停止：只回放有结果的调用
    @Test
    void aStoppedParallelBatchReplaysOnlyAnsweredCalls() {
        List<JsonNode> exchange = List.of(
                json("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"finished\"},"
                        + "{\"id\":\"pending\"}]}"),
                json("{\"role\":\"tool\",\"tool_call_id\":\"finished\",\"content\":\"found\"}"),
                json("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"never_started\"}]}"));
        List<JsonNode> kept = ChatGeneration.completedExchange(exchange);
        assertEquals(2, kept.size());
        assertEquals(json("[{\"id\":\"finished\"}]"), kept.get(0).path("tool_calls"));
        assertEquals("finished", kept.get(1).path("tool_call_id").asText());
    }

    /// 模型服务复用了 id：每条助手消息只匹配它自己那一轮的结果
    @Test
    void aReusedCallIdOnlyMatchesResultsInItsOwnRound() {
        List<JsonNode> exchange = List.of(
                json("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"reused\"}]}"),
                json("{\"role\":\"tool\",\"tool_call_id\":\"reused\",\"content\":\"old result\"}"),
                json("{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"reused\"},"
                        + "{\"id\":\"finished\"}]}"),
                json("{\"role\":\"tool\",\"tool_call_id\":\"finished\",\"content\":\"new result\"}"));
        List<JsonNode> kept = ChatGeneration.completedExchange(exchange);
        assertEquals(4, kept.size());
        assertEquals(json("[{\"id\":\"reused\"}]"), kept.get(0).path("tool_calls"));
        assertEquals(json("[{\"id\":\"finished\"}]"), kept.get(2).path("tool_calls"));
    }

    /// 引用号的解析与沿用判定（cited/carried：[1][2] / [1, 2] / [1，2]，0 不是号）
    @Test
    void carriedSourcesOnlyWhenEveryNumberWasCitedBefore() {
        assertEquals(java.util.Set.of(1L, 2L),
                ChatRunner.citedNumbers("a [1][2] and [1, 2] and [1，2] b"));
        assertTrue(ChatRunner.citedNumbers("no [0] marks").isEmpty());
        assertTrue(ChatRunner.citedNumbers("a [x] b [1-2] c").isEmpty());
        JsonNode previous = json("[{\"n\":1,\"document_id\":\"d1\"},"
                + "{\"n\":2,\"document_id\":\"d2\"},{\"n\":3,\"document_id\":\"d3\"}]");
        assertEquals(1, ChatRunner.carriedSources("Target: 95% [1].", "The target is [1].",
                List.of(previous.get(0), previous.get(1), previous.get(2))).size());
        assertTrue(ChatRunner.carriedSources("Target: 95% [2].", "The target is [1].",
                List.of(previous.get(0))).isEmpty(), "2 不在上一条里——一条不给");
        assertTrue(ChatRunner.carriedSources("No marks.", "The target is [1].",
                List.of(previous.get(0))).isEmpty());
    }
}
