package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.llm.ChatClient;

/**
 * 收尾作答的总时限盖住<b>整个</b>两试循环（chat_finalization.rs 内联
 * the_total_deadline_covers_candidate_repair 对位）：第一次被否后第二次只剩残量，
 * HTTP 请求超时=deadline−now——到点以 "Final answer timed out" 收口，绝无
 * 「第二次只要在时限前开跑就完全不受限」。直调 finalizer + 脚本端点，不碰库。
 */
class ChatFinalizationDeadlineTests {

    private static final String DSML = "<｜DSML｜ calls>\n"
            + "<｜DSML｜ invoke name=\"entity_facts\">{}</｜DSML｜ invoke>\n</｜DSML｜ calls>";

    /// 200ms 总时限切断第二次尝试：第一次秒回 DSML（可修复），第二次被脚本把响应头
    /// 压住 2s——时限在途中到点，收口原文是 timed out，两次尝试都真实发出过
    @Test
    void theTotalDeadlineCoversCandidateRepair() throws Exception {
        ChatScriptServer fake = ChatScriptServer.start(List.of(
                ScriptedReply.text(DSML),
                ScriptedReply.text("The late answer.")));
        fake.delayResponsesFrom(2, 2000);
        try {
            ChatClient client = new ChatClient(fake.endpoint(), null, "test");
            ChatFrames frames = new ChatFrames();
            ChatFinalizer.AnswerContext input = new ChatFinalizer.AnswerContext(
                    "q", List.of(), null, List.of(), List.of(), List.of(), List.of());
            long started = System.nanoTime();
            IOException err = assertThrowsExactly(IOException.class,
                    () -> new ChatFinalizer().answerWithDeadline(client, frames, input,
                            null, null, Duration.ofMillis(200)));
            long tookMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertTrue(err.getMessage().contains("timed out"),
                    "the deadline, not the 2s script delay, ends it: " + err.getMessage());
            assertTrue(tookMs < 1500, "cut mid-flight, not after the slow attempt: " + tookMs);
            assertEquals(2, fake.requests().size(), "both attempts were made");
        } finally {
            fake.stop();
        }
    }
}
