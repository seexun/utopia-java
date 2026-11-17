package tech.seexun.jknow.web.knowledge.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.web.knowledge.rbac.WorkspaceAccess;
import tech.seexun.jknow.store.DocumentsWriteService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.UserRow;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 工作区设置写路径三端点（对齐旧仓库 crates/utopia-server/src/api/settings_routes.rs:45-237）：
 * 主卡 PUT（密钥 COALESCE：None/空串=保留旧值）、OCR 卡（换供应商不保留旧密钥+配上即
 * requeue_waiting_for_reader）、转写卡（同上）。GET 面（脱敏视图）在 WorkspaceKbController。
 *
 * <p>SSE document 事件推送（Rust {@code state.emit_document}）M1 已随事件面整体 deferred
 * （COVERAGE.md 同款处置），此处不补——前端列表靠轮询/重取，M2 事件里程碑统一回收。
 */
@RestController
public class SettingsPutController {

    private final SettingsService settings;
    private final DocumentsWriteService documents;
    private final JobsService jobs;
    private final KbMapper kbs;
    private final WorkspaceAccess workspaceAccess;
    private final ObjectMapper mapper;

    public SettingsPutController(SettingsService settings, DocumentsWriteService documents,
                                 JobsService jobs, KbMapper kbs, WorkspaceAccess workspaceAccess,
                                 ObjectMapper mapper) {
        this.settings = settings;
        this.documents = documents;
        this.jobs = jobs;
        this.kbs = kbs;
        this.workspaceAccess = workspaceAccess;
        this.mapper = mapper;
    }

