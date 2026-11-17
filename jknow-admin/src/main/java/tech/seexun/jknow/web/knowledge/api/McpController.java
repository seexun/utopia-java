package tech.seexun.jknow.web.knowledge.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.auth.ClientContext;
import tech.seexun.jknow.web.knowledge.auth.PersonalTokenService;
import tech.seexun.jknow.web.knowledge.auth.SessionFilter;
import tech.seexun.jknow.web.knowledge.chat.ChatToolSchema;
import tech.seexun.jknow.web.knowledge.chat.ToolExecutor;
import tech.seexun.jknow.web.knowledge.chat.ToolSink;
import tech.seexun.jknow.web.knowledge.rbac.KbAccess;
import tech.seexun.jknow.web.knowledge.rbac.Role;
import tech.seexun.jknow.store.mapper.AccountsMapper;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.UserRow;

/**
 * MCP 服务端（Streamable HTTP，mcp.rs 265 行逐语义——M5 任务 6 切流演练落位：规格 D4
 * 「MCP 迁移」面，此前随 PAT 面留在 Rust 侧，剧本五步 target:rust）。
 *
 * <p>一条路由：POST /api/v1/kbs/{kb_id}/mcp，收 JSON-RPC 2.0。传输选 Streamable HTTP
 * 不选 stdio（0014）：Utopia 本来就是个服务端，stdio 要么起一个子进程反过来连它、要么
 * 再开一个连接池；而这是多人部署，五个人各连各的应该由同一个部署统一服务。
 *
 * <ul>
 *   <li><b>每个 POST 都重新认证一次</b>（0014 的纪律：校验 scope 要在每个工具入口，
 *       不在握手——{@code revoked_at} 中途被写上时必须立刻生效；无状态之后这条性质
 *       是白得的）；</li>
 *   <li><b>响应用 application/json 而不是 SSE</b>：这些工具一问一答，没有服务端主动推
 *       的东西；</li>
 *   <li><b>JSON-RPC 的错误不是 HTTP 的错误</b>：传输成功了、方法失败了——回 200 带
 *       error 体，客户端才解析得动。</li>
 * </ul>
 */
