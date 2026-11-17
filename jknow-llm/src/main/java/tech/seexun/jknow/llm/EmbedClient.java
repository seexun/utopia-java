package tech.seexun.jknow.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容嵌入客户端（对等旧仓库 utopia-llm/src/lib.rs:1071-1099 LlmClient::embed——
 * {@code POST {base_url}/embeddings {"model":…,"input":[…]}}，Bearer 密钥；响应按
 * data[].index 排序取 embedding）。M2 任务 5 落地：管线 embedding 步「工作区配了
 * embed 模型才做」，走这里；没配不建客户端（models.rs:487-489 embed_ready）。
 *
 * <p>取舍：JDK HttpClient 零新依赖（Spring AI 留给对话域里程碑）；每批一次请求，
 * 批间并发由调用方（ProcessDocumentJob 的批 16/并发 4）编排。响应形状对不上按
 * IOException 上抛——一批错位会把向量写到别人身上（pipeline.rs:355-360 同理由）。
 */
public final class EmbedClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public EmbedClient(String baseUrl, String apiKey, String model) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * 一批文本 → 按送入顺序的向量列表。数量对不上就整批失败（lib.rs:1087 ordered_by_index
     * 同语义：配对按位置，少一条全体错位）。
     */
    public List<float[]> embed(List<String> texts) throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("model", model);
        ArrayNode input = body.putArray("input");
        texts.forEach(input::add);
        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp =
                http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("Embedding 请求失败 HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body()));
        }
        JsonNode data = JSON.readTree(resp.body()).path("data");
        if (!data.isArray() || data.size() != texts.size()) {
            throw new IOException("嵌入返回 " + (data.isArray() ? data.size() : 0) + " 条，送去的是 "
                    + texts.size() + " 条");
        }
        // 按 index 排位（OpenAI 规范 data[].index；个别兼容实现缺 index 按原序）
        JsonNode[] ordered = new JsonNode[texts.size()];
        boolean hasIndex = false;
        for (JsonNode item : data) {
            int idx = item.path("index").asInt(-1);
            if (idx < 0 || idx >= ordered.length) {
                hasIndex = false;
                break;
            }
            ordered[idx] = item;
            hasIndex = true;
        }
        if (!hasIndex) {
            int i = 0;
            for (JsonNode item : data) {
                ordered[i++] = item;
            }
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (JsonNode item : ordered) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray()) {
                throw new IOException("Embedding 响应缺少向量");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            out.add(vector);
        }
        return out;
    }

    private static String truncate(String body) {
        return body == null ? "" : body.substring(0, Math.min(300, body.length()));
    }
}
