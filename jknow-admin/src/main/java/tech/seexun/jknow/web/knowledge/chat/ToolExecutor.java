package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.search.SearchService;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.query.QueryRunner;
import tech.seexun.jknow.web.knowledge.query.QueryResult;
import tech.seexun.jknow.store.BusinessRulesService;
import tech.seexun.jknow.store.ChatGraphQueries;
import tech.seexun.jknow.store.ChatGraphQueries.FactQualifier;
import tech.seexun.jknow.store.ChatGraphQueries.ToolEntityFact;
import tech.seexun.jknow.store.ConversationsService;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.GraphService;
import tech.seexun.jknow.store.GraphService.Search;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.MemoryService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.PathsService;
import tech.seexun.jknow.store.PathsService.Limits;
import tech.seexun.jknow.store.PathsService.Path;
import tech.seexun.jknow.store.PathsService.PathEdge;
import tech.seexun.jknow.store.ReasoningService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.ChunkView;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.GraphNodeRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;

/**
 * 工具的<b>执行</b>，与谁在调用它们无关（tools.rs + tools_graph.rs 逐语义）。工具定义
 * （给模型看的 JSON schema）在 {@link ChatToolSchema}——那是提示词的一部分，随对话策略走；
 * 这里只管拿到参数之后干什么。必填参数由 checkCall 在派发之前挡下，所以这里不再回落到
 * 用户那句原话——回落产出的是一个看起来没问题的错误答案。
 */
