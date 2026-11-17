package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 一轮对话里工具共用的累积面（tools.rs ToolSink + llm_util.rs EmbedCache 合一）。
 *
 * <p><b>引用编号是有状态的</b>：[3] 里的 3 取决于这一轮之前已经引过几个，所以不能让每个
 * 工具各算各的再合并——那样同一个 chunk 会拿到两个号。一轮一 sink：对话一轮一个，
 * MCP 一次调用一个。
 *
 * <p><b>EmbedCache（#971）</b>：一轮之内文字与模型都不变，嵌一次就够。挑口径
 * （mapping_index relevant）与检索分块（search_chunks）也从这里拿问题的向量、往这里记
 * ——从挑口径那一刻起生效，问题嵌一次，随后检索分块、按名字查实体都拿同一个向量。
 * 不落库只记成功；失败了下次照样再试。没有这一轮 sink 的调用方传 null 各嵌各的。
 */
public final class ToolSink {

    /** 去重键（chunk uuid 或 charter:slug#anchor），下标 +1 就是引用号。 */
    public final List<String> sourceIds = new ArrayList<>();
    /** 发给前端的引用清单，与 sourceIds 同序。 */
    public final List<JsonNode> sources = new ArrayList<>();
    /** 这一轮认下的实体，落进会话供下一轮回放。 */
    public final List<JsonNode> resolved = new ArrayList<>();
    /** 这一轮嵌过的文字与它的向量（EmbedCache），不落库。 */
    public final Map<String, float[]> embeddings = new HashMap<>();

    /**
     * 已经引过的给回原号，没引过的落一个新号。<b>同一个 chunk 在一轮对话里只能有一个号</b>，
     * 否则模型引 [2] 而界面上有两个 [2]。
     */
    public int cite(String key, Function<Integer, JsonNode> make) {
        int i = sourceIds.indexOf(key);
        if (i >= 0) {
            return i + 1;
        }
        sourceIds.add(key);
        sources.add(make.apply(sourceIds.size()));
        return sourceIds.size();
    }

    static final int QUOTES_PER_SOURCE = 8;
    static final int QUOTE_CHARS = 400;

    /**
     * 给第 n 条来源补一句它说出的话（#968 的后续）：号打开的是那一块，quotes 是块里被引到
     * 的那几句，界面按它们在原文里标出来。同一块一个号，块里被引到几句就记几句，去重、按
     * 引用的先后。两个上限：清单每调一次工具就整份重发一遍，一块里抽出几十条事实不该让它
     * 跟着长；超长的一句整句不记而不是截断——界面按原文找这句话，截过的找不到。
     */
    public void addQuote(int n, String quote) {
        if (quote.codePoints().count() > QUOTE_CHARS) {
            return;
        }
        if (n < 1 || n > sources.size()) {
            return;
        }
        JsonNode entry = sources.get(n - 1);
        if (!entry.isObject()) {
            return;
        }
        com.fasterxml.jackson.databind.node.ObjectNode map =
                (com.fasterxml.jackson.databind.node.ObjectNode) entry;
        com.fasterxml.jackson.databind.node.ArrayNode quotes;
        if (map.has("quotes") && map.get("quotes").isArray()) {
            quotes = (com.fasterxml.jackson.databind.node.ArrayNode) map.get("quotes");
        } else {
            quotes = map.putArray("quotes");
        }
        if (quotes.size() < QUOTES_PER_SOURCE) {
            for (JsonNode q : quotes) {
                if (q.asText().equals(quote)) {
                    return;
                }
            }
            quotes.add(quote);
        }
    }

    /** 记下这一轮认下的实体（tools_graph remember）：只记真的读过或搜索明确指向的。 */
    public void rememberEntity(JsonNode node) {
        String id = node.path("id").asText();
        for (JsonNode e : resolved) {
            if (e.path("id").asText().equals(id)) {
                return;
            }
        }
        resolved.add(node);
    }
}