@RestController
public class McpController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 实现的协议版本（客户端报别的版本照样按这个答——规范要求服务端回自己支持的版本）。 */
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_VERSION = "0.1.0";

    /**
     * 只读的那些：任何一枚够得着这个库的令牌都能调。query_data 仍旧不放（0014 混淆代理
     * 一节：它对部署之外的生产库跑 SQL）；业务规则的两个只读工具在列（0021：判据要看得
     * 见）；写规则不放——推理的判据由人写下，一次工具调用分不出「人口述、agent 代打」
     * 与「模型自己编了一条」。
     */
    private static final Set<String> EXPOSED = Set.of(
            "search_chunks", "get_document", "search_docs", "find_entities", "list_rules",
            "rule_matches", "changes", "entity_facts", "neighbors", "timeline", "paths_between");

    /**
     * 会往账本里写的那些（0015 把 0014 的反对意见拆掉了：记忆抽出的事实先等人点头，
     * 没人点头就什么都没进图——放开的不是「写图」，是「提议」）。
     */
    private static final Set<String> WRITABLE = Set.of("remember");

    private final PersonalTokenService tokens;
    private final AccountsMapper accounts;
    private final KbAccess rbac;
    private final ChatToolSchema schema;
    private final ToolExecutor tools;
    private final AuditService audit;

    public McpController(PersonalTokenService tokens, AccountsMapper accounts, KbAccess rbac,
                         ChatToolSchema schema, ToolExecutor tools, AuditService audit) {
        this.tokens = tokens;
        this.accounts = accounts;
        this.rbac = rbac;
        this.schema = schema;
        this.tools = tools;
        this.audit = audit;
    }

    /** can_write = 令牌 scope 是 write 且这个人在这个库里 editor 以上（scope 是上限，角色才是权限）。 */
    private static boolean isExposed(String name, boolean canWrite) {
        return EXPOSED.contains(name) || (canWrite && WRITABLE.contains(name));
    }

    /** 认证 + 授权的产物：人、令牌、库、有效写权限（mcp.rs:80-83 Authorized）。 */
    private record Authorized(UserRow user, PersonalTokenService.Authenticated auth, KbRow kb,
                              boolean canWrite) {
    }

    /**
     * 认证 + 授权，<b>两道</b>（mcp.rs:84-112）：令牌说「这是谁、这枚钥匙够到哪几个库」；
     * requireKb 说「这个人在这个库里是什么角色」。前者只收窄，后者才是权限——一枚
     * scope 全开的令牌落在一个 viewer 手上，仍旧只是 viewer。
     */
    private Authorized authorize(HttpServletRequest request, UUID kbId) {
        String header = request.getHeader("Authorization");
        String raw = header != null && header.startsWith("Bearer ")
                ? header.substring("Bearer ".length()).trim() : null;
        PersonalTokenService.Authenticated auth = tokens.authenticate(raw);
        if (!auth.covers(kbId)) {
            throw AppException.FORBIDDEN;
        }
        // 人：停用立即生效（selectUserById 只回在职的）
        UserRow user = accounts.selectUserById(auth.userId());
        if (user == null) {
            throw AppException.UNAUTHORIZED;
        }
        KbRow kb = rbac.requireKb(user, kbId, Role.VIEWER);
        // 收窄两次：令牌勾了 write 只是没把写排除掉；能不能写，问的是这个人在这个库里的角色
        Role role = rbac.kbRole(user, kb);
        boolean canWrite = auth.canWrite() && role != null && role.rank() >= Role.EDITOR.rank();
        return new Authorized(user, auth, kb, canWrite);
    }

    /** OpenAI 形状 → MCP 形状（mcp.rs:123-145：共用 chat 那一份定义——契约不抄两份）。 */
    private ArrayNode toMcpTools(JsonNode openai, boolean canWrite) {
        ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode t : openai) {
            JsonNode f = t.path("function");
            String name = f.path("name").asText("");
            if (!isExposed(name, canWrite)) {
                continue;
            }
            ObjectNode tool = out.addObject();
            tool.put("name", name);
            tool.put("description", f.path("description").asText(""));
            ObjectNode empty = MAPPER.createObjectNode();
            empty.put("type", "object");
            empty.putObject("properties");
            tool.set("inputSchema", f.has("parameters") ? f.get("parameters") : empty);
        }
        return out;
    }

    private static ObjectNode withResult(JsonNode id, JsonNode result) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        out.set("result", result);
        return out;
    }

    private static ObjectNode rpcErr(JsonNode id, long code, String message) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("jsonrpc", "2.0");
        out.set("id", id);
        ObjectNode error = out.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return out;
    }

    /** mcp.rs:151-161 tool_result（Java 工具面无 structuredContent，缺省不写键——注记在台账）。 */
    private ObjectNode toolResult(ToolExecutor.ToolResult result) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode content = out.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", result.text());
        out.put("isError", result.isError());
        return out;
    }

    /** mcp.rs:171-259 handle。 */
    @PostMapping(path = "/api/v1/kbs/{kbId}/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode handle(@PathVariable UUID kbId, @RequestBody JsonNode req,
                             HttpServletRequest request) {
        Authorized ctx = authorize(request, kbId);
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        JsonNode params = req.has("params") ? req.get("params") : MAPPER.createObjectNode();
        ClientContext client = (ClientContext) request.getAttribute(SessionFilter.CLIENT_ATTR);

        switch (method) {
            case "initialize" -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.put("protocolVersion", PROTOCOL_VERSION);
                result.putObject("capabilities").putObject("tools").put("listChanged", false);
                result.putObject("serverInfo").put("name", "utopia").put("version", SERVER_VERSION);
                return withResult(id, result);
            }
            // 通知没有 id，按规范不该回响应体；HTTP 这一侧总得回点什么——空结果
            case "notifications/initialized", "notifications/cancelled" -> {
                return withResult(null, MAPPER.createObjectNode());
            }
            case "ping" -> {
                return withResult(id, MAPPER.createObjectNode());
            }
            // 列表跟着这枚令牌变：写不了的令牌看不见 remember——列出一个调不动的工具，
            // 等于让对面的 agent 反复试（传空的数据源列表：query_data 没放出来）
            case "tools/list" -> {
                ObjectNode result = MAPPER.createObjectNode();
                result.set("tools", toMcpTools(
                        schema.toolsSchema(ctx.canWrite(), List.of()), ctx.canWrite()));
                return withResult(id, result);
            }
            case "tools/call" -> {
                String name = params.path("name").asText("");
                JsonNode args = params.has("arguments") ? params.get("arguments")
                        : MAPPER.createObjectNode();
                if (!isExposed(name, ctx.canWrite())) {
                    // 三种「不行」要分得开，否则客户端只能反复重试：拿不到的工具
                    // （query_data）是「这一版没放出来」，写工具是「这枚令牌/这个人写不了」
                    String message = WRITABLE.contains(name)
                            ? "Tool '" + name + "' needs a token with the write scope, "
                                    + "held by an editor in this base"
                            : "Tool '" + name + "' is not exposed over MCP in this version";
                    return rpcErr(id, -32601, message);
                }
                // 与聊天共用参数守卫：缺少 query 不能变成一次成功的空搜索
                ToolExecutor.ToolResult result;
                ChatToolSchema.CheckResult checked = schema.checkCall(
                        schema.toolsSchema(ctx.canWrite(), List.of()), name, args.toString());
                if (checked.refused()) {
                    result = new ToolExecutor.ToolResult(checked.refuseMessage(),
                            checked.refuseStep(), true);
                } else {
                    // mounted_sources 仍空（query_data 没放出来）；hasSourceList=false：
                    // MCP 的调用总带着令牌，事实行不印 [n]（#935）——号码指向清单里的
                    // 一条，对手里没有清单的调用方没有意义
                    ToolExecutor.ToolContext toolCtx = new ToolExecutor.ToolContext(
                            kbId, ctx.kb().workspaceId(), List.of(), ctx.canWrite(),
                            ctx.user().id(), null, false, ctx.auth().tokenId());
                    ToolSink sink = new ToolSink();
                    result = tools.dispatch(toolCtx, sink, name, checked.args());
                }
                audit.record(client, kbId, ctx.user().id(), "mcp.tool_called",
                        "personal_token", ctx.auth().tokenId(), "{\"tool\": \"" + name + "\"}");
                return withResult(id, toolResult(result));
            }
            default -> {
                return rpcErr(id, -32601, "Unknown method: " + method);
            }
        }
    }
}