@Service
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    static final int SEARCH_TOP_K = 6;
    static final int TOOL_CHUNK_CHARS = 800;
    /** get_document 一次最多回多少字：检索那边的 800 是让六条命中装得下，这里只有一篇。 */
    static final int DOCUMENT_CHARS = 24_000;
    /** 一次 changes 最多回多少条：刚灌完的库里 asserted 成百上千条，全发只是填满上下文。 */
    static final int CHANGES_LIMIT = 40;

    /** 一次工具调用看得见的世界（tools.rs ToolCtx）：只读——工具改不了它。 */
    public static final class ToolContext {

        public final UUID kbId;
        public final UUID workspaceId;
        /** 本库挂载的数据源。query_data 的安全边界就是这个列表：凭据不出服务端。 */
        public final List<DataSourceView> mountedSources;
        /** editor 及以上才有 remember。 */
        public final boolean canWrite;
        /** 在说话的人：remember 把它记成「谁说的」。对话恒有；MCP 侧为 null（后续里程碑）。 */
        public final UUID actor;
        /** 用户这一轮的原话：图谱工具拿它消歧。null = 没有这句话的调用方。 */
        public final String question;
        /** 这一轮有没有来源清单：对话有，MCP 没有——事实行上的 [n] 只在有清单时印。 */
        public final boolean hasSourceList;
        /**
         * 经哪枚令牌说的（tools.rs ToolCtx.via_token，M5 任务 6 MCP 面起非空）：
         * 「谁说的」要答到 agent 这一层——一个人可以同时挂三个客户端；null = 网页对话。
         */
        public final UUID viaToken;

        public ToolContext(UUID kbId, UUID workspaceId, List<DataSourceView> mountedSources,
                           boolean canWrite, UUID actor, String question, boolean hasSourceList) {
            this(kbId, workspaceId, mountedSources, canWrite, actor, question, hasSourceList, null);
        }

        public ToolContext(UUID kbId, UUID workspaceId, List<DataSourceView> mountedSources,
                           boolean canWrite, UUID actor, String question, boolean hasSourceList,
                           UUID viaToken) {
            this.kbId = kbId;
            this.workspaceId = workspaceId;
            this.mountedSources = mountedSources;
            this.canWrite = canWrite;
            this.actor = actor;
            this.question = question;
            this.hasSourceList = hasSourceList;
            this.viaToken = viaToken;
        }
    }

    /** 一次调用的文本、界面步骤与失败标记；structuredContent 属 MCP 面（后续里程碑）。 */
    public record ToolResult(String text, JsonNode step, boolean isError) {

        static ToolResult of(ChatFrames frames, String text, JsonNode step) {
            return new ToolResult(text, step, false);
        }

        ToolResult asError() {
            return new ToolResult(text, step, true);
        }
    }

    private final ChatFrames frames;
    private final SearchService search;
    private final SettingsService settings;
    private final DocsIndex docs;
    private final ChatGraphQueries graphQueries;
    private final GraphService graph;
    private final PathsService paths;
    private final NamesService names;
    private final ReasoningService reasoning;
    private final BusinessRulesService rules;
    private final QueryRunner queryRunner;
    private final MemoryService memory;
    private final JobsService jobs;
    private final AppEventBus events;
    private final JdbcTemplate jdbc;

    public ToolExecutor(ChatFrames frames, SearchService search, SettingsService settings,
                        DocsIndex docs, ChatGraphQueries graphQueries, GraphService graph,
                        PathsService paths, NamesService names, ReasoningService reasoning,
                        BusinessRulesService rules, QueryRunner queryRunner,
                        MemoryService memory, JobsService jobs, AppEventBus events,
                        JdbcTemplate jdbc) {
        this.frames = frames;
        this.search = search;
        this.settings = settings;
        this.docs = docs;
        this.graphQueries = graphQueries;
        this.graph = graph;
        this.paths = paths;
        this.names = names;
        this.reasoning = reasoning;
        this.rules = rules;
        this.queryRunner = queryRunner;
        this.memory = memory;
        this.jobs = jobs;
        this.events = events;
        this.jdbc = jdbc;
    }

    /**
     * 按名字派发。未知工具不是错误——模型偶尔会编一个名字出来，告诉它没有这个工具，
     * 它下一轮就换一个，比中断整场对话好。
     */
    public ToolResult dispatch(ToolContext ctx, ToolSink sink, String name, JsonNode args) {
        return switch (name) {
            case "search_chunks" -> searchChunks(ctx, sink, args);
            case "get_document" -> getDocument(ctx, sink, args);
            case "search_docs" -> searchDocs(ctx, sink, args);
            case "find_entities" -> findEntities(ctx, sink, args);
            case "entity_facts" -> entityFacts(ctx, sink, args);
            case "neighbors" -> neighbors(ctx, sink, args);
            case "timeline" -> timeline(ctx, sink, args);
            case "paths_between" -> pathsBetween(ctx, sink, args);
            case "changes" -> changes(ctx, args);
            // 业务规则只读（0021）：判据要看得见，写规则不开给模型
            case "list_rules" -> listRules(ctx);
            case "rule_matches" -> ruleMatches(ctx, args);
            case "query_data" -> ctx.mountedSources.isEmpty()
                    ? unknown(name) : queryData(ctx, args);
            case "remember" -> ctx.canWrite ? remember(ctx, args) : unknown(name);
            default -> unknown(name);
        };
    }

    ToolResult unknown(String name) {
        return ToolResult.of(frames, "Unknown tool: " + name,
                ToolStep.of(frames, "tool", name, "unknown").status("not_found").json());
    }

    // ---- 引用清单 ---------------------------------------------------------------

    /** 检索/文档引用条目（tools.rs source_json）。 */
    JsonNode sourceJson(int n, ChunkView c) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("n", n);
        out.put("chunk_id", c.id().toString());
        out.put("document_id", c.documentId().toString());
        out.put("filename", c.filename());
        out.put("excerpt", truncate(c.text(), 160));
        return out;
    }

    /** Charter 引用：前端渲染成手册行，链到 /docs/{slug}#{anchor}。 */
    JsonNode charterSourceJson(int n, DocsIndex.DocsSection h) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("n", n);
        out.put("kind", "charter");
        out.put("slug", h.slug());
        out.put("anchor", h.anchor());
        out.put("heading", h.heading());
        out.put("filename", h.title());
        out.put("excerpt", truncate(h.body(), 160));
        return out;
    }

    static String truncate(String text, int maxChars) {
        String t = text == null ? "" : text.trim();
        if (t.codePoints().count() <= maxChars) {
            return t;
        }
        StringBuilder out = new StringBuilder();
        t.codePoints().limit(maxChars).forEach(out::appendCodePoint);
        return out + "…";
    }

    // ---- 嵌入（EmbedCache 的取用面） ----------------------------------------------

    /**
     * 一段文字的向量，用这个工作区配的嵌入模型；没配或失败时 null——图谱工具照常按子串走，
     * 向量只是第二阶段。同一轮里同样的文字只嵌一次（#971）。
     */
    public float[] embedOnce(ToolContext ctx, ToolSink sink, String text) {
        if (sink != null) {
            float[] cached = sink.embeddings.get(text);
            if (cached != null) {
                return cached;
            }
        }
        LlmSettingsRow s = settings.get(ctx.workspaceId);
        EmbedClient client = LlmSupport.embedClient(s);
        if (client == null) {
            return null;
        }
        float[] vector;
        try {
            List<float[]> out = client.embed(List.of(text));
            vector = out.isEmpty() ? null : out.get(0);
        } catch (Exception e) {
            log.warn("嵌入失败，图谱工具按子串匹配: {}", e.toString());
            return null;
        }
        if (vector == null || sink == null) {
            return vector;
        }
        sink.embeddings.put(text, vector);
        return vector;
    }

    // ---- search_chunks / get_document / search_docs -------------------------------

    ToolResult searchChunks(ToolContext ctx, ToolSink sink, JsonNode args) {
        String q = args.path("query").asText("");
        OffsetDateTime asOf = parseWhen(args.path("as_of").asText(null));
        List<ChunkView> chunks;
        try {
            chunks = search.search(ctx.kbId, q, SEARCH_TOP_K, asOf,
                    query2 -> embedOnce(ctx, sink, query2));
        } catch (Exception e) {
            log.warn("chunk search failed: {}", e.toString());
            return ToolResult.of(frames, "Could not search the documents.",
                    ToolStep.of(frames, "search", q, "failed").status("failed").json())
                    .asError();
        }
        List<String> lines = new ArrayList<>();
        for (ChunkView c : chunks) {
            int n = sink.cite(c.id().toString(), k -> sourceJson(k, c));
            // 行里带 document_id：命中被切在 800 字上时，模型得有办法把整篇要回去
            lines.add("[" + n + "] \"" + c.filename() + "\" section " + (c.seq() + 1)
                    + " (document_id: " + c.documentId() + "):\n" + truncate(c.text(),
                    TOOL_CHUNK_CHARS));
        }
        String text = lines.isEmpty() ? "No results." : String.join("\n\n", lines);
        return ToolResult.of(frames, text,
                ToolStep.of(frames, "search", q, chunks.size() + " sources")
                        .count(chunks.size()).json());
    }

    record DocumentRow(UUID id, String filename, OffsetDateTime docTime) {
    }

    /** documents.rs find_in_kb：本库之外的 id 一律当作不存在——分不出「没有」和「不给你看」。 */
    DocumentRow findDocumentInKb(UUID kbId, UUID documentId) {
        List<DocumentRow> rows = jdbc.query(
                "SELECT id, filename, doc_time FROM documents WHERE id = ? AND kb_id = ?",
                (rs, i) -> new DocumentRow(rs.getObject("id", UUID.class), rs.getString("filename"),
                        rs.getObject("doc_time", OffsetDateTime.class)), documentId, kbId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    record DocumentChunk(UUID id, int seq, String text) {
    }

    List<DocumentChunk> chunksInDocument(UUID kbId, UUID documentId) {
        return jdbc.query("""
                SELECT c.id, c.seq, c.text FROM chunks c
                 WHERE c.kb_id = ? AND c.document_id = ? AND c.superseded_at IS NULL
                 ORDER BY c.seq
                """, (rs, i) -> new DocumentChunk(rs.getObject("id", UUID.class), rs.getInt("seq"),
                        rs.getString("text")), kbId, documentId);
    }

    /**
     * 一篇文档的全文。search_chunks 够不到的东西全在这里：它只回前六条命中、每条切在
     * 800 字上，答案落在第 801 字或没排上的那一块时，检索本身没错，错在没有第二步。
     */
    ToolResult getDocument(ToolContext ctx, ToolSink sink, JsonNode args) {
        String raw = args.path("document_id").asText("");
        UUID id = parseUuid(raw);
        if (id == null) {
            return ToolResult.of(frames, "No document with that id in this knowledge base.",
                    ToolStep.of(frames, "document", "?", "invalid id")
                            .invalid("document_id").json());
        }
        DocumentRow doc = findDocumentInKb(ctx.kbId, id);
        if (doc == null) {
            return ToolResult.of(frames, "No document with that id in this knowledge base.",
                    ToolStep.of(frames, "document", "?", "not found").status("not_found").json());
        }
        List<DocumentChunk> chunks = chunksInDocument(ctx.kbId, id);
        List<String> lines = new ArrayList<>();
        int used = 0;
        int omitted = 0;
        for (DocumentChunk c : chunks) {
            String body = c.text().trim();
            int len = body.codePointCount(0, body.length());
            int room = Math.max(0, DOCUMENT_CHARS - used);
            // 装不下的分块整块不发也不引——发一个引证编号出去而正文不在，
            // 界面上落成一条指不到东西的引证
            if (room == 0) {
                omitted += len;
                continue;
            }
            String piece;
            if (len > room) {
                omitted += len - room;
                StringBuilder sb = new StringBuilder();
                body.codePoints().limit(room).forEach(sb::appendCodePoint);
                piece = sb.toString();
            } else {
                piece = body;
            }
            used += piece.codePointCount(0, piece.length());
            int n = sink.cite(c.id().toString(),
                    k -> sourceJson(k, new ChunkView(c.id(), id, c.seq(), c.text(), doc.filename())));
            lines.add("[" + n + "] \"" + doc.filename() + "\" section " + (c.seq() + 1) + ":\n"
                    + piece);
        }
        if (omitted > 0) {
            lines.add("… truncated, " + omitted + " chars omitted");
        }
        String when = doc.docTime() == null ? "no date" : TimeText.instant(doc.docTime());
        String header = "\"" + doc.filename() + "\" (" + when + ") — " + chunks.size()
                + " section(s):";
        String text = lines.isEmpty() ? header + "\n(no text)"
                : header + "\n\n" + String.join("\n\n", lines);
        return ToolResult.of(frames, text,
                ToolStep.of(frames, "document", doc.filename(), chunks.size() + " sections")
                        .count(chunks.size()).json());
    }

    ToolResult searchDocs(ToolContext ctx, ToolSink sink, JsonNode args) {
        String q = args.path("query").asText("");
        List<DocsIndex.DocsSection> hits = docs.search(q, 4);
        List<String> lines = new ArrayList<>();
        for (DocsIndex.DocsSection h : hits) {
            int n = sink.cite("charter:" + h.slug() + "#" + h.anchor(),
                    k -> charterSourceJson(k, h));
            lines.add("[" + n + "] Utopia Charter — " + h.title() + " › " + h.heading()
                    + ":\n" + truncate(h.body(), 1600));
        }
        String text = lines.isEmpty() ? "No matching manual sections."
                : String.join("\n\n", lines);
        return ToolResult.of(frames, text,
                ToolStep.of(frames, "docs", q, hits.size() + " sections").count(hits.size())
                        .json());
    }

    // ---- 图谱工具的共享件 ----------------------------------------------------------

    /** 名字匹配的排序：类型判出来的在前、同名精确命中在前、事实多的在前（tools_graph rank）。 */
    static List<GraphNodeRow> rank(List<GraphNodeRow> hits, String query) {
        String q = query.trim().toLowerCase();
        // 没类型的短语（抽取漏进来的描述）在有别的候选时不列；全是 untyped 时照列——
        // 一个类型都没判出来的库也得能用。叫这个名字的照列（0009）
        boolean anyTyped = hits.stream().anyMatch(n -> n.typeLabel() != null);
        List<GraphNodeRow> pool = new ArrayList<>();
        for (GraphNodeRow n : hits) {
            if (!anyTyped || n.typeLabel() != null
                    || n.name().trim().toLowerCase().equals(q)) {
                pool.add(n);
            }
        }
        pool.sort((a, b) -> {
            boolean ea = a.name().trim().toLowerCase().equals(q);
            boolean eb = b.name().trim().toLowerCase().equals(q);
            int byExact = Boolean.compare(eb, ea);
            if (byExact != 0) {
                return byExact;
            }
            int byDegree = Long.compare(b.degree(), a.degree());
            if (byDegree != 0) {
                return byDegree;
            }
            return a.name().compareTo(b.name());
        });
        return pool;
    }

    /** 第一名是不是明显的那一个：只有它、只有它精确命中、或事实数是第二名的三倍。 */
    static boolean dominant(List<GraphNodeRow> ranked, String query) {
        if (ranked.isEmpty()) {
            return false;
        }
        if (ranked.size() == 1) {
            return true;
        }
        String q = query.trim().toLowerCase();
        boolean firstExact = ranked.get(0).name().trim().toLowerCase().equals(q);
        boolean secondExact = ranked.get(1).name().trim().toLowerCase().equals(q);
        return (firstExact && !secondExact)
                || ranked.get(0).degree() >= 3 * Math.max(1, ranked.get(1).degree());
    }

    static String nodeLine(GraphNodeRow n) {
        String dis = n.disambiguator() == null ? "" : " (" + n.disambiguator() + ")";
        // 没判出类型的实体照样能被搜到、被引用（0009）
        return n.id() + " | " + n.name() + dis + " | "
                + (n.typeLabel() == null ? "untyped" : n.typeLabel()) + " | " + n.degree()
                + " facts";
    }

    /** 名字里含了几个词（大小写不敏感）。 */
    static int wordScore(String name, List<String> words) {
        String lower = name.toLowerCase();
        int score = 0;
        for (String w : words) {
            if (lower.contains(w.toLowerCase())) {
                score++;
            }
        }
        return score;
    }

    /**
     * 子串找不到时按词找（tools_graph lookup 的第二路）：每个词各捞一把，名字里含的词数
     * 达到「全部减一、至少两个」的候选算命中。回查到的实体与总数（按词拼凑的那路没有总数）。
     */
    record Lookup(List<GraphNodeRow> hits, Long total) {
    }

    Lookup lookup(ToolContext ctx, String raw) {
        Search found = graph.searchEntities(ctx.kbId, raw, 8, 0, null);
        if (!found.entities().isEmpty()) {
            return new Lookup(found.entities(), found.total());
        }
        List<String> words = new ArrayList<>();
        for (String w : raw.split("\\s+")) {
            if (w.length() >= 2) {
                words.add(w);
            }
        }
        if (words.size() < 2) {
            return new Lookup(found.entities(), found.total());
        }
        Map<UUID, GraphNodeRow> pool = new LinkedHashMap<>();
        for (String w : words) {
            for (GraphNodeRow n : graph.searchEntities(ctx.kbId, w, 40, 0, null).entities()) {
                pool.putIfAbsent(n.id(), n);
            }
        }
        int need = Math.max(2, words.size() - 1);
        record Scored(int score, GraphNodeRow node) {
        }
        List<Scored> scored = new ArrayList<>();
        for (GraphNodeRow n : pool.values()) {
            int s = wordScore(n.name(), words);
            if (s >= need) {
                scored.add(new Scored(s, n));
            }
        }
        scored.sort((a, b) -> {
            int byScore = Integer.compare(b.score(), a.score());
            return byScore != 0 ? byScore : Long.compare(b.node().degree(), a.node().degree());
        });
        List<GraphNodeRow> out = new ArrayList<>();
        for (int i = 0; i < Math.min(8, scored.size()); i++) {
            out.add(scored.get(i).node());
        }
        return new Lookup(out, null);
    }

    /** 同名候选按问题排（rank_by_question）：问题嵌一次，与候选的上下文画像比。 */
    record Ranked(List<GraphNodeRow> nodes, boolean byQuestion) {
    }

    Ranked rankByQuestion(ToolContext ctx, ToolSink sink, List<GraphNodeRow> ranked,
                          String query) {
        if (ranked.size() < 2 || ctx.question == null) {
            return new Ranked(ranked, false);
        }
        float[] vec = embedOnce(ctx, sink, ctx.question);
        if (vec == null) {
            return new Ranked(ranked, false);
        }
        List<UUID> ids = ranked.stream().map(GraphNodeRow::id).toList();
        Map<UUID, Double> dist;
        try {
            dist = graphQueries.profileDistances(ctx.kbId, ids, vec);
        } catch (Exception e) {
            return new Ranked(ranked, false);
        }
        return new Ranked(reorderByDistance(ranked, query, dist), true);
    }

    /** 纯函数：精确命中的一档在前；档内按画像距离升序，没有画像的垫底；再按事实数。 */
    static List<GraphNodeRow> reorderByDistance(List<GraphNodeRow> ranked, String query,
                                                Map<UUID, Double> dist) {
        String q = query.trim().toLowerCase();
        List<GraphNodeRow> out = new ArrayList<>(ranked);
        out.sort((a, b) -> {
            boolean ea = a.name().trim().toLowerCase().equals(q);
            boolean eb = b.name().trim().toLowerCase().equals(q);
            int byExact = Boolean.compare(eb, ea);
            if (byExact != 0) {
                return byExact;
            }
            double da = dist.getOrDefault(a.id(), Double.MAX_VALUE);
            double db = dist.getOrDefault(b.id(), Double.MAX_VALUE);
            int byDist = Double.compare(da, db);
            if (byDist != 0) {
                return byDist;
            }
            return Long.compare(b.degree(), a.degree());
        });
        return out;
    }

    /** 实体参数解析成 id：uuid 直收；名字走 rank，挑了谁写进 note 交给结果开头。 */
    record Resolved(UUID id, String name, String note, boolean byId) {
    }

    static final class UnresolvedException extends RuntimeException {

        UnresolvedException(String message) {
            super(message);
        }
    }

    static final class ReadFailedException extends RuntimeException {

        ReadFailedException(Throwable cause) {
            super(cause);
        }
    }

    Resolved resolve(ToolContext ctx, ToolSink sink, String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isEmpty()) {
            throw new UnresolvedException("an entity name or id is required");
        }
        UUID id = parseUuid(trimmed);
        if (id != null) {
            return new Resolved(id, trimmed, null, true);
        }
        Lookup found;
        try {
            found = lookup(ctx, trimmed);
        } catch (Exception e) {
            throw new ReadFailedException(e);
        }
        Ranked ranked = rankByQuestion(ctx, sink, rank(found.hits(), trimmed), trimmed);
        if (ranked.nodes().isEmpty()) {
            throw new UnresolvedException("no entity named \"" + trimmed + "\" in this base");
        }
        GraphNodeRow first = ranked.nodes().get(0);
        rememberNode(sink, first);
        List<String> others = new ArrayList<>();
        for (int i = 1; i < Math.min(5, ranked.nodes().size()); i++) {
            GraphNodeRow n = ranked.nodes().get(i);
            others.add(n.name() + " (" + (n.typeLabel() == null ? "untyped" : n.typeLabel())
                    + ", " + n.degree() + " facts, " + n.id() + ")");
        }
        StringBuilder note = new StringBuilder("\"" + trimmed + "\" = " + first.name() + " ("
                + (first.typeLabel() == null ? "untyped" : first.typeLabel()) + ", "
                + first.degree() + " facts)");
        if (ranked.byQuestion()) {
            note.append(", closest to the question");
        }
        if (!others.isEmpty()) {
            note.append("; other matches: ").append(String.join("; ", others))
                    .append(". Pass an id to choose another.");
        }
        return new Resolved(first.id(), first.name(), note.toString(), false);
    }

    void rememberNode(ToolSink sink, GraphNodeRow n) {
        ObjectNode out = frames.mapper.createObjectNode();
        out.put("id", n.id().toString());
        out.put("name", n.name());
        if (n.typeLabel() == null) {
            out.putNull("type");
        } else {
            out.put("type", n.typeLabel());
        }
        sink.rememberEntity(out);
    }

    /** 时刻参数三件套：at 世界轴，as_of/before 记录轴（before 优先，减一微秒）。 */
    record Moments(OffsetDateTime at, OffsetDateTime asOf, OffsetDateTime before) {
    }

    static Moments moments(JsonNode args) {
        OffsetDateTime at = parseWhen(args.path("at").asText(null));
        OffsetDateTime before = parseWhen(args.path("before").asText(null));
        OffsetDateTime asOf = before != null ? justBefore(before)
                : parseWhen(args.path("as_of").asText(null));
        return new Moments(at, asOf, before);
    }

    static int limitOf(JsonNode args, int dflt) {
        long n = args.path("limit").asLong(0);
        return (int) Math.min(n > 0 ? n : dflt, 300);
    }

    static boolean containsCi(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    // ---- find_entities --------------------------------------------------------------

    ToolResult findEntities(ToolContext ctx, ToolSink sink, JsonNode args) {
        String name = args.path("name").asText("");
        Lookup found;
        try {
            found = lookup(ctx, name);
        } catch (Exception e) {
            log.warn("Entity lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not look up entities.",
                    ToolStep.of(frames, "entity", name, "failed").status("failed").json())
                    .asError();
        }
        int read = found.hits().size();
        Ranked ranked = rankByQuestion(ctx, sink, rank(found.hits(), name), name);
        boolean clear = ranked.byQuestion() || dominant(ranked.nodes(), name);
        String text;
        if (ranked.nodes().isEmpty()) {
            text = "No matching entities.";
        } else if (clear) {
            List<String> lines = new ArrayList<>();
            lines.add("Best match: " + nodeLine(ranked.nodes().get(0)));
            if (ranked.nodes().size() > 1) {
                lines.add("Other matches:");
                for (int i = 1; i < ranked.nodes().size(); i++) {
                    lines.add(nodeLine(ranked.nodes().get(i)));
                }
            }
            text = String.join("\n", lines);
        } else {
            List<String> lines = new ArrayList<>();
            lines.add("Several entities match \"" + name
                    + "\"; pick by type and fact count, or ask the user:");
            ranked.nodes().forEach(n -> lines.add(nodeLine(n)));
            text = String.join("\n", lines);
        }
        // 截断要说出来：只读了前几个，模型会把它们当成这个名字的全部
        if (found.total() != null && found.total() > read) {
            text += "\n(" + found.total() + " entities have a name containing \"" + name
                    + "\"; only the first " + read + " were read. A longer or more exact name "
                    + "narrows the search.)";
        }
        // 同名歧义时候选只是搜索结果，不是认下的实体；选中的那个在它被读的时候记
        if (clear && !ranked.nodes().isEmpty()) {
            rememberNode(sink, ranked.nodes().get(0));
        }
        ToolStep step = ToolStep.of(frames, "entity", name, ranked.nodes().size() + " matches")
                .count(ranked.nodes().size());
        if (found.total() != null && found.total() > read) {
            step = step.total(found.total());
        }
        return ToolResult.of(frames, text, step.json());
    }

    // ---- 事实行渲染（entity_facts / neighbors / timeline / paths_between 共用） ------

    /** 字面值宾语给模型看的样子：{value,unit} → "28000 CNY"，布尔 → ✓/✗，{summary} → 摘要。 */
    static String literalText(JsonNode v) {
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isTextual()) {
            return v.asText();
        }
        if (v.isBoolean() || v.isNumber()) {
            return v.toString();
        }
        JsonNode val = v.path("value");
        if (!val.isMissingNode()) {
            String text;
            if (val.isBoolean()) {
                text = val.asBoolean() ? "✓" : "✗";
            } else if (val.isTextual()) {
                text = val.asText();
            } else if (val.isNull()) {
                return null;
            } else {
                text = val.toString();
            }
            String unit = v.path("unit").asText("");
            return unit.isEmpty() ? text : text + " " + unit;
        }
        JsonNode summary = v.path("summary");
        if (summary.isTextual()) {
            return summary.asText();
        }
        return v.toString();
    }

    /** 时间线关上一行时接任的那一端怎么写：接任者或新宾语的名字，属性事实是新的值。 */
    static String successorText(String name, JsonNode value) {
        if (name != null) {
            return name;
        }
        return literalText(value);
    }

    /** 边上的属性跟在对端后面：`Vega Capital [amount: 5000000000 $]`。 */
    static String qualifiersText(ToolEntityFact f) {
        if (f.qualifiers().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (FactQualifier q : f.qualifiers()) {
            String v;
            if (q.value() != null && q.value().has("value")) {
                JsonNode val = q.value().path("value");
                v = val.isTextual() ? val.asText() : val.toString();
            } else if (q.entityName() != null) {
                v = q.entityName();
            } else if (q.value() != null) {
                v = literalText(q.value());
            } else {
                v = "?";
            }
            if (v == null) {
                v = "null";
            }
            String u = q.value() == null ? "" : q.value().path("unit").asText("");
            parts.add(q.key() + ": " + v + (u.isEmpty() ? "" : " " + u));
        }
        return " [" + String.join(", ", parts) + "]";
    }

    /** 事实的另一端：对端实体或属性值。 */
    static String otherText(ToolEntityFact f) {
        String literal = f.otherName() == null ? literalText(f.objectValue()) : null;
        if (f.otherName() != null) {
            return f.otherName();
        }
        return literal == null ? "?" : literal;
    }

    String rangeText(ToolEntityFact f, String closerMark) {
        String successor = successorText(f.closedBy(), f.closedByValue());
        String range = TimeText.span(new TimeText.Span(f.validFrom(), f.validFromPrecision(),
                f.validTo(), f.validToPrecision(), f.holdsFrom(), f.holdsTo(), f.endDerived(),
                f.correctedEnds(), successor, closerMark, f.correctionNote()));
        return range.isEmpty() ? "" : " (" + range + ")";
    }

    static String confidenceText(ToolEntityFact f) {
        return "[" + Math.round(f.confidence() * 100) + "%]";
    }

    /** 谓词组的抬头：出边 pred →，入边 ← pred。 */
    static String groupKey(ToolEntityFact f) {
        String pred = f.predicateLabel() == null ? "?" : f.predicateLabel();
        return "out".equals(f.direction()) ? pred + " →" : "← " + pred;
    }

    /** 按谓词分组，多的组在前；组内保持传入顺序（时间序）。 */
    static List<Map.Entry<String, List<ToolEntityFact>>> grouped(List<ToolEntityFact> facts) {
        Map<String, List<ToolEntityFact>> groups = new TreeMap<>();
        for (ToolEntityFact f : facts) {
            groups.computeIfAbsent(groupKey(f), k -> new ArrayList<>()).add(f);
        }
        List<Map.Entry<String, List<ToolEntityFact>>> out = new ArrayList<>(groups.entrySet());
        out.sort((a, b) -> {
            int bySize = Integer.compare(b.getValue().size(), a.getValue().size());
            return bySize != 0 ? bySize : a.getKey().compareTo(b.getKey());
        });
        return out;
    }

    /** 过滤没命中时告诉模型这个实体身上有哪些谓词：它猜的词和库里的词常常对不上（#560）。 */
    static String predicatesOf(List<ToolEntityFact> facts) {
        Map<String, Integer> counts = new TreeMap<>();
        for (ToolEntityFact f : facts) {
            counts.merge(groupKey(f), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> named = new ArrayList<>(counts.entrySet());
        named.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(20, named.size()); i++) {
            out.add(named.get(i).getKey() + " (" + named.get(i).getValue() + ")");
        }
        return String.join(", ", out);
    }

    /** 事实过滤（FactFilter）：谓词按子串、对象类型按子串、时段按说出来的区间。 */
    record FactFilter(String predicate, String objectType, OffsetDateTime since,
                      OffsetDateTime until) {

        static FactFilter fromArgs(JsonNode args) {
            String predicate = args.path("predicate").asText("");
            String objectType = args.path("object_type").asText("");
            return new FactFilter(
                    predicate.isBlank() ? null : predicate,
                    objectType.isBlank() ? null : objectType,
                    parseWhen(args.path("since").asText(null)),
                    parseWhen(args.path("until").asText(null)));
        }

        boolean narrows() {
            return predicate != null || objectType != null || since != null || until != null;
        }

        FactFilter withoutPredicate() {
            return new FactFilter(null, objectType, since, until);
        }

        boolean keeps(ToolEntityFact f) {
            if (predicate != null && !containsCi(f.predicateKey(), predicate)
                    && !containsCi(f.predicateLabel(), predicate)) {
                return false;
            }
            if (objectType != null && !containsCi(f.otherType(), objectType)) {
                return false;
            }
            if (until != null && f.validFrom() != null && f.validFrom().isAfter(until)) {
                return false;
            }
            if (since != null && f.validTo() != null && f.validTo().isBefore(since)) {
                return false;
            }
            return true;
        }
    }

    /** 证据号（#935）：显示的事实各自的第一条有效证据；MCP/读不出时为空。 */
    static final class Evidence {

        private final Map<UUID, ChatGraphQueries.LiveEvidence> byFact = new HashMap<>();

        Evidence(ToolExecutor executor, ToolContext ctx, List<UUID> factIds,
                 OffsetDateTime asOf) {
            if (!ctx.hasSourceList || factIds.isEmpty()) {
                return;
            }
            try {
                byFact.putAll(executor.graphQueries.firstLiveEvidence(ctx.kbId, factIds, asOf));
            } catch (Exception e) {
                log.warn("Evidence lookup for citation marks failed: {}", e.toString());
            }
        }

        /** 关上这一行的那一行的号（#970 第二步）。 */
        String closerMark(ToolExecutor executor, ToolSink sink, ToolEntityFact f) {
            if (f.closedById() == null) {
                return "";
            }
            return mark(executor, sink, f.closedById());
        }

        /** ` [n]`：这条事实的证据登进清单后的号；没有有效证据的事实（派生也是）不带号。 */
        String mark(ToolExecutor executor, ToolSink sink, UUID factId) {
            ChatGraphQueries.LiveEvidence ev = byFact.get(factId);
            if (ev == null) {
                return "";
            }
            int n = sink.cite(ev.chunk().id().toString(),
                    k -> executor.sourceJson(k, ev.chunk()));
            if (ev.quote() != null) {
                sink.addQuote(n, ev.quote());
            }
            return " [" + n + "]";
        }
    }

    /** 谓词参数与库里的词对齐：嵌一次，取最近的关系类型，距离在上限内的算它说的是那几个。 */
    static final double PREDICATE_DISTANCE = 0.45;
    static final int PREDICATE_CANDIDATES = 5;

    Set<String> alignedPredicates(ToolContext ctx, ToolSink sink, String word) {
        float[] vec = embedOnce(ctx, sink, word);
        if (vec == null) {
            return Set.of();
        }
        List<ChatGraphQueries.RelationCandidate> near;
        try {
            near = graphQueries.nearestRelationTypes(ctx.kbId, vec, PREDICATE_CANDIDATES);
        } catch (Exception e) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (ChatGraphQueries.RelationCandidate c : near) {
            if (c.distance() <= PREDICATE_DISTANCE) {
                keys.add(c.key());
            }
        }
        return keys;
    }

    /** 过滤；谓词按子串对不上时向量对齐一次，回一句说明给结果开头。 */
    record Filtered(List<ToolEntityFact> kept, String note) {
    }

    Filtered filtered(ToolContext ctx, ToolSink sink, List<ToolEntityFact> facts,
                      FactFilter filter) {
        List<ToolEntityFact> kept = new ArrayList<>();
        for (ToolEntityFact f : facts) {
            if (filter.keeps(f)) {
                kept.add(f);
            }
        }
        if (filter.predicate() == null) {
            return new Filtered(kept, null);
        }
        if (!kept.isEmpty() || facts.isEmpty()) {
            return new Filtered(kept, null);
        }
        Set<String> keys = alignedPredicates(ctx, sink, filter.predicate());
        if (keys.isEmpty()) {
            return new Filtered(kept, null);
        }
        List<ToolEntityFact> widened = new ArrayList<>();
        for (ToolEntityFact f : facts) {
            if (filter.withoutPredicate().keeps(f) && f.predicateKey() != null
                    && keys.contains(f.predicateKey())) {
                widened.add(f);
            }
        }
        List<String> names = new ArrayList<>(keys);
        java.util.Collections.sort(names);
        return new Filtered(widened,
                "predicate \"" + filter.predicate() + "\" read as " + String.join(", ", names));
    }

    // ---- entity_facts ----------------------------------------------------------------

    ToolResult entityFacts(ToolContext ctx, ToolSink sink, JsonNode args) {
        String raw = args.path("entity_id").asText(args.path("entity").asText(""));
        Resolved who;
        try {
            who = resolve(ctx, sink, raw);
        } catch (ReadFailedException e) {
            return ToolResult.of(frames, "Could not look up entities.",
                    ToolStep.of(frames, "facts", "?", "failed").status("failed").json())
                    .asError();
        } catch (UnresolvedException e) {
            return ToolResult.of(frames, "Invalid entity: " + e.getMessage()
                    + " (expected a name, or the uuid returned by find_entities).",
                    ToolStep.of(frames, "facts", "?", "invalid id").status("not_found").json())
                    .asError();
        }
        Moments m = moments(args);
        FactFilter filter = FactFilter.fromArgs(args);
        int limit = limitOf(args, 80);
        ChatGraphQueries.EntityToolsDetail detail;
        try {
            detail = graphQueries.entityFactsForTools(ctx.kbId, who.id(), m.at(), m.asOf());
        } catch (Exception e) {
            log.warn("Entity facts lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not read the entity facts.",
                    ToolStep.of(frames, "facts", "?", "failed").status("failed").json())
                    .asError();
        }
        if (detail == null) {
            return ToolResult.of(frames, "Entity not found.",
                    ToolStep.of(frames, "facts", "?", "failed").status("failed").json())
                    .asError();
        }
        GraphNodeRow node = detail.node();
        List<ToolEntityFact> facts = detail.facts();
        // 按 id 读的也是认下：模型常常先搜到一串同名的，再按 id 读其中一个
        rememberNode(sink, node);
        // 规则的结论也是这个实体的一部分（0021）：不给的话模型会拿读数自己再判一遍
        List<tech.seexun.jknow.store.mapper.ReasoningMapper.DerivedFactView> derived;
        try {
            derived = reasoning.derivedForEntity(ctx.kbId, who.id(), m.at(), m.asOf());
        } catch (Exception e) {
            log.warn("Derived facts lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not read the derived facts.",
                    ToolStep.of(frames, "facts", node.name(), "failed").status("failed").json())
                    .asError();
        }
        List<String> derivedLines = new ArrayList<>();
        for (var d : derived) {
            String object = d.object() != null ? d.object()
                    : (d.objectValue() == null ? "?" : d.objectValue().toString());
            derivedLines.add(d.subject() + " · " + d.predicate() + " · " + object
                    + " [rule: " + (d.ruleName() == null || d.ruleName().isEmpty()
                            ? d.rule() : d.ruleName()) + "]");
        }
        // 名字单独一行（0041）：「海探1」和「海洋探测器1号」是同一个
        List<tech.seexun.jknow.store.entity.NameView> nameRows = List.of();
        try {
            nameRows = names.forEntity(ctx.kbId, who.id(), m.asOf());
        } catch (Exception e) {
            log.warn("Entity names lookup failed: {}", e.toString());
        }
        Filtered f = filtered(ctx, sink, facts, filter);
        List<ToolEntityFact> shown = f.kept().stream().limit(limit).toList();
        List<String> lines = new ArrayList<>();
        if (who.note() != null) {
            lines.add(who.note());
        }
        List<String> otherNames = new ArrayList<>();
        for (var n : nameRows) {
            if (!n.canonical()) {
                otherNames.add(n.name());
            }
        }
        if (!otherNames.isEmpty()) {
            lines.add("Also known as: " + String.join(", ", otherNames));
        }
        if (f.note() != null) {
            lines.add(f.note());
        }
        String typeLabel = node.typeLabel() == null ? "untyped" : node.typeLabel();
        if (facts.isEmpty() && derived.isEmpty()) {
            lines.add(m.at() != null
                    ? node.name() + ": no facts valid as of " + TimeText.instant(m.at()) + "."
                    : node.name() + ": no recorded facts.");
        } else {
            StringBuilder head = new StringBuilder(node.name() + " (" + typeLabel + ") · "
                    + facts.size() + " facts");
            if (filter.narrows()) {
                head.append(", ").append(f.kept().size()).append(" match the filter");
                if (f.kept().isEmpty()) {
                    head.append(". Predicates on this entity: ").append(predicatesOf(facts));
                }
            }
            if (shown.size() < f.kept().size()) {
                head.append(", ").append(shown.size()).append(" shown. Narrow with predicate=, "
                        + "object_type=, since/until; timeline for the dated ones; neighbors "
                        + "for the related entities");
            }
            lines.add(head.toString());
            Evidence evidence = new Evidence(this, ctx, withClosers(shown), m.asOf());
            for (var group : grouped(shown)) {
                lines.add("## " + group.getKey() + " (" + group.getValue().size() + ")");
                for (ToolEntityFact fact : group.getValue()) {
                    String closer = evidence.closerMark(this, sink, fact);
                    lines.add(otherText(fact) + qualifiersText(fact)
                            + rangeText(fact, closer) + " " + confidenceText(fact)
                            + evidence.mark(this, sink, fact.id()));
                }
            }
            // 派生事实自己不带号：它是规则算出来的，出处在它的前提上
            lines.addAll(derivedLines);
        }
        ToolStep step = ToolStep.of(frames, "facts", node.name(),
                entityFactsDetail(shown.size(), m)).count(shown.size())
                        .moments(m.at(), m.asOf(), m.before());
        return ToolResult.of(frames, String.join("\n", lines), step.json());
    }

    /** 显示的事实加上关上它们的那几行：一次证据查询把两边的块都读回来。 */
    static List<UUID> withClosers(List<ToolEntityFact> facts) {
        List<UUID> ids = new ArrayList<>();
        for (ToolEntityFact f : facts) {
            ids.add(f.id());
            if (f.closedById() != null) {
                ids.add(f.closedById());
            }
        }
        return ids;
    }

    static String entityFactsDetail(int count, Moments m) {
        String record;
        if (m.before() != null) {
            record = "as recorded before " + TimeText.instant(m.before());
        } else if (m.asOf() != null) {
            record = "as recorded by " + TimeText.instant(m.asOf());
        } else {
            record = null;
        }
        if (m.at() != null && record != null) {
            return count + " facts at " + TimeText.instant(m.at()) + ", " + record;
        }
        if (m.at() != null) {
            return count + " facts as of " + TimeText.instant(m.at());
        }
        if (record != null) {
            return count + " facts " + record;
        }
        return count + " facts";
    }

    // ---- neighbors ------------------------------------------------------------------

    ToolResult neighbors(ToolContext ctx, ToolSink sink, JsonNode args) {
        String raw = args.path("entity").asText(args.path("entity_id").asText(""));
        Resolved who;
        try {
            who = resolve(ctx, sink, raw);
        } catch (ReadFailedException e) {
            return ToolResult.of(frames, "Could not look up entities.",
                    ToolStep.of(frames, "neighbors", "?", "failed").status("failed").json())
                    .asError();
        } catch (UnresolvedException e) {
            return ToolResult.of(frames, "Unknown entity: " + e.getMessage() + ".",
                    ToolStep.of(frames, "neighbors", "?", "unknown entity").status("not_found")
                            .json()).asError();
        }
        Moments m = moments(args);
        FactFilter filter = FactFilter.fromArgs(args);
        int limit = limitOf(args, 40);
        ChatGraphQueries.EntityToolsDetail detail;
        try {
            detail = graphQueries.entityFactsForTools(ctx.kbId, who.id(), m.at(), m.asOf());
        } catch (Exception e) {
            log.warn("Entity neighbors lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not read the entity facts.",
                    ToolStep.of(frames, "neighbors", "?", "failed").status("failed").json())
                    .asError();
        }
        if (detail == null) {
            return ToolResult.of(frames, "Entity not found.",
                    ToolStep.of(frames, "neighbors", "?", "not found").status("not_found")
                            .json());
        }
        GraphNodeRow node = detail.node();
        rememberNode(sink, node);
        // 邻居是对端实体；属性值不算邻居，entity_facts 里有
        Filtered f = filtered(ctx, sink, detail.facts(), filter);
        List<ToolEntityFact> linked = new ArrayList<>();
        for (ToolEntityFact fact : f.kept()) {
            if (fact.otherId() != null) {
                linked.add(fact);
            }
        }
        List<ToolEntityFact> shown = linked.stream().limit(limit).toList();
        List<String> lines = new ArrayList<>();
        if (who.note() != null) {
            lines.add(who.note());
        }
        if (f.note() != null) {
            lines.add(f.note());
        }
        String typeLabel = node.typeLabel() == null ? "untyped" : node.typeLabel();
        if (shown.isEmpty()) {
            List<ToolEntityFact> allLinked = new ArrayList<>();
            for (ToolEntityFact fact : detail.facts()) {
                if (fact.otherId() != null) {
                    allLinked.add(fact);
                }
            }
            String hint = filter.narrows() && !allLinked.isEmpty()
                    ? " Predicates on this entity: " + predicatesOf(allLinked) : "";
            lines.add(node.name() + " (" + typeLabel + "): no linked entities"
                    + (filter.narrows() ? " match the filter" : "") + "." + hint);
        } else {
            Evidence evidence = new Evidence(this, ctx, withClosers(shown), m.asOf());
            List<Map.Entry<String, List<ToolEntityFact>>> groups = grouped(shown);
            // 数的是对端实体，不是事实：同一条边常是两条事实
            Set<UUID> entities = new HashSet<>();
            linked.forEach(x -> entities.add(x.otherId()));
            StringBuilder head = new StringBuilder(node.name() + " (" + typeLabel + "): "
                    + entities.size() + " linked entities under " + groups.size()
                    + " predicates");
            if (shown.size() < linked.size()) {
                head.append(" (").append(shown.size())
                        .append(" shown; narrow with predicate= or object_type=)");
            }
            lines.add(head.toString());
            for (var group : groups) {
                List<String> items = new ArrayList<>();
                for (ToolEntityFact fact : group.getValue()) {
                    String ty = fact.otherType() == null ? "" : " [" + fact.otherType() + "]";
                    String closer = evidence.closerMark(this, sink, fact);
                    items.add(otherText(fact) + ty + rangeText(fact, closer) + " "
                            + confidenceText(fact) + evidence.mark(this, sink, fact.id()));
                }
                lines.add(group.getKey() + " " + String.join(" · ", items));
            }
        }
        return ToolResult.of(frames, String.join("\n", lines),
                ToolStep.of(frames, "neighbors", node.name(),
                        shown.size() + " of " + linked.size() + " linked")
                        .count(shown.size()).total(linked.size()).json());
    }

    // ---- timeline ------------------------------------------------------------------

    /** 事实行整行（tools.rs fact_line）：`works at → 星云科技 (2023-08 → now) [90%]`。 */
    String factLine(ToolEntityFact f, String closerMark) {
        String core = "out".equals(f.direction())
                ? (f.predicateLabel() == null ? "?" : f.predicateLabel()) + " → " + otherText(f)
                : (f.predicateLabel() == null ? "?" : f.predicateLabel()) + " ← " + otherText(f);
        String range = rangeText(f, closerMark);
        return core + range + " " + confidenceText(f);
    }

    ToolResult timeline(ToolContext ctx, ToolSink sink, JsonNode args) {
        String raw = args.path("entity").asText(args.path("entity_id").asText(""));
        Resolved who;
        try {
            who = resolve(ctx, sink, raw);
        } catch (ReadFailedException e) {
            return ToolResult.of(frames, "Could not look up entities.",
                    ToolStep.of(frames, "timeline", "?", "failed").status("failed").json())
                    .asError();
        } catch (UnresolvedException e) {
            return ToolResult.of(frames, "Unknown entity: " + e.getMessage() + ".",
                    ToolStep.of(frames, "timeline", "?", "unknown entity").status("not_found")
                            .json()).asError();
        }
        Moments m = moments(args);
        FactFilter filter = FactFilter.fromArgs(args);
        int limit = limitOf(args, 60);
        ChatGraphQueries.EntityToolsDetail detail;
        try {
            detail = graphQueries.entityFactsForTools(ctx.kbId, who.id(), null, m.asOf());
        } catch (Exception e) {
            log.warn("Entity timeline lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not read the entity facts.",
                    ToolStep.of(frames, "timeline", "?", "failed").status("failed").json())
                    .asError();
        }
        if (detail == null) {
            return ToolResult.of(frames, "Entity not found.",
                    ToolStep.of(frames, "timeline", "?", "not found").status("not_found")
                            .json());
        }
        GraphNodeRow node = detail.node();
        rememberNode(sink, node);
        // 只要说出了世界时间的事实：没日期的那些起点是摄取时刻，排进时间线只会把
        // 一篇文章的日期当成事件的日期
        Filtered f = filtered(ctx, sink, detail.facts(), filter);
        List<ToolEntityFact> dated = new ArrayList<>();
        int undated = 0;
        for (ToolEntityFact fact : f.kept()) {
            if (fact.validFrom() != null) {
                dated.add(fact);
            }
        }
        for (ToolEntityFact fact : detail.facts()) {
            if (fact.validFrom() == null) {
                undated++;
            }
        }
        dated.sort(java.util.Comparator.comparing(ToolEntityFact::validFrom));
        List<ToolEntityFact> shown = dated.stream().limit(limit).toList();
        List<String> lines = new ArrayList<>();
        if (who.note() != null) {
            lines.add(who.note());
        }
        if (f.note() != null) {
            lines.add(f.note());
        }
        String typeLabel = node.typeLabel() == null ? "untyped" : node.typeLabel();
        if (shown.isEmpty()) {
            lines.add(node.name() + " (" + typeLabel + "): no dated facts"
                    + (filter.narrows() ? " in that window" : "") + "; " + undated
                    + " facts carry no date (entity_facts lists them).");
        } else {
            String first = TimeText.world(shown.get(0).validFrom(),
                    shown.get(0).validFromPrecision());
            String last = TimeText.world(shown.get(shown.size() - 1).validFrom(),
                    shown.get(shown.size() - 1).validFromPrecision());
            StringBuilder head = new StringBuilder(node.name() + " (" + typeLabel + "): "
                    + dated.size() + " dated facts");
            if (shown.size() < dated.size()) {
                head.append(", ").append(shown.size()).append(" shown from ").append(first)
                        .append(" to ").append(last)
                        .append(" (pass since/until to see the rest)");
            }
            if (undated > 0) {
                head.append("; ").append(undated)
                        .append(" undated facts omitted (entity_facts has them)");
            }
            lines.add(head.toString());
            Evidence evidence = new Evidence(this, ctx, withClosers(shown), m.asOf());
            for (ToolEntityFact fact : shown) {
                String stamp = TimeText.world(fact.validFrom(), fact.validFromPrecision());
                String closer = evidence.closerMark(this, sink, fact);
                lines.add(stamp + "  " + factLine(fact, closer)
                        + evidence.mark(this, sink, fact.id()));
            }
        }
        return ToolResult.of(frames, String.join("\n", lines),
                ToolStep.of(frames, "timeline", node.name(),
                        shown.size() + " of " + dated.size() + " dated")
                        .count(shown.size()).total(dated.size()).json());
    }

    // ---- paths_between ---------------------------------------------------------------

    /** 一条边在链上的写法：顺着走 A —pred→ B，逆着走 A ←pred— B。 */
    String edgeText(UUID prev, PathEdge e, String closerMark) {
        String pred = e.predicate() == null ? "?" : e.predicate();
        String successor = successorText(e.closedBy(), e.closedByValue() == null
                ? null : e.closedByValue() instanceof JsonNode n ? n : null);
        String range = TimeText.span(new TimeText.Span(e.validFrom(), e.validFromPrecision(),
                e.validTo(), e.validToPrecision(), e.holdsFrom(), e.holdsTo(), e.endDerived(),
                e.correctedEnds(), successor, closerMark, e.correctionNote()));
        String rangeText = range.isEmpty() ? "" : " (" + range + ")";
        int conf = Math.round(e.confidence() * 100);
        if (e.subjectId().equals(prev)) {
            return e.subjectName() + " —" + pred + "→ " + e.objectName() + rangeText
                    + " [" + conf + "%]";
        }
        return e.objectName() + " ←" + pred + "— " + e.subjectName() + rangeText
                + " [" + conf + "%]";
    }

    /** 一条路径，每一跳后面跟着 mark 给那条事实的东西。 */
    String pathTextMarked(Path p, Evidence evidence, ToolSink sink) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < p.edges().size(); i++) {
            PathEdge e = p.edges().get(i);
            String closer = e.closedById() == null ? ""
                    : evidence.mark(this, sink, e.closedById());
            String own = evidence.mark(this, sink, e.factId());
            parts.add(edgeText(p.nodes().get(i), e, closer) + own);
        }
        return String.join("; ");
    }

    String nameOnPath(Path p, UUID id) {
        for (PathEdge e : p.edges()) {
            if (e.subjectId().equals(id)) {
                return e.subjectName();
            }
            if (e.objectId().equals(id)) {
                return e.objectName();
            }
        }
        return null;
    }

    ToolResult pathsBetween(ToolContext ctx, ToolSink sink, JsonNode args) {
        List<String> notes = new ArrayList<>();
        List<Resolved> ends = new ArrayList<>();
        for (String key : new String[]{"from", "to"}) {
            try {
                Resolved r = resolve(ctx, sink, args.path(key).asText(""));
                if (r.note() != null) {
                    notes.add(r.note());
                }
                ends.add(r);
            } catch (ReadFailedException e) {
                return ToolResult.of(frames, "Could not look up entities.",
                        ToolStep.of(frames, "path", "?", "failed").status("failed").json())
                        .asError();
            } catch (UnresolvedException e) {
                return ToolResult.of(frames, "Unknown `" + key + "`: " + e.getMessage() + ".",
                        ToolStep.of(frames, "path", "?", "unknown entity").status("not_found")
                                .json()).asError();
            }
        }
        Moments m = moments(args);
        int maxHops = Math.max(1, Math.min(3, args.path("max_hops").asInt(3)));
        Limits limits = Limits.of(maxHops);
        List<Path> found;
        try {
            found = paths.pathsBetween(ctx.kbId, ends.get(0).id(), ends.get(1).id(), m.at(),
                    m.asOf(), limits);
        } catch (Exception e) {
            log.warn("Path search failed: {}", e.toString());
            return ToolResult.of(frames, "Could not search paths.",
                    ToolStep.of(frames, "path", "?", "failed").status("failed").json())
                    .asError();
        }
        // 以 id 给出的一端没在库里查过：找到了路径名字写在边上；没找到时先认一遍，
        // 认不出就照实说——「两者之间没有路径」读起来像一个关于图的事实
        for (int i = 0; i < ends.size(); i++) {
            Resolved end = ends.get(i);
            String key = i == 0 ? "from" : "to";
            if (!end.byId()) {
                continue;
            }
            if (!found.isEmpty()) {
                String name = nameOnPath(found.get(0), end.id());
                if (name != null) {
                    ends.set(i, new Resolved(end.id(), name, end.note(), end.byId()));
                }
                continue;
            }
            GraphNodeRow node = m.asOf() == null
                    ? graphSearchNode(ctx.kbId, end.id())
                    : graphSearchNodeAsOf(ctx.kbId, end.id(), m.asOf());
            if (node != null) {
                ends.set(i, new Resolved(end.id(), node.name(), end.note(), end.byId()));
            } else {
                return ToolResult.of(frames, "Unknown `" + key + "`: no entity with id "
                        + end.id() + " in this base.",
                        ToolStep.of(frames, "path", "?", "unknown entity").status("not_found")
                                .json());
            }
        }
        String from = ends.get(0).name();
        String to = ends.get(1).name();
        String label = from + " ↔ " + to;
        String when;
        if (m.at() != null) {
            when = " at " + TimeText.world(m.at(), "day");
        } else if (m.before() != null) {
            when = " as recorded before " + TimeText.instant(m.before());
        } else if (m.asOf() != null) {
            when = " as recorded by " + TimeText.instant(m.asOf());
        } else {
            when = "";
        }
        List<String> lines = new ArrayList<>(notes);
        if (found.isEmpty()) {
            lines.add("No path of up to " + maxHops + " hop" + (maxHops == 1 ? "" : "s")
                    + " between " + from + " and " + to + when + ". They may be unrelated in "
                    + "this base, or connected only through a hub; try neighbors on each.");
            return ToolResult.of(frames, String.join("\n", lines),
                    ToolStep.of(frames, "path", label, "no path").count(0).json());
        }
        int shortest = found.get(0).hops();
        lines.add(found.size() + " path" + (found.size() == 1 ? "" : "s") + " between " + from
                + " and " + to + when + " (up to " + maxHops + " hops, shortest first):");
        // 每一跳是一条事实，各带各的号；时间线关上的那一跳，关它的那一行也要号
        List<UUID> hops = new ArrayList<>();
        for (Path p : found) {
            for (PathEdge e : p.edges()) {
                hops.add(e.factId());
                if (e.closedById() != null) {
                    hops.add(e.closedById());
                }
            }
        }
        Evidence evidence = new Evidence(this, ctx, hops, m.asOf());
        for (int i = 0; i < found.size(); i++) {
            lines.add((i + 1) + ". " + pathTextMarked(found.get(i), evidence, sink));
        }
        if (found.size() >= limits.maxPaths()) {
            lines.add("(Only the first " + limits.maxPaths()
                    + " paths, shortest first, are listed; there may be more.)");
        }
        String detail = found.size() + " path" + (found.size() == 1 ? "" : "s") + ", shortest "
                + shortest + " hop" + (shortest == 1 ? "" : "s");
        return ToolResult.of(frames, String.join("\n", lines),
                ToolStep.of(frames, "path", label, detail).count(found.size())
                        .more(found.size() >= limits.maxPaths()).field("hops", shortest).json());
    }

    GraphNodeRow graphSearchNode(UUID kbId, UUID id) {
        List<GraphNodeRow> rows = jdbc.query(
                "SELECT e.id, e.canonical_name AS name, t.key AS type_key, "
                        + "t.label AS type_label, '#000' AS color, 'circle' AS shape, "
                        + "e.disambiguator, 0::bigint AS degree "
                        + "FROM entities e LEFT JOIN entity_types t ON t.id = e.type_id "
                        + "WHERE e.id = ?::uuid AND e.kb_id = ?::uuid",
                ToolExecutor::mapNode, id.toString(), kbId.toString());
        return rows.isEmpty() ? null : rows.get(0);
    }

    GraphNodeRow graphSearchNodeAsOf(UUID kbId, UUID id, OffsetDateTime asOf) {
        // 回放形态：as_of 时刻可见（合并之前的被并者还在）
        List<GraphNodeRow> rows = jdbc.query(
                "SELECT e.id, e.canonical_name AS name, t.key AS type_key, "
                        + "t.label AS type_label, '#000' AS color, 'circle' AS shape, "
                        + "e.disambiguator, 0::bigint AS degree "
                        + "FROM entities e LEFT JOIN entity_types t ON t.id = e.type_id "
                        + "WHERE e.id = ?::uuid AND e.kb_id = ?::uuid AND e.created_at <= ? "
                        + "AND (e.merged_into IS NULL OR e.merged_at > ?)",
                ToolExecutor::mapNode, id.toString(), kbId.toString(), asOf, asOf);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static GraphNodeRow mapNode(java.sql.ResultSet rs, int i)
            throws java.sql.SQLException {
        return new GraphNodeRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("type_key"), rs.getString("type_label"), rs.getString("color"),
                rs.getString("shape"), rs.getString("disambiguator"), rs.getLong("degree"));
    }

    // ---- rules ---------------------------------------------------------------------

    /**
     * 这个库的判据。把阈值原样给出来——模型要能解释「凭什么算含气井」，而不是猜一个
     * 听起来合理的门槛。
     */
    ToolResult listRules(ToolContext ctx) {
        List<ObjectNode> found;
        try {
            found = rules.list(ctx.kbId);
        } catch (Exception e) {
            return ToolResult.of(frames, "Could not read the rules.",
                    ToolStep.of(frames, "tool", "list_rules", "failed").status("failed").json())
                    .asError();
        }
        if (found.isEmpty()) {
            return ToolResult.of(frames, "This base has no business rules.",
                    ToolStep.of(frames, "tool", "list_rules", "none").count(0).json());
        }
        List<String> lines = new ArrayList<>();
        for (ObjectNode r : found) {
            String conditions = conditionsText(r.path("conditions"));
            String concludes;
            String conclusion = r.path("conclusion").asText("");
            if ("typing".equals(conclusion)) {
                concludes = r.path("conclude_type_label").asText("?");
            } else if ("computed".equals(conclusion)) {
                concludes = r.path("conclude_predicate_label").asText("?") + " = "
                        + describeExpression(ctx, r.path("conclude_expr"));
            } else if ("relation".equals(conclusion)) {
                concludes = r.path("conclude_predicate_label").asText("?") + " from X to the Y "
                        + "reached by " + r.path("join_predicate_label").asText("?");
            } else {
                concludes = r.path("conclude_predicate_label").asText("?") + " = "
                        + r.path("conclude_value").toString();
            }
            lines.add(r.path("name").asText("?") + " ["
                    + (r.path("enabled").asBoolean(false) ? "on" : "off") + "] — applies to "
                    + r.path("subject_label").asText("?") + " where " + conditions + " ⇒ "
                    + concludes + " · marks " + r.path("derived_count").asLong(0) + " now · id "
                    + r.path("id").asText("?"));
        }
        return ToolResult.of(frames, String.join("\n", lines),
                ToolStep.of(frames, "tool", "list_rules", found.size() + " rules")
                        .count(found.size()).json());
    }

    /**
     * 计算结论的算式给模型读的样子（business_rules.rs expression_text 同构）：
     * attr → 关系名（label [key]）、const → 原值、(l op r) → 中缀。解析不了回
     * 「(expression unavailable)」——判据要看得见，但看不懂的算式不硬猜。
     */
    private String describeExpression(ToolContext ctx, JsonNode expr) {
        if (expr == null || !expr.isObject()) {
            return "(expression unavailable)";
        }
        String text = expressionText(ctx, expr);
        return text == null ? "(expression unavailable)" : text;
    }

    private final Map<String, Map<String, String>> relationNameCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private String expressionText(ToolContext ctx, JsonNode raw) {
        JsonNode attr = raw.get("attr");
        if (attr != null) {
            String id = attr.asText(null);
            if (id == null) {
                return null;
            }
            Map<String, String> byKb = relationNameCache.computeIfAbsent(id,
                    k -> new java.util.concurrent.ConcurrentHashMap<>());
            String kbKey = ctx.kbId.toString();
            String cached = byKb.get(kbKey);
            if (cached != null) {
                return cached;
            }
            List<String[]> rows = jdbc.query(
                    "SELECT key, label FROM relation_types WHERE kb_id = ?::uuid AND id = ?::uuid",
                    (rs, i) -> new String[]{rs.getString("key"), rs.getString("label")},
                    kbKey, id);
            if (rows.isEmpty()) {
                return null;
            }
            String key = rows.get(0)[0];
            String label = rows.get(0)[1];
            String name = label == null || label.isEmpty() || label.equals(key) ? key
                    : label + " [" + key + "]";
            byKb.put(kbKey, name);
            return name;
        }
        JsonNode value = raw.get("const");
        if (value != null) {
            return value.isTextual() ? value.asText().trim() : value.toString();
        }
        String op = switch (raw.path("op").asText("")) {
            case "add" -> "+";
            case "sub" -> "-";
            case "mul" -> "*";
            case "div" -> "/";
            default -> null;
        };
        if (op == null) {
            return null;
        }
        JsonNode l = raw.get("l");
        JsonNode r = raw.get("r");
        if (l == null || r == null) {
            return null;
        }
        String left = expressionText(ctx, l);
        String right = expressionText(ctx, r);
        if (left == null || right == null) {
            return null;
        }
        return "(" + left + " " + op + " " + right + ")";
    }

    /** OR-of-ANDs：store 按 group/seq 排的条件，显示成求值器用的同一形状。 */
    static String conditionsText(JsonNode conditions) {
        if (conditions == null || !conditions.isArray()) {
            return "";
        }
        List<String> alternatives = new ArrayList<>();
        List<String> current = new ArrayList<>();
        String lastGroup = null;
        boolean multiGroup = false;
        List<String> groups = new ArrayList<>();
        for (JsonNode c : conditions) {
            String group = c.path("group").asText("0");
            if (lastGroup != null && !group.equals(lastGroup)) {
                multiGroup = true;
            }
            lastGroup = group;
        }
        for (JsonNode c : conditions) {
            String side = "y".equals(c.path("side").asText()) ? "Y." : "";
            String condition = side + c.path("predicate_label").asText("?") + " "
                    + c.path("op").asText("?") + " "
                    + (c.path("operand").isTextual() ? c.path("operand").asText()
                            : c.path("operand").toString());
            String group = c.path("group").asText("0");
            if (!groups.contains(group)) {
                groups.add(group);
                if (!current.isEmpty()) {
                    alternatives.add(String.join(" AND ", current));
                    current = new ArrayList<>();
                }
            }
            current.add(condition);
        }
        if (!current.isEmpty()) {
            alternatives.add(String.join(" AND ", current));
        }
        List<String> out = new ArrayList<>();
        for (String alt : alternatives) {
            out.add(multiGroup && alt.contains(" AND ") ? "(" + alt + ")" : alt);
        }
        return String.join(" OR ", out);
    }

    /** 一条规则此刻标了谁。前提一起给：结论没有前提就跟一条凭空的断言没区别。 */
    ToolResult ruleMatches(ToolContext ctx, JsonNode args) {
        UUID ruleId = parseUuid(args.path("rule_id").asText(""));
        if (ruleId == null) {
            return ToolResult.of(frames,
                    "Invalid rule_id (expected the uuid returned by list_rules).",
                    ToolStep.of(frames, "tool", "rule_matches", "invalid id").invalid("rule_id")
                            .json()).asError();
        }
        long limit = Math.max(1, Math.min(200, args.path("limit").asLong(50)));
        BusinessRulesService.Matches m;
        try {
            m = rules.matches(ctx.kbId, ruleId, limit, 0);
        } catch (Exception e) {
            return ToolResult.of(frames, "Could not read what that rule marks.",
                    ToolStep.of(frames, "tool", "rule_matches", "failed").status("failed")
                            .json()).asError();
        }
        if (m.rows().isEmpty()) {
            return ToolResult.of(frames, "That rule marks nothing right now.",
                    ToolStep.of(frames, "tool", "rule_matches", "0").count(0).json());
        }
        List<String> lines = new ArrayList<>();
        for (JsonNode row : m.rows()) {
            List<String> premises = new ArrayList<>();
            for (JsonNode p : row.path("premises")) {
                if (p.isTextual()) {
                    premises.add(p.asText());
                }
            }
            String from = bound(row, "valid_from", "valid_from_precision", "unknown start");
            String to = bound(row, "valid_to", "valid_to_precision", "unknown end");
            String concluded;
            if (row.path("object_entity").isNull()) {
                concluded = row.path("concluded").isTextual()
                        ? row.path("concluded").asText() : row.path("concluded").toString();
            } else {
                concluded = row.path("relation_predicate").asText(
                        row.path("concluded").asText("?")) + " "
                        + row.path("object_entity").asText("?");
            }
            lines.add(row.path("entity").asText("?") + " ⇒ " + concluded + " (because "
                    + String.join(", ", premises) + ") [validity: " + from + " → " + to + "] ["
                    + row.path("entity_id").asText("?") + "]");
        }
        String text = String.join("\n", lines);
        // 截断要说出来：模型看到 50 条会当成全部
        if (m.total() > m.rows().size()) {
            text += "\n(showing " + m.rows().size() + " of " + m.total() + " matches)";
        }
        return ToolResult.of(frames, text,
                ToolStep.of(frames, "tool", "rule_matches", m.total() + " matches")
                        .field("count", m.total()).json());
    }

    private static String bound(JsonNode row, String key, String precision, String unknown) {
        String raw = row.path(key).asText(null);
        if (raw == null) {
            return unknown;
        }
        try {
            return TimeText.world(OffsetDateTime.parse(raw), row.path(precision).asText(null));
        } catch (Exception e) {
            return unknown;
        }
    }

    // ---- changes ---------------------------------------------------------------------

    ToolResult changes(ToolContext ctx, JsonNode args) {
        LocalDate since = parsePeriod(args.path("since").asText(null));
        LocalDate until = parsePeriod(args.path("until").asText(null));
        if (until != null) {
            until = periodLastDay(until,
                    precisionOf(args.path("until").asText(null)));
        }
        if (since == null) {
            return ToolResult.of(frames, "Invalid or missing `since` (expected YYYY-MM-DD).",
                    ToolStep.of(frames, "changes", "?", "invalid since").invalid("since").json())
                    .asError();
        }
        Window window = changesWindow(since, until, OffsetDateTime.now(ZoneOffset.UTC));
        UUID entity = parseUuid(args.path("entity_id").asText(null));
        List<String> kinds = new ArrayList<>();
        for (JsonNode k : args.path("kinds")) {
            if (k.isTextual()) {
                kinds.add(k.asText());
            }
        }
        List<GraphService.GraphChangeRow> rows;
        try {
            rows = graph.graphChanges(ctx.kbId, window.start(), window.end(), entity,
                    kinds.isEmpty() ? null : kinds, CHANGES_LIMIT);
        } catch (Exception e) {
            log.warn("Graph changes lookup failed: {}", e.toString());
            return ToolResult.of(frames, "Could not read the graph changes.",
                    ToolStep.of(frames, "changes", window.label(), "failed").status("failed")
                            .json()).asError();
        }
        String text;
        if (rows.isEmpty()) {
            text = "No recorded changes in " + window.label() + ".";
        } else {
            List<String> lines = new ArrayList<>();
            for (GraphService.GraphChangeRow r : rows) {
                lines.add(changeLine(r));
            }
            // 取的是最近的四十条，更早的被截掉——不说的话模型会把这四十条当成窗口的全部
            if (rows.size() == CHANGES_LIMIT) {
                lines.add("(Only the " + CHANGES_LIMIT + " most recent changes in this window "
                        + "are listed; it may hold more. Narrow since/until, or pass kinds such "
                        + "as [\"corrected\", \"rejected\"], to see the rest.)");
            }
            text = String.join("\n", lines);
        }
        ToolStep step = ToolStep.of(frames, "changes", window.label(),
                rows.size() == CHANGES_LIMIT ? CHANGES_LIMIT + "+ changes"
                        : rows.size() + " changes").count(rows.size())
                                .more(rows.size() == CHANGES_LIMIT);
        if (since != null) {
            step = step.field("since", since.toString());
        }
        if (until != null) {
            step = step.field("until", until.toString());
        }
        return ToolResult.of(frames, text, step.json());
    }

    /** 认知轴上的一行：两根轴分开写——排成一串日期，模型会把「记下的」读成「发生的」。 */
    String changeLine(GraphService.GraphChangeRow c) {
        String object;
        if (c.objectName() != null) {
            object = c.objectName();
        } else {
            String literal = literalText(c.objectValue());
            object = literal == null ? "?" : literal;
        }
        String range = TimeText.span(new TimeText.Span(c.validFrom(), c.validFromPrecision(),
                c.validTo(), c.validToPrecision(), null, null, false, null, null, null, null));
        String rangeText = range.isEmpty() ? "" : " [valid " + range + "]";
        // 文件名不带 [n]：引证编号是 chunk 的，这里只有 document
        String src;
        if (c.filename() != null && c.quote() != null) {
            src = " — from \"" + c.filename() + "\": \"" + truncate(c.quote(), 160) + "\"";
        } else if (c.filename() != null) {
            src = " — from \"" + c.filename() + "\"";
        } else {
            src = "";
        }
        return TimeText.instant(c.at()) + " " + c.kind() + ": " + c.subjectName() + " "
                + (c.predicateLabel() == null ? "?" : c.predicateLabel()) + " " + object
                + rangeText + src;
    }

    // ---- query_data / remember ---------------------------------------------------------

    ToolResult queryData(ToolContext ctx, JsonNode args) {
        String dsName = args.path("data_source").asText("").trim();
        String sql = args.path("sql").asText("").trim();
        String purpose = args.path("purpose").asText("").trim();
        // 安全边界：只允许本 KB 挂载的源（凭据不出服务端，模型只能按名字点单）
        DataSourceView found = null;
        for (DataSourceView d : ctx.mountedSources) {
            if (d.name().equalsIgnoreCase(dsName)) {
                found = d;
                break;
            }
        }
        String detail = purpose.isEmpty()
                ? sql.codePoints().limit(60)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint,
                                StringBuilder::append)
                        .toString()
                : purpose;
        // 界面上的这一步带着写下的那条 SQL：读者能展开看到数是怎么算出来的
        ToolStep step = ToolStep.of(frames, "query", dsName, detail).field("sql", sql);
        String text;
        if (found == null) {
            List<String> names = new ArrayList<>();
            ctx.mountedSources.forEach(d -> names.add(d.name()));
            text = "Unknown data source '" + dsName + "'. Mounted sources: "
                    + String.join(", ", names);
            step = step.status("not_found");
        } else {
            try {
                QueryResult result = queryRunner.executeQuery(found.id(), sql);
                text = QueryRunner.queryText(result);
                step = step.count(result.rows().size()).more(result.truncated());
            } catch (Exception e) {
                // 错误透传：模型可据此修正 SQL 重试
                text = "Query failed: " + e.getMessage();
                step = step.status("failed");
            }
        }
        return ToolResult.of(frames, text, step.json());
    }

    ToolResult remember(ToolContext ctx, JsonNode args) {
        // NUL 在入口就剥（#665）：拼的回复与卡片详情用同一份文本，TEXT/JSONB 都不收 0x00
        String text = args.path("text").asText("").replace("\0", "").trim();
        String raw = args.path("occurred_at").asText("");
        OffsetDateTime occurredAt;
        String occurredText;
        if (raw != null && !raw.isBlank()) {
            raw = raw.trim();
            OffsetDateTime at = parseWhen(raw);
            if (at != null) {
                occurredAt = at;
                occurredText = TimeText.instant(at);
            } else {
                occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
                occurredText = TimeText.instant(occurredAt);
            }
        } else {
            occurredAt = OffsetDateTime.now(ZoneOffset.UTC);
            occurredText = TimeText.instant(occurredAt);
        }
        if (text.isEmpty()) {
            return ToolResult.of(frames, "remember requires non-empty text.",
                    ToolStep.of(frames, "tool", "remember", "empty").missing("text").json())
                    .asError();
        }
        MemoryService.Episode episode;
        try {
            episode = memory.appendEpisode(ctx.kbId, text, occurredAt);
        } catch (Exception e) {
            return ToolResult.of(frames, "Failed to record: " + e.getMessage(),
                    ToolStep.of(frames, "tool", "remember", "failed").status("failed").json())
                    .asError();
        }
        // 摄取异步走队列；抽出的事实先等人点头（0015）——只能如实说「记下了这句话」
        ObjectNode payload = frames.mapper.createObjectNode();
        payload.put("document_id", episode.documentId().toString());
        if (ctx.actor != null) {
            payload.put("proposed_by", ctx.actor.toString());
        }
        if (ctx.viaToken != null) {
            payload.put("proposed_token", ctx.viaToken.toString());
        }
        try {
            jobs.enqueue("memory_ingest", payload);
        } catch (Exception e) {
            log.warn("memory_ingest enqueue failed: {}", e.toString());
        }
        events.emitDocument(ctx.kbId, episode.documentId());
        String brief = text.codePoints().limit(60)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint,
                        StringBuilder::append)
                .toString();
        return ToolResult.of(frames,
                "Recorded the sentence (effective " + occurredText + "): " + text + "\n"
                        + "Facts extracted from it will be shown to the user for confirmation "
                        + "before entering the graph. Tell the user exactly that: the sentence "
                        + "is recorded, and the extracted facts await their confirmation. Do "
                        + "not claim any fact has been added to the knowledge graph.",
                // 对话里那张确认卡按 chunk_id 取待确认项；回放时也据此重画
                ToolStep.of(frames, "tool", "remember", brief)
                        .field("chunk_id", episode.chunkId().toString()).json());
    }

    // ---- 时间参数 -----------------------------------------------------------------------

    /**
     * changes 的时间窗（tools.rs changes_window 逐语义）：把两个日期变成 (SQL 半开区间,
     * 展示串)。until 进 SQL 前要加一天（说「到 3 月 31 日为止」的人要的是含 31 日），
     * 但展示串里绝不能出现加过一天的那个值——印出去等于告诉模型窗口比它要的宽一天，
     * 模型会照着答（这条真的发生过）。now 从外面传进来：纯函数才测得动。
     */
    static Window changesWindow(LocalDate since, LocalDate until, OffsetDateTime now) {
        OffsetDateTime start = since.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        OffsetDateTime end = until == null ? now
                : until.plusDays(1).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
        String label = since.toString() + " → " + (until == null ? "now" : until.toString());
        return new Window(start, end, label);
    }

    record Window(OffsetDateTime start, OffsetDateTime end, String label) {
    }

    /**
     * 时刻参数：YYYY-MM-DD 或 RFC3339。at 与 as_of 共用一个解析——记录轴上的时刻常常是
     * 带时间的戳，日期粒度装不下它。别的一律 null，不猜。
     */
    static OffsetDateTime parseWhen(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception notRfc3339) {
            // 日期形式：YYYY / YYYY-MM / YYYY-MM-DD，取那一段的开头
            if (s.matches("\\d{4}")) {
                return LocalDate.parse(s + "-01-01").atStartOfDay(ZoneOffset.UTC)
                        .toOffsetDateTime();
            }
            if (s.matches("\\d{4}-\\d{2}")) {
                return LocalDate.parse(s + "-01").atStartOfDay(ZoneOffset.UTC)
                        .toOffsetDateTime();
            }
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toOffsetDateTime();
            }
            return null;
        }
    }

    static LocalDate parsePeriod(String raw) {
        OffsetDateTime t = parseWhen(raw);
        return t == null ? null : t.toLocalDate();
    }

    static String precisionOf(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.matches("\\d{4}")) {
            return "year";
        }
        if (s.matches("\\d{4}-\\d{2}")) {
            return "month";
        }
        return "day";
    }

    /** 一段（年/月/日）的最后一天：until=2023 是到 12 月 31 日为止，含那一整天。 */
    static LocalDate periodLastDay(LocalDate first, String precision) {
        return switch (precision == null ? "day" : precision) {
            case "year" -> first.withMonth(12).withDayOfMonth(31);
            case "month" -> first.plusMonths(1).withDayOfMonth(1).minusDays(1);
            default -> first;
        };
    }

    /** 严格早于 T，按账本的分辨率（微秒）：不晚于 T − 1µs（0019 held_at 一个字不用改）。 */
    static OffsetDateTime justBefore(OffsetDateTime t) {
        return t.minusNanos(1000);
    }

    static UUID parseUuid(String s) {
        if (s == null) {
            return null;
        }
        s = s.trim();
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            if (s.length() == 32) {
                try {
                    return UUID.fromString(s.substring(0, 8) + "-" + s.substring(8, 12) + "-"
                            + s.substring(12, 16) + "-" + s.substring(16, 20) + "-"
                            + s.substring(20));
                } catch (IllegalArgumentException e2) {
                    return null;
                }
            }
            return null;
        }
    }
}
