package tech.seexun.jknow.web.knowledge.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;

/**
 * check_call 统一参数闸（chat.rs 内联 10 例，断言原值移植）。两种「没说清」以前都会
 * 安静地变成一次正常调用：参数解析不出来（token 上限截断的半截 JSON）与必填参数没给
 * ——回落产出的是一个看起来没问题的错误答案，那比报错坏得多。
 */
class ChatCheckCallTests {

    private final ChatFrames frames = new ChatFrames();
    private final ChatToolSchema schema = new ChatToolSchema(frames);

    private JsonNode tools(boolean canWrite, String... sources) {
        return schema.toolsSchema(canWrite, List.of(sources));
    }

    private ChatToolSchema.CheckResult check(JsonNode tools, String name, String rawArgs) {
        return schema.checkCall(tools, name, rawArgs);
    }

    private record Refusal(String message, JsonNode step) {
    }

    private Refusal refuse(JsonNode tools, String name, String rawArgs) {
        ChatToolSchema.CheckResult r = check(tools, name, rawArgs);
        assertTrue(r.refused(), () -> name + " " + rawArgs + " 应当被拒，得到 " + r.args());
        return new Refusal(r.refuseMessage(), r.refuseStep());
    }

    /// 必填 string 参数收到别的 JSON 类型直接拒，不 coerce
    @Test
    void requiredStringsRejectOtherJsonTypesWithoutCoercion() {
        JsonNode tools = tools(true);
        for (String[] pair : new String[][]{{"search_chunks", "query"}, {"remember", "text"}}) {
            String name = pair[0];
            String key = pair[1];
            for (String value : new String[]{"123", "false", "[\"pressure\"]",
                    "{\"text\":\"pressure\"}"}) {
                String raw = "{\"" + key + "\":" + value + "}";
                Refusal err = refuse(tools, name, raw);
                assertEquals("invalid " + key, err.step().path("detail").asText(),
                        name + " " + raw);
                assertTrue(err.message().contains("must be a string"),
                        name + " " + raw + ": " + err.message());
            }
        }
        for (String text : new String[]{"pressure", "  压力  "}) {
            String raw = "{\"query\":\"" + text + "\",\"limit\":5}";
            JsonNode parsed = frames.parse(raw);
            assertEquals(parsed.path("limit"),
                    check(tools, "search_chunks", raw).args().path("limit"));
        }
        // 保持 missing/uuid 的优先序：类型错也先按 uuid 说
        Refusal docId = refuse(tools, "get_document", "{\"document_id\":123}");
        assertTrue(docId.message().contains("uuid"));
        assertEquals("missing query", refuse(tools, "search_chunks", "{\"query\":null}")
                .step().path("detail").asText());
        // 自定义工具表也照表判：integer/array 必填各就各位，可选非 string 不苛求
        JsonNode custom = frames.parse("""
                [{"function":{"name":"typed_control","parameters":{
                  "required":["count","items"],"properties":{"count":{"type":"integer"},
                  "items":{"type":"array"},"optional":{"type":"string"}}}}}]}""");
        String args = "{\"count\":3,\"items\":[\"a\"],\"optional\":false}";
        assertEquals(frames.parse(args), check(custom, "typed_control", args).args());
        assertEquals(frames.parse(args), check(tools, "unknown_tool", args).args());
    }

    /// 参数在半路断掉——模型撞上 token 上限时就长这样。从前这里回落成空对象，
    /// 然后 search_chunks 拿用户那句原话去检索，得到一条看起来完全正常的轨迹
    @Test
    void argumentsCutOffMidJsonDoNotBecomeASearch() {
        JsonNode tools = tools(false);
        Refusal err = refuse(tools, "search_chunks", "{\"query\": \"Acme reven");
        assertEquals("tool", err.step().path("kind").asText(), "轨迹上要显示成一次没做成的调用");
        assertEquals("bad arguments", err.step().path("detail").asText());
        assertTrue(err.message().contains("not valid JSON") && err.message().contains("again"),
                "回给模型的话要说清没执行、并让它重来：" + err.message());
    }

    /// 空串与缺字段是同一件事：{"query": ""} 检索回来的东西与问题无关
    @Test
    void anEmptyRequiredArgumentCountsAsMissing() {
        JsonNode tools = tools(false);
        for (String raw : new String[]{"{}", "{\"query\": \"\"}", "{\"query\": \"   \"}",
                "{\"query\": null}"}) {
            assertEquals("missing query", refuse(tools, "search_chunks", raw)
                    .step().path("detail").asText(), raw);
        }
    }

