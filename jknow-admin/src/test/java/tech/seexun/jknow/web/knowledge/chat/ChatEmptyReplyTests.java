package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * 空回复重问一次（EMPTY_REPLY_RETRY）+ 预算边界的终稿闸（chat_empty_reply_tests.rs 主族，
 * 断言原值移植）。走整条对话：直调 ChatController 的准备-启动路径，模型由
 * ChatScriptServer 按脚本假扮——「模型回空」这类真模型上几十轮才碰一次的事在这里
 * 每次都发生。
 */
@org.junit.jupiter.api.condition.EnabledIf(value = "envUp",
        disabledReason = "共享库（1517）不可达，跳过 chat 集成测试")
class ChatEmptyReplyTests extends ChatFx {

    /** 共享库可达性（StoreTestBase 的门；方法引用须在本类可见）。 */
    public static boolean envUp() {
        return sharedStackUp();
    }

    private static final String DSML = "<｜DSML｜ calls>\n"
            + "<｜DSML｜ invoke name=\"entity_facts\">{}</｜DSML｜ invoke>\n</｜DSML｜ calls>";

    /** 断线档直调 chatAttached 要的请求对象（ChatFx 的 request 是私有的）。 */
    private org.springframework.mock.web.MockHttpServletRequest request() {
        org.springframework.mock.web.MockHttpServletRequest req =
                new org.springframework.mock.web.MockHttpServletRequest();
        req.setAttribute(tech.seexun.jknow.web.knowledge.auth.SessionFilter.USER_ATTR, user);
        return req;
    }

    private static String lastMessage(JsonNode request) {
        JsonNode messages = request.path("messages");
        return messages.size() == 0 ? "" : messages.get(messages.size() - 1).path("content")
                .asText("");
    }

    /// 查完之后空一次，重问后答出来：用户看到的是查证步骤接着答案，不是报错
    @Test
    void anEmptyReplyAfterTheToolsIsAskedAgainAndTheAnswerArrives() throws Exception {
        String answer = "Answered on the second ask.";
        fixture(List.of(
                ScriptedReply.tool("find_entities", "{\"name\":\"Acme\"}"),
                ScriptedReply.empty(),
                ScriptedReply.text(answer)));
        try {
            String sse = ask("What changed at Acme last quarter?");

            assertTrue(sse.contains("event: step"), () -> "the tool ran first:\n" + sse);
            assertFalse(sse.contains("event: error"),
                    () -> "an empty reply must not reach the user as an error:\n" + sse);
            assertTrue(sse.contains("event: done"), () -> "the turn finishes:\n" + sse);
            assertTrue(sse.contains(answer), () -> "the reply after the retry is streamed:\n" + sse);

            List<JsonNode> requests = requests();
            assertEquals(3, requests.size(), "tool, empty, then one retry");
            assertEquals(1, requests.stream()
                    .filter(r -> ChatGuards.EMPTY_REPLY_RETRY.equals(lastMessage(r))).count(),
                    "the retry is sent exactly once");
            assertEquals(ChatGuards.EMPTY_REPLY_RETRY, lastMessage(requests.get(2)),
                    "the request after the empty reply ends with the retry");
            assertEquals(answer, storedAnswer(), "the stored answer is the one the retry produced");
        } finally {
            cleanup();
        }
    }

    /// 一直空，报错，而且只重问一次：一个始终不说话的端点不能让循环空转
    @Test
    void aReplyThatStaysEmptyIsAnErrorAfterOneRetry() throws Exception {
        fixture(List.of(ScriptedReply.empty(), ScriptedReply.empty()));
        try {
            String sse = ask("What changed last quarter?");
            assertTrue(sse.contains("event: error"), () -> "still empty is an error:\n" + sse);
            assertTrue(sse.contains("Model returned an empty answer"),
                    () -> "with the same message as before:\n" + sse);
            assertEquals(2, requests().size(),
                    "one retry, not a loop: a silent endpoint must not be asked again and again");
            assertNull(storedAnswer());
        } finally {
            cleanup();
        }
    }

