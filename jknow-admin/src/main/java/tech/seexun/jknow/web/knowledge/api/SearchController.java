package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.search.SearchService;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * search 域端点（对齐旧仓库 utopia-server/src/api/search_routes.rs:14-41 逐字语义；
 * M1 任务 6——BM25 路换 ES+IK，语义等价集评审）。
 *
 * <p>判定序逐字：q trim 空→422 empty_query <b>先于</b> require_kb（search_routes.rs:31-35：
 * 未知库+空 q 也是 422）；然后 top_k 缺省 10 min 50（usize 域）；as_of 经 parse_instant
 * （YYYY-MM-DD 或 RFC3339，与 graph 域共用一根解析）；响应
 * {@code {"results":[ChunkView{id,document_id,seq,text,filename}]}}。
 */
@RestController
public class SearchController {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SearchController.class);

    private final KbAccess rbac;
    private final SearchService search;
    private final SettingsService settings;

    public SearchController(KbAccess rbac, SearchService search, SettingsService settings) {
        this.rbac = rbac;
        this.search = search;
        this.settings = settings;
    }

    /**
     * 检索请求体：自定义反序列化（沿用 AuthController.RegisterRequestDeserializer 先例）——
     * q 必填（缺→serde 对等文案含 line/column），top_k/as_of 可缺省（Rust Option 缺失=None；
     * 全局 fail-on-missing-creator-properties 对记录组件不豁免可缺省项）。
     */
    @JsonDeserialize(using = SearchController.SearchReqDeserializer.class)
    record SearchReq(String q, Integer topK, String asOf) {
    }

    static final class SearchReqDeserializer extends JsonDeserializer<SearchReq> {
        @Override
        public SearchReq deserialize(JsonParser p,
                                     com.fasterxml.jackson.databind.DeserializationContext ctxt)
                throws IOException {
            JsonNode node = p.getCodec().readTree(p);
            // readTree 后解析器停在收尾 } 之后一格：-1 即 serde 报的对象收尾列
            JsonLocation end = p.currentLocation();
            String q = required(node, "q", p, end);
            Integer topK = null;
            if (node.hasNonNull("top_k")) {
                JsonNode t = node.get("top_k");
                if (!t.isIntegralNumber()) {
                    throw MismatchedInputException.from(p, SearchReq.class,
                            "invalid type: " + t.getNodeType().toString().toLowerCase()
                                    + ", expected usize at line " + end.getLineNr()
                                    + " column " + (end.getColumnNr() - 1));
                }
                topK = t.asInt();
            }
            String asOf = node.hasNonNull("as_of") && node.get("as_of").isTextual()
                    ? node.get("as_of").asText() : null;
            return new SearchReq(q, topK, asOf);
        }

        private static String required(JsonNode node, String field, JsonParser p, JsonLocation end)
                throws IOException {
            if (node.hasNonNull(field) && node.get(field).isTextual()) {
                return node.get(field).asText();
            }
            throw MismatchedInputException.from(p, SearchReq.class,
                    "missing field `" + field + "` at line " + end.getLineNr()
                            + " column " + (end.getColumnNr() - 1));
        }
    }

    public record SearchResponse(List<ChunkView> results) {
    }

    // ---- POST /api/v1/kbs/{kbId}/search（search_routes.rs:25-41） ----

    @PostMapping("/api/v1/kbs/{kbId}/search")
    public SearchResponse search(@PathVariable UUID kbId, @RequestBody SearchReq req,
                                 HttpServletRequest request) {
        String q = req.q() == null ? "" : req.q().trim();
        if (q.isEmpty()) {
            throw AppException.invalid("empty_query", "Search query cannot be empty");
        }
        KbRow kb = rbac.requireKb(user(request), kbId, Role.VIEWER);
        // usize 域：负值 serde 在反序列化层就拒（invalid value: integer `-1`, expected usize）；
        // Java 侧等效拦（文案差异记录于任务 6 报告，剧本不覆盖）
        if (req.topK() != null && req.topK() < 0) {
            throw AppException.validation("Invalid `top_k` (expected a non-negative integer)");
        }
        int topK = req.topK() == null ? 10 : Math.min(req.topK(), 50);
        // 时刻参数与 graph 域共用一根解析（graph_routes.rs:27-44 parse_instant）
        OffsetDateTime asOf = GraphController.parseInstant("as_of", req.asOf());
        // 向量第二路（retrieval.rs:76-99）：工作区配了嵌入模型才在；嵌入请求失败/空回复
        // 在适配层静默降级为纯 BM25（retrieval.rs:83-92 的 warn + None）
        return new SearchResponse(
                search.search(kbId, q, topK, asOf, queryEmbedder(kb.workspaceId())));
    }

    /**
     * 查询嵌入端：embed_ready 才建客户端（llm_util.rs:71-80）；失败/空回复 → null
     * （降级不是错误——retrieval.rs:86-92「查询 embedding 失败，降级为纯 BM25」）。
     */
    private SearchService.QueryEmbedder queryEmbedder(UUID workspaceId) {
        LlmSettingsRow settingsRow = settings.get(workspaceId);
        tech.seexun.jknow.llm.EmbedClient client = LlmSupport.embedClient(settingsRow);
        if (client == null) {
            return null;
        }
        return query -> {
            try {
                java.util.List<float[]> vectors = client.embed(java.util.List.of(query));
                return vectors.isEmpty() ? null : vectors.get(0);
            } catch (Exception e) {
                log.warn("查询 embedding 失败，降级为纯 BM25: {}", e.getMessage());
                return null;
            }
        };
    }

    private static UserRow user(HttpServletRequest request) {
        UserRow user = (UserRow) request.getAttribute(SessionFilter.USER_ATTR);
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        return user;
    }
}