    /// 拒绝的那一步也按字段说（#942）：哪个参数、没给还是给错了；整个解析不出来的没有 param
    @Test
    void aRefusedCallNamesItsParameterForTheInterface() {
        JsonNode tools = tools(false);
        JsonNode missing = refuse(tools, "search_chunks", "{}").step();
        assertEquals("invalid", missing.path("status").asText());
        assertEquals("query", missing.path("param").asText());
        assertEquals(true, missing.path("missing").asBoolean());
        assertEquals("missing query", missing.path("detail").asText());
        JsonNode wrong = refuse(tools, "get_document",
                "{\"document_id\": \"notes.txt\"}").step();
        assertEquals("invalid", wrong.path("status").asText());
        assertEquals("document_id", wrong.path("param").asText());
        assertTrue(wrong.path("missing").isMissingNode());
        JsonNode cut = refuse(tools, "search_chunks", "{\"query\": \"Acme").step();
        assertEquals("invalid", cut.path("status").asText());
        assertTrue(cut.path("param").isMissingNode());
        assertEquals("bad arguments", cut.path("detail").asText());
    }

    /// 判据取自工具表本身：query_data 只在挂了数据源时才在表里，它的两个必填参数
    /// 从没在别处被单独写过一遍
    @Test
    void theSchemaIsTheOnlyPlaceRequiredIsWrittenDown() {
        JsonNode tools = tools(false, "warehouse");
        assertEquals("missing sql", refuse(tools, "query_data",
                "{\"data_source\": \"warehouse\"}").step().path("detail").asText());
        check(tools, "query_data",
                "{\"data_source\": \"warehouse\", \"sql\": \"SELECT 1\"}");
    }

    /// 合规的调用原样通过，可选参数一个不少——这一关只判「说清了没有」，不做过滤
    @Test
    void aWellFormedCallPassesThroughUntouched() {
        JsonNode tools = tools(false);
        JsonNode args = check(tools, "entity_facts",
                "{\"entity_id\": \"1f8ac10b-58cc-4372-a567-0e02b2c3d479\", "
                        + "\"at\": \"2026-03-15\"}").args();
        assertEquals("2026-03-15", args.path("at").asText(), "可选参数不能在这一关被吃掉");
    }

    /// 全文只能按 id 读，而 id 是模型从检索结果里抄来的——抄错、抄漏都得在这里停住
    @Test
    void getDocumentWithoutAnIdDoesNotRun() {
        JsonNode tools = tools(false);
        assertEquals("missing document_id",
                refuse(tools, "get_document", "{}").step().path("detail").asText());
    }

    /// 不是 uuid 的 id 到了工具里只能回「本库没有这篇文档」——模型会把它读成
    /// 「库里真的没有」而收手，于是一次抄错的 id 变成一个否定的答案
    @Test
    void aDocumentIdThatIsNotAUuidDoesNotRun() {
        JsonNode tools = tools(false);
        for (String raw : new String[]{
                "{\"document_id\": \"Notes- 'Scrum' 3 Sept 2026.txt\"}",
                "{\"document_id\": \"1f8ac10b-58cc-4372\"}"}) {
            assertEquals("invalid document_id", refuse(tools, "get_document", raw)
                    .step().path("detail").asText(), raw);
        }
        check(tools, "get_document",
                "{\"document_id\": \"1f8ac10b-58cc-4372-a567-0e02b2c3d479\"}");
    }

    /// changes 早就在自己那一支里拒绝缺失的 since——它是唯一做对的一个。这条钉住两件事：
    /// 统一关卡与它一致；它自己对日期格式的检查（2026-13-45）仍然要留着
    @Test
    void theOneToolThatAlreadyRefusedStillRefuses() {
        JsonNode tools = tools(false);
        assertTrue(check(tools, "changes", "{}").refused());
        check(tools, "changes", "{\"since\": \"2026-13-45\"}");
    }

    /// 描述是写给模型读的，每一轮都在请求里：不许有连着的空格
    /// （从前 list_rules 等的描述在折行处各夹着二十几个空格）
    @Test
    void everyToolDescriptionIsWrittenWithSingleSpaces() {
        List<String> found = new ArrayList<>();
        spaced(schema.toolsSchema(true, List.of("warehouse")), "", found);
        assertTrue(found.isEmpty(), () -> String.valueOf(found));
    }

    private void spaced(JsonNode v, String at, List<String> found) {
        if (v.isObject()) {
            v.fields().forEachRemaining(e -> {
                if (e.getKey().equals("description") && e.getValue().isTextual()
                        && e.getValue().asText().contains("  ")) {
                    found.add(at + ": " + e.getValue());
                }
                spaced(e.getValue(), at + "/" + e.getKey(), found);
            });
        } else if (v.isArray()) {
            for (int i = 0; i < v.size(); i++) {
                JsonNode item = v.get(i);
                String name = item.path("function").path("name").asText("");
                spaced(item, at + "[" + i + "]" + name, found);
            }
        }
    }
}