    /// 预算边界（第 7 次请求）上的候选答案过收尾闸：控制文本/结构化调用/空答/超限各拒
    private void budgetCase(ScriptedReply last, String question, String answer) throws Exception {
        List<ScriptedReply> replies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            replies.add(ScriptedReply.narratedTool());
        }
        replies.add(last);
        fixture(replies);
        try {
            String sse = ask(question);
            // oversized 也是失败（answer_too_long），且在终稿流中即止——没有修复档，7 次请求
            int expectedRequests = answer == null && !isOversized(last) ? 8 : 7;
            assertEquals(expectedRequests, requests().size(),
                    "six tool rounds, one final call, and at most one answer-only recovery");
            assertEquals(6, countMatches(sse, "event: step"),
                    "no budget-overrun tool execution: " + sse);
            List<JsonNode> requests = requests();
            for (int i = 0; i < 6; i++) {
                assertTrue(requests.get(i).has("tools"), "tool rounds carry the tool list");
            }
            assertFalse(requests.get(6).has("tools"));
            assertFalse(requests.get(6).has("tool_choice"));
            assertTrue(requests.get(6).path("messages").toString().contains("evidence"));
            if (answer != null) {
                assertTrue(sse.contains("event: done"), sse);
                assertFalse(sse.contains("event: error"), sse);
                String expected = "I will check the evidence.\n\n".repeat(6) + answer;
                assertEquals(expected, storedAnswer());
                StringBuilder streamed = new StringBuilder();
                for (String frame : sse.split("\n\n")) {
                    if (frame.startsWith("event: delta\ndata: ")) {
                        streamed.append(frames().parse(
                                frame.substring("event: delta\ndata: ".length()))
                                .path("text").asText());
                    }
                }
                assertEquals(expected, streamed.toString(), "final text is published exactly once");
            } else {
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                assertFalse(sse.contains("DSML"),
                        "protocol text must not escape in deltas: " + sse);
                assertNull(storedAnswer());
            }
        } finally {
            cleanup();
        }
    }

    private static boolean isOversized(ScriptedReply reply) {
        // oversizedText 档：终稿超 1MiB 在流中即止，无修复重试（Rust budget_case 的特判）
        try {
            String sse = reply.sse(new com.fasterxml.jackson.databind.ObjectMapper(), 999);
            return sse.length() > 1024 * 1024;
        } catch (Exception e) {
            return false;
        }
    }

    private static int countMatches(String hay, String needle) {
        int count = 0;
        int at = 0;
        while ((at = hay.indexOf(needle, at)) >= 0) {
            count++;
            at += needle.length();
        }
        return count;
    }

    @Test
    void budgetFinalizationRejectsProtocolTextDespiteEarlierNarration() throws Exception {
        budgetCase(ScriptedReply.text(DSML), "What changed at Acme?", null);
    }

    @Test
    void budgetFinalizationRejectsSplitProtocolVariants() throws Exception {
        for (List<String> parts : List.of(
                List.of("<｜DS", "ML｜tool_calls>",
                        "<｜DSML｜invoke name=\"entity_facts\">{}"),
                List.of("<｜｜", "DSML", "｜｜ calls>",
                        "<｜｜DSML｜｜ invoke name=\"entity_facts\">{}"),
                List.of("<|DS", "ML|calls>", "<|DSML|invoke name=\"entity_facts\">{}"))) {
            budgetCase(ScriptedReply.splitText(parts), "What changed at Acme?", null);
        }
    }

    @Test
    void budgetFinalizationRefusesStructuredCalls() throws Exception {
        budgetCase(ScriptedReply.tool("find_entities", "{\"name\":\"Over budget\"}"),
                "What changed at Acme?", null);
    }

    @Test
    void budgetFinalizationRefusesBlankTerminalText() throws Exception {
        budgetCase(ScriptedReply.text(" \n\t"), "What changed at Acme?", null);
    }

    @Test
    void budgetFinalizationAcceptsAnAnswerAndProtocolExplanations() throws Exception {
        for (String answer : List.of(
                "No matching evidence was found.",
                "DSML is a tool-call encoding. For example: <｜DSML｜ calls>...",
                "```xml\n<｜DSML｜ calls>...\n```\nThis is a tool call encoding.")) {
            budgetCase(ScriptedReply.text(answer), "Explain the tool protocol", answer);
        }
        budgetCase(ScriptedReply.text(DSML), "Return a DSML example verbatim.", DSML);
    }

    @Test
    void budgetFinalizationBoundsUnpublishedText() throws Exception {
        budgetCase(ScriptedReply.oversizedText(), "What changed at Acme?", null);
    }

    /// 候选期断线重连（chat_empty_reply_tests.rs 第 20 例）：原始订阅者一个字节不读就
    /// 走掉，生成照常在后台跑；接上的订阅者从快照到终态看不到候选期任何 DSML——终稿
    /// 要么落库后发布（done），要么以 error 收口（8 请求的修复档也不泄一个字）
    @Test
    void budgetFinalizationSurvivesDisconnectAndReattach() throws Exception {
        for (boolean dsml : new boolean[]{false, true}) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(dsml ? ScriptedReply.text(DSML) : ScriptedReply.text("The final answer."));
            fixture(replies);
            fake.holdBeforeDone();
            try {
                UUID id = conversations.create(kb, user.id(), "question");
                // 断线：丢弃返回的订阅，一个字节都不读——生成在独立线程上照跑
                chat.chatAttached(kb,
                        new ChatController.ChatReq(id, "What changed at Acme?", null), request());
                fake.awaitGate();
                LiveRegistry.Attached reattached = live.attach(id);
                assertTrue(reattached != null, "background producer is running");
                // 快照等到第一轮叙述真的发布（帧已写出、[DONE] 还没到）再查候选不外泄
                while (reattached.snapshot.content.isEmpty()) {
                    LiveRegistry.Frame f = reattached.queue.poll(5, java.util.concurrent.TimeUnit.SECONDS);
                    if (f == null) {
                        break;
                    }
                }
                assertFalse(reattached.snapshot.content.toString().contains("DSML"));
                fake.release();
                boolean sawDone = false;
                boolean sawError = false;
                while (true) {
                    LiveRegistry.Frame frame = reattached.queue.poll(20,
                            java.util.concurrent.TimeUnit.SECONDS);
                    if (frame == null) {
                        break;
                    }
                    assertFalse(frame.data().contains("DSML"), frame.data());
                    sawDone |= "done".equals(frame.event());
                    sawError |= "error".equals(frame.event());
                    if (sawDone || sawError) {
                        break;
                    }
                }
                assertEquals(dsml ? 8 : 7, requests().size(),
                        dsml ? "one rejected candidate, one repair attempt"
                                : "six tool rounds, one final call");
                if (dsml) {
                    assertTrue(sawError, "rejected twice ends in error");
                    assertFalse(sawDone);
                    assertNull(storedAnswer());
                } else {
                    assertTrue(sawDone, "the accepted answer is published to the reattached subscriber");
                    assertFalse(sawError);
                    assertTrue(storedAnswer().endsWith("The final answer."), storedAnswer());
                }
                assertNull(live.attach(id), "the conversation is free after the terminal");
            } finally {
                fake.release();
                cleanup();
            }
        }
    }

    /// 早退回（空回复/该调不调）不延长工具预算：预算按请求计数
    @Test
    void earlyRetriesDoNotExtendTheToolBudget() throws Exception {
        for (ScriptedReply early : List.of(ScriptedReply.empty(),
                ScriptedReply.text("I will look into it."))) {
            List<ScriptedReply> replies = new ArrayList<>();
            replies.add(early);
            for (int i = 0; i < 5; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(ScriptedReply.text(DSML));
            fixture(replies);
            try {
                String sse = ask("What changed at Acme?");
                assertEquals(8, requests().size());
                assertEquals(5, countMatches(sse, "event: step"));
                assertFalse(requests().get(6).has("tools"));
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                assertFalse(sse.contains("DSML"), sse);
                assertNull(storedAnswer());
            } finally {
                cleanup();
            }
        }
    }

    /// 收尾被否一次后从既有证据修复：同一份冻结证据、无工具、无原文回流
    @Test
    void finalizationRecoversOnceFromExistingEvidenceWithoutTools() throws Exception {
        String answer = "There are no matching entities in the supplied evidence.";
        List<ScriptedReply> invalids = List.of(
                ScriptedReply.text(DSML),
                ScriptedReply.splitText(List.of("Let me examine it.\n\n<｜｜D",
                        "SML｜｜ calls>\n", "<｜DSML｜ invoke name=\"entity_facts\">{}")),
                ScriptedReply.empty(),
                ScriptedReply.tool("find_entities", "{\"name\":\"forbidden\"}"),
                ScriptedReply.finished("Incomplete final", "length"));
        for (ScriptedReply invalid : invalids) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(invalid);
            replies.add(ScriptedReply.finished(answer, "stop"));
            fixture(replies);
            try {
                String sse = ask("What changed at Acme?");
                assertTrue(sse.contains("event: done"), sse);
                assertFalse(sse.contains("event: error"), sse);
                assertFalse(sse.contains("DSML"), sse);
                assertFalse(sse.contains("Incomplete final"), sse);
                assertEquals(1, countMatches(sse, answer));
                assertEquals(6, countMatches(sse, "event: step"));
                assertEquals("I will check the evidence.\n\n".repeat(6) + answer, storedAnswer());
                List<JsonNode> reqs = requests();
                assertEquals(8, reqs.size());
                JsonNode recovery = reqs.get(7);
                assertFalse(recovery.has("tools"));
                assertFalse(recovery.has("tool_choice"));
                assertEquals(2, recovery.path("messages").size());
                String data = recovery.path("messages").get(1).path("content").asText();
                JsonNode parsed = frames().parse(data);
                assertEquals("What changed at Acme?", parsed.path("question").asText());
                assertEquals(reqs.get(6).path("messages").get(1),
                        reqs.get(7).path("messages").get(1), "same frozen evidence on repair");
                JsonNode storedExchange = frames().parse(jdbc.queryForObject(
                        "SELECT m.tool_exchange::text FROM conversation_messages m "
                                + "JOIN conversations c ON c.id=m.conversation_id "
                                + "WHERE c.kb_id=? AND m.role='assistant'",
                        String.class, kb));
                List<JsonNode> original = new ArrayList<>();
                storedExchange.forEach(m -> {
                    if ("tool".equals(m.path("role").asText())) {
                        original.add(m);
                    }
                });
                List<JsonNode> evidence = new ArrayList<>();
                parsed.path("evidence").forEach(evidence::add);
                assertEquals(original.size(), evidence.size());
                for (int i = 0; i < evidence.size(); i++) {
                    assertEquals(original.get(i).path("content").asText(),
                            evidence.get(i).path("result").asText(), "evidence is copied exactly");
                    assertEquals(original.get(i).path("tool_call_id").asText(),
                            evidence.get(i).path("id").asText());
                }
                assertFalse(data.contains("DSML"));
            } finally {
                cleanup();
            }
        }
    }

    /// 修复不成绝不循环也不重开工具
    @Test
    void unsuccessfulRecoveryNeverLoopsOrReopensTools() throws Exception {
        List<ScriptedReply> faileds = List.of(
                ScriptedReply.text(DSML),
                ScriptedReply.splitText(List.of("Let me examine it.\n\n<｜｜D",
                        "SML｜｜ calls>\n", "<｜DSML｜ invoke name=\"entity_facts\">{}")),
                ScriptedReply.empty(),
                ScriptedReply.tool("find_entities", "{\"name\":\"forbidden\"}"),
                ScriptedReply.finished("partial", "length"),
                ScriptedReply.http(422),
                ScriptedReply.http(401));
        for (ScriptedReply failed : faileds) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(ScriptedReply.text(DSML));
            replies.add(failed);
            replies.add(ScriptedReply.text("Must never be requested"));
            fixture(replies);
            try {
                String sse = ask("What changed at Acme?");
                assertEquals(8, requests().size());
                assertEquals(6, countMatches(sse, "event: step"));
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                assertFalse(sse.contains("DSML"), sse);
                assertFalse(sse.contains("partial"), sse);
                assertNull(storedAnswer());
            } finally {
                cleanup();
            }
        }
    }

    /// 传输失败与不可修复的 finish 不重试
    @Test
    void finalAnswerTransportAndNonrepairableFinishesDoNotRetry() throws Exception {
        List<ScriptedReply> failures = List.of(
                ScriptedReply.http(400), ScriptedReply.http(401), ScriptedReply.http(402),
                ScriptedReply.http(403), ScriptedReply.http(422), ScriptedReply.http(429),
                ScriptedReply.finished("", "content_filter"),
                ScriptedReply.finished("partial", "unknown_provider_reason"));
        for (ScriptedReply failure : failures) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(failure);
            replies.add(ScriptedReply.text("Never requested"));
            fixture(replies);
            try {
                String sse = ask("What changed?");
                assertEquals(7, requests().size());
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                assertFalse(sse.contains("Never requested"), sse);
                assertNull(storedAnswer());
            } finally {
                cleanup();
            }
        }
    }

    /// 失败的工具观察不是「库里没有」：证据单里按 status=error 记下
    @Test
    void failedToolObservationIsNotReportedAsEmptyKnowledge() throws Exception {
        List<ScriptedReply> replies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replies.add(ScriptedReply.narratedTool());
        }
        replies.add(ScriptedReply.tool("get_document", "{}"));
        replies.add(ScriptedReply.text("The document could not be read."));
        fixture(replies);
        try {
            String sse = ask("Read the document.");
            assertTrue(sse.contains("event: done"), sse);
            JsonNode data = frames().parse(
                    requests().get(6).path("messages").get(1).path("content").asText());
            assertEquals("error", data.path("evidence").get(5).path("status").asText());
            assertEquals(6, data.path("evidence").size());
        } finally {
            cleanup();
        }
    }

    /// 两个会话并行走各自的手递手与修复预算
    @Test
    void concurrentChatsHaveIndependentHandoffAndRepairBudgets() throws Exception {
        List<ScriptedReply> a = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            a.add(ScriptedReply.narratedTool());
        }
        a.add(ScriptedReply.text(DSML));
        a.add(ScriptedReply.text("Recovered A"));
        fixture(a);
        try {
            String ra = ask("Question A");
            assertTrue(ra.contains("Recovered A"), ra);
            assertEquals(8, requests().size());
        } finally {
            cleanup();
        }
        // 第二段：直接一答（无修复）
        List<ScriptedReply> b = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            b.add(ScriptedReply.narratedTool());
        }
        b.add(ScriptedReply.text("Direct B"));
        fixture(b);
        try {
            String rb = ask("Question B");
            assertTrue(rb.contains("Direct B"), rb);
            assertEquals(7, requests().size());
        } finally {
            cleanup();
        }
    }

    /// 并行工具与 UTF-16 偏移：12 条证据、12 步、at 按轮累积
    @Test
    void parallelToolResultsAndUtf16StepPositionsSurviveHandoff() throws Exception {
        List<ScriptedReply> replies = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            replies.add(ScriptedReply.parallelTools());
        }
        replies.add(ScriptedReply.text("最终答案"));
        fixture(replies);
        try {
            String sse = ask("查到什么？");
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(7, requests().size());
            JsonNode data = frames().parse(
                    requests().get(6).path("messages").get(1).path("content").asText());
            List<JsonNode> evidence = new ArrayList<>();
            data.path("evidence").forEach(evidence::add);
            assertEquals(12, evidence.size());
            assertEquals(12, evidence.stream().map(e -> e.path("id").asText()).distinct().count());
            assertTrue(evidence.stream().allMatch(e -> "success".equals(e.path("status")
                    .asText())));
            List<JsonNode> steps = new ArrayList<>();
            for (String block : sse.split("\n\n")) {
                if (block.startsWith("event: step\ndata: ")) {
                    steps.add(frames().parse(block.substring("event: step\ndata: ".length())));
                }
            }
            assertEquals(12, steps.size());
            int width = "核查😀\n\n".length();
            for (int i = 0; i < steps.size(); i++) {
                assertEquals((i / 2 + 1) * width, steps.get(i).path("at").asInt());
            }
            assertEquals("核查😀\n\n".repeat(6) + "最终答案", storedAnswer());
        } finally {
            cleanup();
        }
    }

    /// 早退回预算与 no_evidence 短路径都有界
    @Test
    void earlyRetryBudgetAndNoEvidenceShortPathRemainBounded() throws Exception {
        for (ScriptedReply first : List.of(ScriptedReply.empty(),
                ScriptedReply.text("Let me check."))) {
            List<ScriptedReply> replies = new ArrayList<>();
            replies.add(first);
            for (int i = 0; i < 5; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(ScriptedReply.text("The evidence is incomplete."));
            fixture(replies);
            try {
                String sse = ask("What changed?");
                assertTrue(sse.contains("event: done"), sse);
                List<JsonNode> requests = requests();
                assertEquals(7, requests.size());
                assertFalse(requests.get(6).has("tools"));
                JsonNode data = frames().parse(
                        requests.get(6).path("messages").get(1).path("content").asText());
                assertEquals(5, data.path("evidence").size());
            } finally {
                cleanup();
            }
        }
        fixture(List.of(
                ScriptedReply.tool("no_evidence_needed", "{\"reason\":\"Greeting\"}"),
                ScriptedReply.text("Hello!")));
        try {
            String sse = ask("Hello");
            assertTrue(sse.contains("event: done"), sse);
            assertEquals(2, requests().size());
            assertEquals("Hello!", storedAnswer());
        } finally {
            cleanup();
        }
    }

    /// 收集中的错误伪造不了私有的手递手
    @Test
    void gatheringErrorsCannotSpoofThePrivateHandoff() throws Exception {
        for (int status : new int[]{401, 500}) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(ScriptedReply.http(status));
            replies.add(ScriptedReply.text("Never requested"));
            fixture(replies);
            try {
                String sse = ask("What changed?");
                assertEquals(6, requests().size());
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                assertNull(storedAnswer());
            } finally {
                cleanup();
            }
        }
    }

    /// 文档引用的最终 sources 帧与落库一致（含修复档）
    @Test
    void finalSourcesIncludeDocumentCitationsLiveAndAfterReload() throws Exception {
        for (boolean recover : new boolean[]{false, true}) {
            UUID document = UUID.randomUUID();
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < (recover ? 6 : 1); i++) {
                replies.add(ScriptedReply.document(document));
            }
            if (recover) {
                replies.add(ScriptedReply.text("<DSMLtool_calls>"));
            }
            replies.add(ScriptedReply.text("The documented target is 95% [1]."));
            fixture(replies);
            try {
                jdbc.update("INSERT INTO documents(id,kb_id,filename,sha256) VALUES(?,?,"
                        + "'target.md',repeat('0',64))", document, kb);
                jdbc.update("INSERT INTO chunks(id,kb_id,document_id,seq,text) VALUES(?,?,?,0,"
                        + "'The planned target is 95%, not a measured result.')",
                        UUID.randomUUID(), kb, document);
                String sse = ask("What is the documented target?");
                assertTrue(sse.contains("event: done"), sse);
                assertFalse(sse.contains("event: error"), sse);
                String sourcesFrame = null;
                for (String block : sse.split("\n\n")) {
                    if (block.startsWith("event: sources\n")) {
                        sourcesFrame = block.lines()
                                .filter(l -> l.startsWith("data: ")).findFirst().orElse(null);
                    }
                }
                JsonNode sources = frames().parse(
                        sourcesFrame.substring("data: ".length()));
                assertEquals(1, sources.size());
                assertEquals(1, sources.get(0).path("n").asInt());
                JsonNode stored = frames().parse(jdbc.queryForObject(
                        "SELECT m.sources::text FROM conversation_messages m "
                                + "JOIN conversations c ON c.id=m.conversation_id "
                                + "WHERE c.kb_id=? AND m.role='assistant'",
                        String.class, kb));
                assertEquals(sources, stored);
                assertEquals("The documented target is 95% [1].", storedAnswer());
                assertEquals(recover ? 8 : 2, requests().size());
            } finally {
                cleanup();
            }
        }
    }

    /// 存库失败：error 收口、缓冲的终稿不发布、不再重试
    @Test
    void saveFailureIsAnErrorWithoutPublishingTheBufferedAnswerOrRetrying() throws Exception {
        for (boolean budget : new boolean[]{false, true}) {
            List<ScriptedReply> replies = new ArrayList<>();
            for (int i = 0; i < (budget ? 6 : 1); i++) {
                replies.add(ScriptedReply.narratedTool());
            }
            replies.add(ScriptedReply.text("Accepted final answer."));
            fixture(replies);
            try {
                String name = "reject_assistant_" + kb.toString().replace("-", "");
                jdbc.update("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql "
                        + "AS $$ BEGIN IF NEW.role='assistant' AND EXISTS(SELECT 1 FROM "
                        + "conversations WHERE id=NEW.conversation_id AND kb_id='" + kb
                        + "') THEN RAISE EXCEPTION 'injected persistence failure'; END IF; "
                        + "RETURN NEW; END $$");
                jdbc.update("CREATE TRIGGER " + name + " BEFORE INSERT ON conversation_messages "
                        + "FOR EACH ROW EXECUTE FUNCTION " + name + "()");
                String sse = ask("What happened?");
                jdbc.update("DROP TRIGGER " + name + " ON conversation_messages");
                jdbc.update("DROP FUNCTION " + name + "()");
                assertTrue(sse.contains("event: error"), sse);
                assertFalse(sse.contains("event: done"), sse);
                if (budget) {
                    assertFalse(sse.contains("Accepted final answer."),
                            "uncommitted final text must remain private");
                }
                assertEquals(budget ? 7 : 2, requests().size());
                assertNull(storedAnswer());
            } finally {
                cleanup();
            }
        }
    }
}