    /**
     * settings_routes.rs:46-57 PutSettingsReq：None 或空串 = 保留旧密钥；effort 缺席=不改。
     * Rust 侧字段全 Option（serde 缺键=None——serde-probe 实测），而全局
     * fail-on-missing-creator-properties 会把缺键拒成 422：沿用 SearchReqDeserializer
     * 先例，自定义反序列化恢复「缺键=None」的 serde 语义（键在而值非字符串才容错丢弃，
     * 同 RegisterRequest.org_name 的处置）。
     */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = SettingsPutController.PutSettingsDeserializer.class)
    public record PutSettingsRequest(String chatBaseUrl, String chatApiKey, String chatModel,
                                      String chatReasoningEffort, String embedBaseUrl,
                                      String embedApiKey, String embedModel, Integer embedDim) {
    }

    static final class PutSettingsDeserializer
            extends com.fasterxml.jackson.databind.JsonDeserializer<PutSettingsRequest> {
        @Override
        public PutSettingsRequest deserialize(com.fasterxml.jackson.core.JsonParser p,
                                              com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode n = p.getCodec().readTree(p);
            Integer dim = null;
            if (n.hasNonNull("embed_dim")) {
                com.fasterxml.jackson.databind.JsonNode d = n.get("embed_dim");
                if (!d.isIntegralNumber()) {
                    throw com.fasterxml.jackson.databind.exc.MismatchedInputException.from(p,
                            PutSettingsRequest.class,
                            "invalid type: " + d.getNodeType().toString().toLowerCase()
                                    + ", expected i32");
                }
                dim = d.asInt();
            }
            return new PutSettingsRequest(optText(n, "chat_base_url"), optText(n, "chat_api_key"),
                    optText(n, "chat_model"), optText(n, "chat_reasoning_effort"),
                    optText(n, "embed_base_url"), optText(n, "embed_api_key"),
                    optText(n, "embed_model"), dim);
        }
    }

    /** settings_routes.rs:125-135 PutOcrReq（全 Option，serde 缺键=None）。 */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = SettingsPutController.PutOcrDeserializer.class)
    public record PutOcrRequest(String provider, String baseUrl, String apiKey, String backend,
                                String model) {
    }

    static final class PutOcrDeserializer
            extends com.fasterxml.jackson.databind.JsonDeserializer<PutOcrRequest> {
        @Override
        public PutOcrRequest deserialize(com.fasterxml.jackson.core.JsonParser p,
                                         com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode n = p.getCodec().readTree(p);
            return new PutOcrRequest(optText(n, "provider"), optText(n, "base_url"),
                    optText(n, "api_key"), optText(n, "backend"), optText(n, "model"));
        }
    }

    /** settings_routes.rs:191-197 PutTranscribeReq（全 Option，serde 缺键=None）。 */
    @com.fasterxml.jackson.databind.annotation.JsonDeserialize(
            using = SettingsPutController.PutTranscribeDeserializer.class)
    public record PutTranscribeRequest(String baseUrl, String apiKey, String model) {
    }

    static final class PutTranscribeDeserializer
            extends com.fasterxml.jackson.databind.JsonDeserializer<PutTranscribeRequest> {
        @Override
        public PutTranscribeRequest deserialize(com.fasterxml.jackson.core.JsonParser p,
                                                com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws java.io.IOException {
            com.fasterxml.jackson.databind.JsonNode n = p.getCodec().readTree(p);
            return new PutTranscribeRequest(optText(n, "base_url"), optText(n, "api_key"),
                    optText(n, "model"));
        }
    }

    /** Option&lt;String&gt; 的 serde 语义：缺键/显式 null/非字符串值都归 None（先例：org_name）。 */
    private static String optText(com.fasterxml.jackson.databind.JsonNode n, String field) {
        return n.hasNonNull(field) && n.get(field).isTextual() ? n.get(field).asText() : null;
    }

    /** settings_routes.rs:59-122 put（Admin）。 */
    @PutMapping("/api/v1/workspaces/{workspaceId}/settings")
    public Map<String, Object> put(@PathVariable UUID workspaceId,
                                   @RequestBody PutSettingsRequest req,
                                   HttpServletRequest request) {
        UserRow user = user(request);
        workspaceAccess.requireRole(user, workspaceId, Role.ADMIN);
        settings.upsert(workspaceId,
                nonempty(req.chatBaseUrl()), nonempty(req.chatApiKey()), nonempty(req.chatModel()),
                nonempty(req.embedBaseUrl()), nonempty(req.embedApiKey()), nonempty(req.embedModel()),
                req.embedDim());
        if (req.chatReasoningEffort() != null) {
            String effort = nonempty(req.chatReasoningEffort());
            if (effort != null && !List.of("none", "minimal", "low", "medium", "high").contains(effort)) {
                throw AppException.invalid("bad_reasoning_effort",
                        "reasoning effort is one of none, minimal, low, medium, high, or empty");
            }
            settings.setChatReasoningEffort(workspaceId, effort);
        }
        // 配好嵌入模型的这一刻，就是本体索引能开工的最早时刻（settings_routes.rs:102-119）：
        // 注册时自动建的默认库装着本体包而那一刻还没有模型——在这里补排一次，
        // 索引在用户还在想上传什么的时候就建起来了（enqueue 失败不阻断保存，let _ = 同义）。
        if (nonempty(req.embedModel()) != null) {
            for (UUID kbId : kbs.selectIdsForWorkspace(workspaceId)) {
                try {
                    jobs.enqueueUnlessQueued("embed_ontology",
                            mapper.createObjectNode().put("kb_id", kbId.toString()));
                } catch (Exception e) {
                    // Rust 调用点 let _ =：补排失败不阻断保存，抽取侧另有兜底
                }
            }
        }
        return Map.of("ok", true);
    }

    /** settings_routes.rs:142-188 put_ocr（Admin）：配上的这一刻，等它的扫描件/图片重新排队。 */
    @PutMapping("/api/v1/workspaces/{workspaceId}/settings/ocr")
    public Map<String, Object> putOcr(@PathVariable UUID workspaceId,
                                      @RequestBody PutOcrRequest req,
                                      HttpServletRequest request) {
        UserRow user = user(request);
        workspaceAccess.requireRole(user, workspaceId, Role.ADMIN);
        String provider = nonempty(req.provider());
        if (provider != null && !List.of("mineru", "ark").contains(provider)) {
            throw AppException.invalid("unsupported_reader_provider",
                    "The OCR provider must be mineru or ark");
        }
        LlmSettingsRow saved = settings.upsertOcr(workspaceId, provider, nonempty(req.baseUrl()),
                nonempty(req.apiKey()), nonempty(req.backend()), nonempty(req.model()));
        int requeued = 0;
        if (saved.ocrReady()) {
            requeued = documents.requeueWaitingForReader(workspaceId, "ocr").size();
        }
        return Map.of("ok", true, "requeued", requeued);
    }

    /** settings_routes.rs:201-237 put_transcribe（Admin）：等它的录音重新排队。 */
    @PutMapping("/api/v1/workspaces/{workspaceId}/settings/transcribe")
    public Map<String, Object> putTranscribe(@PathVariable UUID workspaceId,
                                             @RequestBody PutTranscribeRequest req,
                                             HttpServletRequest request) {
        UserRow user = user(request);
        workspaceAccess.requireRole(user, workspaceId, Role.ADMIN);
        String baseUrl = nonempty(req.baseUrl());
        String model = nonempty(req.model());
        settings.upsertTranscribe(workspaceId, baseUrl, nonempty(req.apiKey()), model);
        int requeued = 0;
        if (baseUrl != null && model != null) {
            requeued = documents.requeueWaitingForReader(workspaceId, "transcribe").size();
        }
        return Map.of("ok", true, "requeued", requeued);
    }

    /** settings_routes.rs:66-71 nonempty：trim 后非空才有值；空串=保留旧密钥。 */
    private static String nonempty(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }

    /**
     * settings_routes.rs:239-296 test（M5 任务 6 切流演练落位）：连通性测试——对话发一条
     * 最小消息；embedding 试算一条并返回维度；OCR 问一声 health（MinerU GET /health 同
     * 路径）；转写 Java 未迁移读取器执行器（D4 边缘裁剪记录），配置了也只报不可用。
     */
    @org.springframework.web.bind.annotation.PostMapping("/api/v1/workspaces/{id}/settings/test")
    public com.fasterxml.jackson.databind.node.ObjectNode test(
            @org.springframework.web.bind.annotation.PathVariable java.util.UUID id,
            jakarta.servlet.http.HttpServletRequest request) {
        tech.seexun.jknow.store.entity.UserRow user =
                (tech.seexun.jknow.store.entity.UserRow) request.getAttribute(
                        tech.seexun.jknow.web.knowledge.auth.SessionFilter.USER_ATTR);
        workspaceAccess.requireRole(user, id, tech.seexun.jknow.web.knowledge.rbac.Role.ADMIN);
        com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
        tech.seexun.jknow.store.entity.LlmSettingsRow s = settings.get(id);
        if (s == null) {
            for (String k : new String[]{"chat", "embed", "ocr", "transcribe"}) {
                com.fasterxml.jackson.databind.node.ObjectNode row = out.putObject(k);
                row.put("ok", false);
                row.put("error", "Not configured");
            }
            return out;
        }
        com.fasterxml.jackson.databind.node.ObjectNode chat = out.putObject("chat");
        tech.seexun.jknow.llm.ChatClient chatClient = s.chatReady()
                ? new tech.seexun.jknow.llm.ChatClient(s.chatBaseUrl(), s.chatApiKey(),
                        s.chatModel(), s.chatReasoningEffort()) : null;
        if (chatClient == null) {
            chat.put("ok", false).put("error", "Not configured");
        } else {
            try {
                var reply = chatClient.chatAtStreaming(java.util.List.of(
                        tech.seexun.jknow.llm.ChatClient.ChatMessage.user(
                                "Reply with exactly one word: OK")), 0.0);
                chat.put("ok", true);
                chat.put("reply", reply.text().codePoints().limit(50)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint,
                                StringBuilder::append).toString());
            } catch (Exception e) {
                chat.put("ok", false).put("error", String.valueOf(e.getMessage()));
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode embed = out.putObject("embed");
        if (s.embedBaseUrl() == null || s.embedModel() == null) {
            embed.put("ok", false).put("error", "Not configured");
        } else {
            try {
                var v = new tech.seexun.jknow.llm.EmbedClient(s.embedBaseUrl(),
                        s.embedApiKey(), s.embedModel())
                        .embed(java.util.List.of("connectivity test"));
                if (v.isEmpty()) {
                    embed.put("ok", false).put("error", "Empty response");
                } else {
                    embed.put("ok", true).put("dim", v.get(0).length);
                }
            } catch (Exception e) {
                embed.put("ok", false).put("error", String.valueOf(e.getMessage()));
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode ocr = out.putObject("ocr");
        if (s.ocrBaseUrl() == null) {
            ocr.put("ok", false).put("error", "Not configured");
        } else {
            try {
                java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
                java.net.http.HttpResponse<String> resp = client.send(
                        java.net.http.HttpRequest.newBuilder()
                                .uri(java.net.URI.create(s.ocrBaseUrl() + "/health"))
                                .timeout(java.time.Duration.ofSeconds(10)).GET().build(),
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() / 100 != 2) {
                    ocr.put("ok", false).put("error", "the OCR service answered " + resp.statusCode());
                } else {
                    ocr.put("ok", true);
                    ocr.set("version", mapper.readTree(resp.body()).path("version"));
                }
            } catch (Exception e) {
                ocr.put("ok", false).put("error", "the OCR service is unreachable");
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode transcribe = out.putObject("transcribe");
        transcribe.put("ok", false);
        transcribe.put("error", s.transcribeBaseUrl() == null
                ? "Not configured" : "transcription executor not migrated (D4)");
        return out;
    }
}
