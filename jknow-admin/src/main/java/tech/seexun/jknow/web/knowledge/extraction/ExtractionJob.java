package tech.seexun.jknow.web.knowledge.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.extract.OpenParser.OpenEntity;
import tech.seexun.jknow.extract.OpenParser.OpenExtraction;
import tech.seexun.jknow.extract.OpenParser.OpenStatement;
import tech.seexun.jknow.extract.OpenPrompts.KnownEntity;
import tech.seexun.jknow.extract.TimeExtract.TimeEntry;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;
import tech.seexun.jknow.llm.ChatClient.Reply;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.store.Confidence;
import tech.seexun.jknow.store.DocumentsService;
import tech.seexun.jknow.store.ExtractionDropsService;
import tech.seexun.jknow.store.GraphWriteService;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.MemoryService;
import tech.seexun.jknow.store.NameVectorsService;
import tech.seexun.jknow.store.NamesService;
import tech.seexun.jknow.store.PendingService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.TimeMentionsService;
import tech.seexun.jknow.store.TypeBindingsService;
import tech.seexun.jknow.store.entity.DocumentRow;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper;
import tech.seexun.jknow.store.mapper.DocumentsWriteMapper.ChunkForExtract;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 开放图谱抽取的写入路径（extraction.rs + extraction_open.rs 逐语义，0044 第 1 刀 / #729）：
 * 文档说了什么，就按它自己的话记下来——提示词里没有本体、没有关系清单、没有文档日期。
 *
 * <p><b>epoch 是抽取任务的所有权凭证</b>：重抽会自增 epoch，任务在每块循环顶部与收尾处
 * 比对，不一致即安静退出——单靠 graph_status 认领无效（旧任务回读时，接手的新任务可能已
 * 把状态写回 extracting）。<b>宁跳勿死</b>贯穿调用半步：一块调失败/解析不出只记
 * extraction_drops + unextracted 跳过，抽成了的那些照常收尾（时间解析、待确认的那一声
 * 通知不跟着没，#1187），错留到最后再报。记忆日志（await_nod）的陈述先进待确认表等人
 * 点头（0015），点头时才落成开放陈述。
 */
@Component
public class ExtractionJob {

    private static final Logger log = LoggerFactory.getLogger(ExtractionJob.class);

    /** 抽完一篇文档补多少条还没有向量的名字（extraction_open.rs:816）。 */
    private static final int NAME_VECTOR_PENDING = 256;

    /** 0040 决定 4 的上限已在 store 侧定义（Confidence.DESCRIBED_CEILING = LOW_CONFIDENCE_BELOW - 0.05）。 */
    public static final float DESCRIBED_CEILING = Confidence.DESCRIBED_CEILING;

    /** 谁说的（0026）：人 + 经 MCP 时那个 agent 的令牌；批量摄入的文档传默认值。 */
    public record Proposer(UUID userId, UUID tokenId) {

        public static Proposer none() {
            return new Proposer(null, null);
        }
    }

    private final DocumentsService documents;
    private final DocumentsWriteMapper writes;
    private final KbMapper kbs;
    private final SettingsService settingsService;
    private final MemoryService memory;
    private final ResolutionService resolution;
    private final NamesService names;
    private final GraphWriteService graph;
    private final PendingService pending;
    private final TimeMentionsService timeMentions;
    private final ExtractionDropsService drops;
    private final NameVectorsService nameVectors;
    private final TypeBindingsService typeBindings;
    private final JobsService jobs;
    private final LlmSupport llm;
    private final AppEventBus events;

    /** #588 的旋钮：提示词里带不带前面分块认下的实体（落库那半步不受它管）。 */
    private final boolean extractKnownInPrompt;

    public ExtractionJob(DocumentsService documents, DocumentsWriteMapper writes, KbMapper kbs,
                         SettingsService settingsService, MemoryService memory,
                         ResolutionService resolution, NamesService names,
                         GraphWriteService graph, PendingService pending,
                         TimeMentionsService timeMentions, ExtractionDropsService drops,
                         NameVectorsService nameVectors, TypeBindingsService typeBindings,
                         JobsService jobs, LlmSupport llm, AppEventBus events,
                         @Value("${jknow.extract-known-in-prompt:true}") boolean extractKnownInPrompt) {
        this.documents = documents;
        this.writes = writes;
        this.kbs = kbs;
        this.settingsService = settingsService;
        this.memory = memory;
        this.resolution = resolution;
        this.names = names;
        this.graph = graph;
        this.pending = pending;
        this.timeMentions = timeMentions;
        this.drops = drops;
        this.nameVectors = nameVectors;
        this.typeBindings = typeBindings;
        this.jobs = jobs;
        this.llm = llm;
        this.events = events;
        this.extractKnownInPrompt = extractKnownInPrompt;
    }

    // ====================================================================
    // 纯函数（extraction.rs / extraction_open.rs 的可测半边）
    // ====================================================================

    /**
     * extraction.rs:100-106 origin_ceiling：这块文字的来源给事实置信度设的上限。
     * 开放陈述无模型自报置信度（说的是「文档这么说了」，恒 1.0 起步）。
     */
    public static float originCeiling(String origin, float confidence) {
        return "described".equals(origin) ? Math.min(confidence, DESCRIBED_CEILING) : confidence;
    }

    /** extraction.rs:109-118 span_in_quote：片段在不在引文里——大小写、空白都不论。 */
    public static boolean spanInQuote(String span, String quote) {
        String s = squashWhitespace(span == null ? "" : span).toLowerCase(java.util.Locale.ROOT);
        String q = squashWhitespace(quote == null ? "" : quote).toLowerCase(java.util.Locale.ROOT);
        return !s.isEmpty() && q.contains(s);
    }

    /**
     * Rust {@code split_whitespace().collect().join(" ")}：空白 = char::is_whitespace
     * （Unicode White_Space 属性，含 NBSP/NNBSP；Java 的 isWhitespace 与 regex
     * {@code (?U)\\s} 都不含 NBSP，(?U)\\s 反倒多收 U+001C-001F——两头都偏）。
     */
    private static String squashWhitespace(String s) {
        StringBuilder out = new StringBuilder();
        boolean inSpace = true; // 开头的空白不吃进来
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isWs(cp)) {
                if (!inSpace) {
                    out.append(' ');
                    inSpace = true;
                }
            } else {
                out.appendCodePoint(cp);
                inSpace = false;
            }
        }
        if (out.length() > 0 && out.charAt(out.length() - 1) == ' ') {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    /**
     * char::is_whitespace（Unicode White_Space）：0009-000D、0020、0085、00A0、1680、
     * 2000-200A、2028、2029、202F、205F、3000。{@link Character#isWhitespace} 排除
     * 00A0/2007/202F 又多收 001C-001F——与 Rust 不是同一张表（审查 M-2）。
     */
    static boolean isWs(int cp) {
        switch (cp) {
            case 0x09: case 0x0A: case 0x0B: case 0x0C: case 0x0D:
            case 0x20: case 0x85: case 0xA0: case 0x1680:
            case 0x2028: case 0x2029: case 0x202F: case 0x205F: case 0x3000:
                return true;
            default:
                return cp >= 0x2000 && cp <= 0x200A;
        }
    }

    /** str::trim（两端去 White_Space——String.strip 不含 NBSP）。 */
    static String rustTrim(String s) {
        int b = 0;
        int e = s.length();
        while (b < e && isWs(s.codePointAt(b))) {
            b += Character.charCount(s.codePointAt(b));
        }
        while (e > b && isWs(s.codePointBefore(e))) {
            e -= Character.charCount(s.codePointBefore(e));
        }
        return s.substring(b, e);
    }

    /** str::trim_start（行首空白含 NBSP；M-1 的标题判定用它先整行去空白）。 */
    static String rustTrimStart(String s) {
        int b = 0;
        while (b < s.length() && isWs(s.codePointAt(b))) {
            b += Character.charCount(s.codePointAt(b));
        }
        return s.substring(b);
    }

    /**
     * extraction.rs:149-172 incomplete_reason：这一轮抽完了没有——没抽完就给出要写进
     * graph_error 的那句话。判据是「全抽完」而不是比例；分母是<b>本轮</b>取到的分块数
     * （重试只取 extracted_at IS NULL 的，第二轮分母天然更小）；失败块原因举 3 例 + N more。
     */
    public static String incompleteReason(List<Map.Entry<Integer, String>> unextracted,
                                          int attempted) {
        if (unextracted.isEmpty()) {
            return null;
        }
        List<String> sample = new ArrayList<>();
        for (int i = 0; i < Math.min(3, unextracted.size()); i++) {
            sample.add("#" + unextracted.get(i).getKey() + " " + unextracted.get(i).getValue());
        }
        int more = unextracted.size() - sample.size();
        String tail = more > 0 ? "; " + more + " more" : "";
        return unextracted.size() + " of " + attempted + " "
                + (attempted == 1 ? "chunk" : "chunks")
                + " could not be extracted in this attempt: "
                + String.join("; ", sample) + tail;
    }

    /**
     * extraction_open.rs:694-700 dated_at：文档日期只在它来自内容或来源系统时才算证据日期
     * （与 temporal::DATED_AT 同口径）——处理文档的时刻不是文档说的日期（#714）。
     */
    public static OffsetDateTime datedAt(DocumentRow doc) {
        return "content".equals(doc.docTimeSource()) || "source".equals(doc.docTimeSource())
                ? doc.docTime() : null;
    }

    /**
     * extraction_open.rs:710-722 locate：一段字在块里的<b>字符</b>偏移（起、止）。字符不是
     * 字节、也不是 UTF-16 code unit：界面和 SQL 的 substr 都按<b>码点</b>数，增补平面字符
     * （emoji）在 Java String 里占两个 unit——按 UTF-16 算出的偏移到 PG substr 上错位
     * （审查 M-2）。<b>一段空白对一段空白</b>，别的逐字比——
     * PDF 文字层在行尾换行、模型抄回来是空格；换行还是空格不是原文说了什么，偏移照原文
     * 算。找不到返回 null——偏移只能由服务端从原文算，模型报的数字不算数。
     */
    public static int[] locate(String hay, String needle) {
        Squashed n = squashed(rustTrim(needle));
        if (n.text.isEmpty()) {
            return null;
        }
        Squashed h = squashed(hay);
        int found = indexOfCp(h.text, n.text);
        if (found < 0) {
            return null;
        }
        int lenCp = n.text.codePointCount(0, n.text.length());
        int start = h.at[found];
        int end = h.at[found + lenCp - 1] + 1;
        return new int[]{start, end};
    }

    /** 折叠文本按码点序搜索（Rust 在 Vec<char> 上开窗口比；UTF-16 indexOf 在增补平面上会错位）。 */
    private static int indexOfCp(String hay, String needle) {
        int hLen = hay.codePointCount(0, hay.length());
        int nLen = needle.codePointCount(0, needle.length());
        for (int at = 0; at + nLen <= hLen; at++) {
            int i = hay.offsetByCodePoints(0, at);
            int j = 0;
            while (j < needle.length()) {
                int hc = hay.codePointAt(i);
                int nc = needle.codePointAt(j);
                if (hc != nc) {
                    break;
                }
                i += Character.charCount(hc);
                j += Character.charCount(nc);
            }
            if (j >= needle.length()) {
                return at;
            }
        }
        return -1;
    }

    /**
     * extraction_open.rs:724-742 squashed：连着的空白压成一个空格 + 压过之后每个<b>码点</b>
     * 在原文里的码点位置（at[j] 是原文的码点序号；locate 返回的起止因此是码点偏移，
     * 与 PG 的 substr 同一语义）。
     */
    private static Squashed squashed(String text) {
        StringBuilder out = new StringBuilder();
        List<Integer> at = new ArrayList<>();
        boolean inSpace = false;
        int cpIndex = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isWs(cp)) {
                if (!inSpace) {
                    out.append(' ');
                    at.add(cpIndex);
                }
                inSpace = true;
            } else {
                out.appendCodePoint(cp);
                at.add(cpIndex);
                inSpace = false;
            }
            cpIndex++;
        }
        int[] positions = new int[at.size()];
        for (int k = 0; k < positions.length; k++) {
            positions[k] = at.get(k);
        }
        return new Squashed(out.toString(), positions);
    }

    private record Squashed(String text, int[] at) {
    }

    /**
     * extraction_open.rs:751-759 shows_value（#729）：这条陈述的值有没有真的写在它的引文里。
     * 千分位逗号、空白和货币符号两边都不算数；别的按原样比。<b>故意放得松</b>：这是一条
     * 丢弃规则，放过一条可疑的，好过丢掉一条对的。
     */
    public static boolean showsValue(String quote, String value) {
        String v = strip(value);
        return v.isEmpty() || strip(quote).contains(v);
    }

    private static String strip(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isWs(cp) || cp == ',' || cp == '$' || cp == '￥' || cp == '€' || cp == '£') {
                continue;
            }
            out.appendCodePoint(cp);
        }
        return out.toString();
    }

    /**
     * extraction_open.rs:761-809 locate_time：时间词在块里的字符起点。<b>必须在这条陈述
     * 自己的那句引文里</b>（模型会把一个时间词挂到好几条陈述上）；引文里有、引文本身没在
     * 块里定位到的，起点退回整块里的第一处；表格行取表头（期数写在表头行里）；标题上的
     * 时间（0064：一节标题只说了一个时间，它下面没写时间的陈述拿它当 when）。
     */
    public static Integer locateTime(String chunk, Quote quote, String words) {
        if (quote == null) {
            return null;
        }
        int[] inner = locate(quote.text(), words);
        if (inner != null) {
            if (quote.span() != null) {
                // 在原文的这一句里找：引文和原文的空白未必一样长（换行加缩进对一个空格），
                // 按引文里的位置加上去会偏。span 是码点偏移，切片按码点取
                int start = Math.max(0, quote.span()[0]);
                int end = Math.max(0, quote.span()[1]);
                String sentence = cpSlice(chunk, start, end);
                int[] at = locate(sentence, words);
                return start + (at != null ? at[0] : inner[0]);
            }
            int[] whole = locate(chunk, words);
            return whole != null ? whole[0] : null;
        }
        // 表格的一行：期数写在表头行里，不在这一行里；这一块就是这张表（分块器让表头跟着
        // 每一块走），所以在整块里找。模型常把行首的 "| " 抄掉，所以看块里那一行
        boolean onTableRow;
        if (quote.span() != null) {
            int start16 = chunk.offsetByCodePoints(0, Math.max(0, quote.span()[0]));
            int lineStart = lastNewlineBefore(chunk, start16) + 1;
            onTableRow = rustTrimStart(chunk.substring(lineStart)).startsWith("|");
        } else {
            onTableRow = rustTrimStart(quote.text()).startsWith("|")
                    || quote.text().contains(" | ");
        }
        if (onTableRow) {
            int[] at = locate(chunk, words);
            return at != null ? at[0] : null;
        }
        // 标题上的时间：判据是结构的——Rust 先 trim_start() 整行再数 '#'（审查 M-1），
        // 行首带空白（含 NBSP）的标题行照样认
        int[] found = locate(chunk, words);
        if (found == null) {
            return null;
        }
        int start = found[0];
        int start16 = chunk.offsetByCodePoints(0, start);
        int lineStart = lastNewlineBefore(chunk, start16) + 1;
        String line = chunk.substring(lineStart);
        int nl = line.indexOf('\n');
        if (nl >= 0) {
            line = line.substring(0, nl);
        }
        String trimmed = rustTrimStart(line);
        int level = 0;
        while (level < trimmed.length() && trimmed.charAt(level) == '#') {
            level++;
        }
        if (level >= 1 && level <= 6) {
            return start;
        }
        return null;
    }

    /** UTF-16 索引之前最后一个 \n 的位置；没有返回 -1（行从 0 起）。 */
    private static int lastNewlineBefore(String s, int from16) {
        return s.lastIndexOf('\n', Math.min(from16, s.length() - 1));
    }

    /** 按码点切片 [fromCp, toCp)。 */
    private static String cpSlice(String s, int fromCp, int toCp) {
        int from = s.offsetByCodePoints(0, fromCp);
        int to = s.offsetByCodePoints(0, Math.min(toCp, s.codePointCount(0, s.length())));
        return s.substring(from, Math.min(to, s.length()));
    }

    /** 一条引文 + 它在块里的定位（定位不到 span=null，照记证据但记一笔 drop）。 */
    public record Quote(String text, int[] span) {
    }

    /** extraction_open.rs:884-889 name_key：名字的查找键——空白折叠（White_Space）、小写。 */
    public static String nameKey(String name) {
        return squashWhitespace(name).toLowerCase(java.util.Locale.ROOT);
    }

    // ====================================================================
    // 入口（extraction.rs:177-198 extract_document → run → run_open）
    // ====================================================================

    /** job 入口：payload {document_id, proposed_by?, proposed_token?}。 */
    public void handle(Job job) throws Exception {
        UUID documentId = UUID.fromString(job.payload().get("document_id").asText());
        Proposer proposer = new Proposer(
                job.payload().hasNonNull("proposed_by")
                        ? UUID.fromString(job.payload().get("proposed_by").asText()) : null,
                job.payload().hasNonNull("proposed_token")
                        ? UUID.fromString(job.payload().get("proposed_token").asText()) : null);
        extractDocument(documentId, proposer);
    }

    /**
     * extraction.rs:177-198 extract_document：失败时 set_graph_failed（原因随状态落库——
     * 只进日志的错误等于没有错误）+ emit_document。
     */
    public void extractDocument(UUID documentId, Proposer proposer) throws Exception {
        try {
            run(documentId, proposer);
        } catch (Exception e) {
            writes.setGraphFailed(documentId, String.valueOf(e.getMessage() != null ? e.getMessage()
                    : e));
            DocumentRow doc = documents.get(documentId);
            if (doc != null) {
                events.emitDocument(doc.kbId(), documentId);
            }
            throw e;
        }
    }

    /** extraction.rs:337-381 run：入口三查 + 所有权凭证 + 交给 runOpen。 */
    void run(UUID documentId, Proposer proposer) throws Exception {
        DocumentRow doc = documents.get(documentId);
        if (doc == null) {
            throw new IllegalStateException("document not found: " + documentId);
        }
        // 排队之后被删了（#268）：墓碑不抽——抽出来的事实会活在一个已删除的出处上
        if (doc.deletedAt() != null) {
            log.info("document={} 跳过已删除文档", documentId);
            return;
        }
        KbRow kb = kbs.selectById(doc.kbId());
        // 推送来的陈述（0054）：块就是契约，抽取按契约解析、不问模型，没配对话模型也照抽
        boolean pushed = "statements".equals(sourceKind(doc.sourceId()));
        // settings 有就传：推送路径不问对话模型，但名字向量的嵌入模型（#877）仍从它来
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = null;
        if (!pushed) {
            if (settings == null || !settings.chatReady()) {
                throw new IllegalStateException("Chat model not configured; cannot extract");
            }
            client = LlmSupport.chatClient(settings);
            if (client == null) {
                throw new IllegalStateException("Chat model not configured; cannot extract");
            }
        }
        // 所有权凭证：重抽会自增 epoch，任务据此察觉自己已被接管
        int myEpoch = writes.extractEpoch(documentId);
        writes.setGraphStatus(documentId, "extracting");
        events.emitDocument(doc.kbId(), documentId);
        boolean awaitNod = memory.isMemoryDocument(documentId);
        runOpen(doc, kb, settings, client, myEpoch, proposer, awaitNod, pushed);
    }

    private String sourceKind(UUID sourceId) {
        if (sourceId == null) {
            return null;
        }
        DocumentsWriteMapper.SourceRef ref = writes.selectSourceRef(sourceId);
        return ref == null ? null : ref.kind();
    }

    // ====================================================================
    // run_open 状态机（extraction_open.rs:936-1197）
    // ====================================================================

    /**
     * 跨块状态（run_open 的局部变量集——apply 半步要按块序读它们，#588 cut 1）。
     * 包可见：extraction.rs 内联测试直接驱动 resolve_handle 的运行时（A-B-C 句柄）。
     */
    static final class RunState {
        final List<Object[]> docEntities = new ArrayList<>(); // (id, kind, name)
        final Map<String, UUID> knownByName = new HashMap<>();
        final Map<String, UUID> described = new HashMap<>();
        final Map<String, List<UUID>> handledByName = new HashMap<>();
        final Map<String, UUID> ambiguousBareCache = new HashMap<>();
        final Set<String> touchedNames = new HashSet<>();
        final List<Map.Entry<Integer, String>> unextracted = new ArrayList<>();
        boolean needsAdjudication;
        boolean humanReviewsFound;
        int pendingCount;
        int statementCount;
    }

    void runOpen(DocumentRow doc, KbRow kb, LlmSettingsRow settings, ChatClient client,
                 int myEpoch, Proposer proposer, boolean awaitNod, boolean pushed)
            throws Exception {
        UUID documentId = doc.id();
        UUID kbId = kb.id();
        // 本轮从头讲一遍这篇文档的故事，旧信号先清掉（重抽自动作数）
        drops.clearForDocument(documentId);
        OffsetDateTime attestedAt = datedAt(doc);
        List<ChunkForExtract> chunks = writes.selectChunksForExtraction(documentId);
        // 记忆日志一句一块，前一句不是后一句的开头，不附（与老路同一条规矩）
        DocumentsWriteMapper.OpeningChunk openingChunk =
                awaitNod ? null : writes.selectOpeningChunk(documentId);

        RunState st = new RunState();
        // 类别词已经绑到类的（0044 对齐第一片）：提及带着类去消解，同名不同类才分得开
        Map<String, UUID> boundTypes = typeBindings.boundMap(kbId);
        // 名字向量的嵌入客户端。没配嵌入模型就是 null：召回退回字面相等，抽取照常
        EmbedClient embed = LlmSupport.embedClient(settings);
        // 文档自己说到的日期（0064 决定 1）：每一块报上来的，连它所在的标题路径
        List<TimeEntry> timeEntries = new ArrayList<>();
        List<UUID> extractedChunks = new ArrayList<>();

        for (ChunkForExtract chunk : chunks) {
            // 被接管则安静退场（重抽自增 epoch）：检查放在调用模型之前
            if (writes.extractEpoch(documentId) != myEpoch) {
                log.info("document={} 抽取任务已被新一轮接管，退出", documentId);
                return;
            }
            float[] ctx = vectorOf(chunk.embedding());
            List<KnownEntity> known = new ArrayList<>();
            for (int i = 0; i < st.docEntities.size(); i++) {
                Object[] e = st.docEntities.get(i);
                known.add(new KnownEntity("k" + (i + 1), (String) e[1], (String) e[2]));
            }
            String opening = null;
            if (openingChunk != null && !openingChunk.id().equals(chunk.id())) {
                opening = openingChunk.text();
            }
            // 提示词里带不带前面分块认下的实体，是个为了量而设的旋钮（#588）；落库那半步
            // 不受它管
            List<KnownEntity> shown = extractKnownInPrompt ? known : List.of();
            ChunkParsed parsed = callAndParseOneChunk(settings, client, embed, kbId, documentId,
                    doc.filename(), chunk, opening, shown, pushed, st);
            if (parsed == null) {
                continue;
            }
            extractedChunks.add(chunk.id());
            for (TimeEntry entry : parsed.extraction.time) {
                // 字要原样在这一块里（与名字、引文同一条规矩）；核不到的不算
                int[] at = locate(chunk.text(), entry.words());
                if (at == null) {
                    drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.TIME_NOT_IN_QUOTE,
                            "a date the passage was said to state is not in the passage",
                            entry.words());
                    continue;
                }
                List<String> scope = tech.seexun.jknow.extract.TimeExtract.headingsAt(chunk.text(),
                        at[0]);
                boolean dup = false;
                for (TimeEntry e : timeEntries) {
                    if (e.kind().equals(entry.kind()) && e.words().equals(entry.words())
                            && e.scope().equals(scope)) {
                        dup = true;
                        break;
                    }
                }
                if (dup) {
                    continue;
                }
                timeEntries.add(new TimeEntry(entry.kind(), entry.name(), entry.words(),
                        entry.from(), entry.to(), scope, chunk.id().toString(), at[0]));
            }
            applyOpenExtraction(kbId, documentId, chunk, ctx, parsed.extraction, parsed.nameVecs,
                    attestedAt, boundTypes, pushed, awaitNod, proposer, st);
        }

        // 消歧后缀在实体创建时算会早于其事实写入——收尾时对本文档涉及的名字统一刷新
        for (String name : st.touchedNames) {
            resolution.refreshDisambiguators(kbId, name);
        }
        if (writes.extractEpoch(documentId) != myEpoch) {
            log.info("document={} 抽取任务已被新一轮接管，收尾时退出", documentId);
            return;
        }
        // 有分块没抽成就不许标 done。但抽成了的那些照常收尾：从前在这里直接返回，后面的
        // 时间解析、待确认的那一声通知都跟着没了（#1187）。错留到最后再报
        String incomplete = incompleteReason(st.unextracted, chunks.size());
        if (incomplete == null) {
            writes.setGraphStatus(documentId, "done");
            events.emitDocument(kbId, documentId);
        }
        events.emitGraph(kbId);
        // 文档的时间语境（0064）：这一轮各块报上来的日期并进文档上存着的那份
        mergeTimeContext(doc, documentId, timeEntries, extractedChunks);
        // 时间词的解析是它后面的任务（0045）；新出现的类别词绑到类（0044 对齐第一片）
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("document_id", documentId.toString());
        jobs.enqueueUnlessQueued("resolve_time", payload);
        ObjectNode alignPayload = JsonNodeFactory.instance.objectNode();
        alignPayload.put("kb_id", kbId.toString());
        jobs.enqueueUnlessQueued("align_types", alignPayload);
        // 队列里多了东西才叫醒人：Review 的计数与对话里那张确认卡都靠这一声
        if (st.pendingCount > 0) {
            log.info("document={} 记忆抽出的陈述进了待确认队列 {} 条", documentId, st.pendingCount);
            events.emitPending(kbId);
            events.emitReview(kbId);
        }
        // 名字向量：这篇新写的名字事实，向量补上。算不出来只记日志——文档已经抽完了，
        // 不能因为召回的辅助数据没算而把它标成 failed
        if (settings != null && embed != null) {
            try {
                EmbedClient embedClient = embed;
                int n = nameVectors.embedPendingNames(kbId, NAME_VECTOR_PENDING,
                        embedClient::embed);
                if (n > 0) {
                    log.info("document={} 名字向量已补 {} 条", documentId, n);
                }
            } catch (Exception e) {
                log.warn("document={} 名字向量没补上，下一篇再补: {}", documentId, e.getMessage());
            }
        }
        // 灰区对进了审核队列 → 治理 / 裁决任务，同库排着的不重复。不排类型消解、不排
        // 自动扩本体：开放图谱里没有类也没有关系可扩，那是对齐的事。govern 真身已落
        // （M4 任务 4），这里排下即被消费
        if (kb.governance()) {
            if (st.needsAdjudication || st.humanReviewsFound) {
                ObjectNode p = JsonNodeFactory.instance.objectNode();
                p.put("kb_id", kbId.toString());
                jobs.enqueueUnlessQueued("govern", p);
            }
        } else if (st.needsAdjudication) {
            ObjectNode p = JsonNodeFactory.instance.objectNode();
            p.put("kb_id", kbId.toString());
            jobs.enqueueUnlessQueued("adjudicate_entities", p);
        }
        if (st.needsAdjudication || st.humanReviewsFound) {
            events.emitReview(kbId);
        }
        if (incomplete != null) {
            throw new IllegalStateException(incomplete);
        }
        log.info("document={} 开放图谱抽取完成，陈述 {} 条", documentId, st.statementCount);
    }

    private static float[] vectorOf(JsonNode embedding) {
        if (embedding == null || !embedding.isArray()) {
            return null;
        }
        float[] out = new float[embedding.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (float) embedding.get(i).asDouble();
        }
        return out;
    }

    /**
     * extraction_open.rs:1110-1137 time_context 合并：没抽到的块（增量抽取时认领的未变
     * 段落）原来报的留着；这一轮抽过的块以这一轮的为准。整篇起算点 = 范围最浅的那个
     * now（0064 决定 2：只是相对时间词的基准，不盖在任何一条事实上）。一条都没有就不写。
     */
    private void mergeTimeContext(DocumentRow doc, UUID documentId, List<TimeEntry> timeEntries,
                                  List<UUID> extractedChunks) throws Exception {
        // 保留文档上存着的那份的其余字段（periods/fiscal_year_end——DocumentDating 的
        // serde default 面），只重写 entries 与整篇起算点
        JsonNode previous = doc.timeContext();
        ObjectNode context = previous != null && previous.isObject()
                ? (ObjectNode) previous.deepCopy() : JsonNodeFactory.instance.objectNode();
        context.remove("entries");
        ArrayNode entries = context.putArray("entries");
        JsonNode prevEntries = previous != null ? previous.get("entries") : null;
        Set<String> redone = new HashSet<>();
        for (UUID id : extractedChunks) {
            redone.add(id.toString());
        }
        if (prevEntries != null && prevEntries.isArray()) {
            for (JsonNode e : prevEntries) {
                String chunkId = e.path("chunk").asText(null);
                if (chunkId == null || redone.contains(chunkId)) {
                    continue;
                }
                entries.add(e.deepCopy());
            }
        }
        for (TimeEntry e : timeEntries) {
            entries.add(toEntryJson(e));
        }
        if (entries.isEmpty()) {
            return;
        }
        // 整篇的起算点：范围最浅的那个 now，同一层取先说的（0064 决定 2）。Rust 侧
        // `context.date = whole.map(...)` 是无条件赋值——没有 now 条目时把旧值清成 null，
        // 本侧同样总是写这两个键（serde 对 None 写 null，不是省略）
        TimeEntry best = null;
        for (JsonNode e : entries) {
            if (!"now".equals(e.path("kind").asText())) {
                continue;
            }
            TimeEntry cand = fromEntryJson(e);
            if (cand == null) {
                continue;
            }
            if (best == null || cand.scope().size() < best.scope().size()) {
                best = cand;
            }
        }
        context.set("date", best != null ? toDateParts(best.from()) : NullNode);
        context.set("date_words", best != null
                ? JsonNodeFactory.instance.textNode(best.words()) : NullNode);
        // DocumentDating serde(default) 的另外三个键也按 Rust 的写法常驻：periods/
        // fiscal_year_end 缺省时是 []/null，skipped 是 usize（这一路从不写它，恒 0）
        if (!context.has("periods")) {
            context.putArray("periods");
        }
        if (!context.has("fiscal_year_end")) {
            context.set("fiscal_year_end", NullNode);
        }
        context.put("skipped", 0);
        writes.setTimeContext(documentId, context);
    }

    private ObjectNode toEntryJson(TimeEntry e) {
        // 字段形状按 TimeEntry 的 serde：chunk/char_start 的 Option 不带 skip，None 写
        // null；scope 的 serde(default) 写 []
        ObjectNode n = JSON_MAPPER.createObjectNode();
        n.put("kind", e.kind());
        n.put("name", e.name());
        n.put("words", e.words());
        n.set("from", toDateParts(e.from()));
        n.set("to", e.to() == null ? NullNode : toDateParts(e.to()));
        ArrayNode scope = n.putArray("scope");
        e.scope().forEach(scope::add);
        n.set("chunk", e.chunk() != null
                ? JsonNodeFactory.instance.textNode(e.chunk()) : NullNode);
        n.set("char_start", e.charStart() != null
                ? JsonNodeFactory.instance.numberNode(e.charStart()) : NullNode);
        return n;
    }

    private static final JsonNode NullNode = com.fasterxml.jackson.databind.node.NullNode
            .getInstance();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private ObjectNode toDateParts(tech.seexun.jknow.extract.TimeExtract.DateParts p) {
        // DateParts 的 serde 只有 quarter 带 skip_serializing_if；month/day/hour/minute/
        // second 的 Option 写 null——共享库里 Rust 侧写的形状一字不差
        ObjectNode n = JSON_MAPPER.createObjectNode();
        n.put("year", p.year());
        if (p.quarter() != null) {
            n.put("quarter", p.quarter());
        }
        n.put("month", p.month() == null ? NullNode
                : JsonNodeFactory.instance.numberNode(p.month()));
        n.put("day", p.day() == null ? NullNode : JsonNodeFactory.instance.numberNode(p.day()));
        n.put("hour", p.hour() == null ? NullNode
                : JsonNodeFactory.instance.numberNode(p.hour()));
        n.put("minute", p.minute() == null ? NullNode
                : JsonNodeFactory.instance.numberNode(p.minute()));
        n.put("second", p.second() == null ? NullNode
                : JsonNodeFactory.instance.numberNode(p.second()));
        return n;
    }

    private TimeEntry fromEntryJson(JsonNode e) {
        try {
            return new TimeEntry(e.path("kind").asText(), e.path("name").asText(""),
                    e.path("words").asText(), fromDateParts(e.path("from")),
                    e.has("to") && !e.get("to").isNull() ? fromDateParts(e.get("to")) : null,
                    toStrings(e.path("scope")), e.path("chunk").asText(null),
                    e.hasNonNull("char_start") ? e.get("char_start").asInt() : null);
        } catch (Exception ex) {
            return null;
        }
    }

    private tech.seexun.jknow.extract.TimeExtract.DateParts fromDateParts(JsonNode n) {
        return new tech.seexun.jknow.extract.TimeExtract.DateParts(n.path("year").asInt(),
                n.hasNonNull("quarter") ? n.get("quarter").asInt() : null,
                n.hasNonNull("month") ? n.get("month").asInt() : null,
                n.hasNonNull("day") ? n.get("day").asInt() : null,
                n.hasNonNull("hour") ? n.get("hour").asInt() : null,
                n.hasNonNull("minute") ? n.get("minute").asInt() : null,
                n.hasNonNull("second") ? n.get("second").asInt() : null);
    }

    private List<String> toStrings(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            arr.forEach(x -> out.add(x.asText()));
        }
        return out;
    }

    // ====================================================================
    // 前半步：调模型 + 解析 + 名字向量（extraction_open.rs:59-198）
    // ====================================================================

    /** 一块调 + 解析完后的中间产物（#588 cut 1）。null = 这一块跳过了。 */
    record ChunkParsed(OpenExtraction extraction, Map<String, float[]> nameVecs) {
    }

    ChunkParsed callAndParseOneChunk(LlmSettingsRow settings, ChatClient client,
                                     EmbedClient embed, UUID kbId, UUID documentId,
                                     String filename, ChunkForExtract chunk, String opening,
                                     List<KnownEntity> known, boolean pushed, RunState st)
            throws InterruptedException {
        // 推送来的陈述：块就是契约，解析它而不是问模型（0054）。下面从解析起一步不变
        String replyText;
        boolean cutByCeiling;
        if (pushed) {
            replyText = chunk.text();
            cutByCeiling = false;
        } else {
            if (settings == null || client == null) {
                throw new IllegalStateException("Chat model not configured; cannot extract");
            }
            List<ChatMessage> messages = tech.seexun.jknow.extract.OpenPrompts.buildOpenMessages(
                    filename, known, opening, chunk.text());
            // 温度 0：照抄原文的活不该靠采样——端点缺省 1.0 时同一块两次回复密度差三倍
            Reply reply;
            try {
                reply = llm.chatRetryingRateLimits(settings, client, messages, 0.0d);
            } catch (Exception e) {
                if (ChatClient.outOfCredit(e) != null) {
                    // 空余额终止整篇（#1095）：已落块保留，任务失败一次
                    throw e instanceof RuntimeException rt ? rt : new RuntimeException(e);
                }
                log.warn("document={} seq={} 开放抽取调用失败，跳过该分块: {}", documentId,
                        chunk.seq(), e.getMessage());
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.CHUNK_UNEXTRACTED,
                        "request failed; this chunk did not enter the graph",
                        "#" + chunk.seq() + ": " + e.getMessage());
                st.unextracted.add(Map.entry(chunk.seq(), "request failed: " + e.getMessage()));
                return null;
            }
            // 端点说它是撞上 token 上限停的（#760）：解析器只看得见 JSON 少了尾巴，
            // 看不见少的原因，这句话得从回复里带过来
            cutByCeiling = reply.hitTokenCeiling();
            replyText = reply.text();
        }
        OpenExtraction extraction;
        try {
            extraction = tech.seexun.jknow.extract.OpenParser.parseOpenResponse(replyText);
        } catch (Exception e) {
            log.warn("document={} seq={} 开放抽取回复解析失败，跳过该分块: {}", documentId,
                    chunk.seq(), e.getMessage());
            String why = cutByCeiling
                    ? "response hit the token ceiling and could not be parsed; this chunk did not enter the graph"
                    : "response could not be parsed; this chunk did not enter the graph";
            drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.CHUNK_UNEXTRACTED, why,
                    "#" + chunk.seq() + (cutByCeiling ? ": hit the token ceiling; " : ": ")
                            + e.getMessage());
            st.unextracted.add(Map.entry(chunk.seq(),
                    "response parsing failed: " + e.getMessage()));
            return null;
        }
        if (extraction.truncated) {
            drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.TRUNCATED_REPLY,
                    cutByCeiling
                            ? "the open reply hit the token ceiling; kept up to the last complete item"
                            : "the open reply was not valid JSON as a whole; read item by item",
                    null);
        }
        if (extraction.skipped > 0) {
            drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.MALFORMED_ITEM,
                    extraction.skipped + " items in the open reply were malformed", null);
        }
        // 名字向量（0041 决定 3 通道 2）：这一块里有名字的东西各算一条；算不出来不拦抽取，
        // 只是这一块少一条召回通道
        Map<String, float[]> nameVecs = new HashMap<>();
        if (settings != null && embed != null) {
            List<Map.Entry<String, String>> wanted = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (OpenEntity e : extraction.entities) {
                String n = e.name().strip();
                if (e.named() && !n.isEmpty() && seen.add(nameKey(n))) {
                    wanted.add(Map.entry(nameKey(n), n));
                }
            }
            nameVecs = NameVectorsService.embedNames(wanted, texts -> embed.embed(texts));
        }
        return new ChunkParsed(extraction, nameVecs);
    }

    // ====================================================================
    // 后半步：apply_open_extraction（extraction_open.rs:207-691，必须按块序）
    // ====================================================================

    void applyOpenExtraction(UUID kbId, UUID documentId, ChunkForExtract chunk, float[] ctx,
                             OpenExtraction extraction, Map<String, float[]> nameVecs,
                             OffsetDateTime attestedAt, Map<String, UUID> boundTypes,
                             boolean pushed, boolean awaitNod, Proposer proposer, RunState st) {
        // ---- 东西：有名字的走身份消解，被描述的建成没有名字事实的实体 ----
        Map<String, List<UUID>> responseClaims = new HashMap<>();
        Map<String, UUID> local = new HashMap<>();
        // 被描述的东西：先记下名字和类别词，等陈述指到它再建（见 place）
        Map<String, Map.Entry<String, String>> deferred = new HashMap<>();
        for (OpenEntity e : extraction.entities) {
            String name = e.name().strip();
            if (name.isEmpty()) {
                continue;
            }
            String kind = e.kind().strip();
            String key = nameKey(name);
            if (local.containsKey(key)) {
                continue;
            }
            UUID id;
            if (e.named()) {
                UUID bound = boundTypes.get(TypeBindingsService.normalize(kind));
                id = resolveHandle(kbId, bound, name, ctx, nameVecs.get(key), chunk.text(),
                        responseClaims, st);
                // 模型说它是什么（「a British film」里的 film）：存成它自己的说法，类的
                // 绑定等对齐来做
                if (!kind.isEmpty()) {
                    try {
                        resolution.setSpecificType(id, kind);
                    } catch (Exception ignore) {
                        // 首写不覆盖的竞争：谁先写上都一样
                    }
                }
                // 名字就在这一块原文里时，给名字事实补出处（0041）。记忆日志里的不补：
                // 那一句算不算出处，要等人点头（0018）
                if (!awaitNod && spanInQuote(name, chunk.text())) {
                    names.record(kbId, id, name,
                            new NamesService.NameSource(chunk.id(), name), attestedAt);
                }
                st.touchedNames.add(tech.seexun.jknow.store.Names.normalizeName(name).toLowerCase(java.util.Locale.ROOT));
                boolean known = false;
                for (Object[] row : st.docEntities) {
                    if (row[0].equals(id)) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    st.docEntities.add(new Object[]{id, kind, name});
                    st.knownByName.putIfAbsent(key, id);
                }
            } else {
                deferred.put(key, Map.entry(name, kind));
                continue;
            }
            local.put(key, id);
        }

        // ---- 陈述 ----
        for (OpenStatement s : extraction.statements) {
            String phrase = s.phrase().strip();
            if (phrase.isEmpty()) {
                continue;
            }
            UUID subject = place(kbId, local, st.knownByName, deferred, st.described, s.subject());
            if (subject == null) {
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.UNKNOWN_REF,
                        "a statement's subject is not a listed thing", s.subject());
                continue;
            }
            // 一个字母数字都没有的值（表格里的「—」、空格）什么也没说：按没有值处理——
            // 这是结构判断，不是词表
            String value = hasAlphanumeric(s.value()) ? s.value().strip() : null;
            // 模型自己写的那个值：引文要核的是它，不是一个实体的名字（#729）
            String statedValue = value;
            UUID object = null;
            String objectName = s.object() == null ? null : s.object().strip();
            if (objectName != null && !objectName.isEmpty()) {
                object = place(kbId, local, st.knownByName, deferred, st.described, objectName);
                if (object == null) {
                    // 宾语写了名字但没在清单上：模型漏列了它。陈述照落，宾语落成字面值——
                    // 不凭空建实体，也不丢这条话（#559 的那一档）
                    drops.dropSignal(kbId, documentId,
                            ExtractionDropsService.Reason.OBJECT_UNDECLARED,
                            "a statement's object is not a listed thing; written as a value",
                            objectName);
                    if (value == null) {
                        value = objectName;
                    }
                }
            }
            // 短语就是值、短语就是主语：表格丢了列头时模型的两种写法。陈述照落——值和
            // 主语都在，信息没丢——只记一笔让它可量；只比字面，不认词
            String trimmedPhrase = phrase.strip();
            if (value != null && sameWords(value, trimmedPhrase)) {
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.PHRASE_IS_VALUE,
                        "kept, but the phrase is the value itself", trimmedPhrase);
            } else if (sameWords(s.subject(), trimmedPhrase)) {
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.PHRASE_IS_SUBJECT,
                        "kept, but the phrase is the subject's own name", trimmedPhrase);
            }
            JsonNode valueJson;
            if (object != null && object.equals(subject)) {
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.OBJECT_MISSING,
                        "a statement points at its own subject", trimmedPhrase);
                continue;
            }
            if (object != null) {
                valueJson = null;
            } else if (value != null) {
                valueJson = JsonNodeFactory.instance.objectNode().put("value", value);
            } else {
                drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.OBJECT_MISSING,
                        "a statement has neither an object nor a value", trimmedPhrase);
                continue;
            }
            // 引文定位。定位不到的那句照样当证据文字记，只是没有偏移，并记一笔——模型
            // 没照抄的句子是「不是原文说的」那一类的苗头，量它
            String quoteText = s.quote() == null ? null : s.quote().strip();
            Quote quote = null;
            if (quoteText != null && !quoteText.isEmpty()) {
                quote = new Quote(quoteText, locate(chunk.text(), quoteText));
                if (quote.span() == null) {
                    drops.dropSignal(kbId, documentId,
                            ExtractionDropsService.Reason.QUOTE_NOT_IN_CHUNK,
                            "a quoted sentence is not in the chunk verbatim", quoteText);
                }
            }
            // 值必须出现在它自己的引文里（#729）：一行五列被压成五条只有值不同的陈述、
            // 引文却是表上面那句导语时，五条里至多一条对。只查带数字的值
            if (statedValue != null && quote != null && hasAsciiDigit(statedValue)
                    && !showsValue(quote.text(), statedValue)) {
                drops.dropSignal(kbId, documentId,
                        ExtractionDropsService.Reason.VALUE_NOT_IN_QUOTE,
                        "a statement's value is not in its own quoted sentence", statedValue);
                continue;
            }
            // 开放陈述没有模型自报的置信度：它说的是「文档这么说了」。看图描述出来的块
            // 照旧压上限（0040 决定 4）
            float confidence = originCeiling(chunk.origin(), 1.0f);
            // 限定：值是清单上某个东西的名字就挂实体，否则挂文字
            List<Object[]> qualifiers = new ArrayList<>(); // (role, JsonNode value, UUID entity)
            for (Map.Entry<String, String> q : s.qualifiers()) {
                String role = q.getKey().strip();
                String text = q.getValue().strip();
                if (role.isEmpty() || text.isEmpty()) {
                    continue;
                }
                UUID entityId = place(kbId, local, st.knownByName, deferred, st.described, text);
                if (entityId != null) {
                    qualifiers.add(new Object[]{role, null, entityId});
                } else {
                    qualifiers.add(new Object[]{role, JsonNodeFactory.instance.textNode(text),
                            null});
                }
            }
            // 时间词：起与止各是一条提及，必须原样在这条陈述的引文里
            List<Object[]> timeWords = new ArrayList<>(); // (words, start, role)
            for (String[] roleAndWords : new String[][]{
                    new String[]{"when", s.when()}, new String[]{"ended", s.ended()}}) {
                String role = roleAndWords[0];
                String words = roleAndWords[1] == null ? null : roleAndWords[1].strip();
                if (words == null || words.isEmpty()) {
                    continue;
                }
                // 推送来的陈述没有引文：条目自己就是证据（0054 决定 4），时间词在块里找
                Integer located = pushed
                        ? (locate(chunk.text(), words) != null ? locate(chunk.text(), words)[0]
                                : null)
                        : locateTime(chunk.text(), quote, words);
                if (located != null) {
                    timeWords.add(new Object[]{words, located, role});
                } else {
                    drops.dropSignal(kbId, documentId,
                            ExtractionDropsService.Reason.TIME_NOT_IN_QUOTE,
                            "a time mention's words are not in the statement's own sentence", words);
                }
            }
            if (awaitNod) {
                // 记忆日志：一切原样进待确认表（0015），人点头时 confirm 才把它落成开放陈述
                PendingService.Proposal proposal = new PendingService.Proposal(kbId, subject, null,
                        object, valueJson, phrase,
                        tech.seexun.jknow.store.temporal.Validity.empty()
                                .attested(attestedAt),
                        confidence, chunk.id(), proposer.userId(), proposer.tokenId(), phrase,
                        qualifiersJson(qualifiers), timeJson(timeWords),
                        quote != null ? quote.span() : null);
                PendingService.Outcome outcome = pending.propose(proposal);
                if (outcome.isProposed()) {
                    st.pendingCount++;
                }
                st.statementCount++;
                continue;
            }
            GraphWriteService.FactWrite fact = graph.insertOpenStatement(kbId, subject, phrase,
                    object, valueJson, attestedAt, confidence);
            // 表层谓词也写短语：今天的读路径都从 fact_surface_predicate 取名字
            graph.addEvidenceLocated(fact.id(), chunk.id(),
                    quote != null ? quote.text() : null, phrase,
                    quote != null ? quote.span() : null);
            for (Object[] q : qualifiers) {
                graph.addStatementQualifier(fact.id(), (String) q[0], (JsonNode) q[1],
                        (UUID) q[2]);
            }
            for (Object[] t : timeWords) {
                timeMentions.record(kbId, fact.id(), chunk.id(), (String) t[0],
                        (Integer) t[1], (String) t[2]);
            }
            st.statementCount++;
        }

        // ---- 别名（0041 决定 2）：服务端只核对名字确实在这一块原文里 ----
        // 记忆日志里的别名不记：那一句算不算出处要等人点头（0018）
        if (!awaitNod) {
            for (var n : extraction.names) {
                String name = n.name().strip();
                if (name.isEmpty()) {
                    continue;
                }
                UUID id = place(kbId, local, st.knownByName, deferred, st.described, n.entity());
                if (id == null) {
                    drops.dropSignal(kbId, documentId, ExtractionDropsService.Reason.UNKNOWN_REF,
                            "a name's thing is not a listed thing", n.entity());
                    continue;
                }
                if (!spanInQuote(name, chunk.text())) {
                    drops.dropSignal(kbId, documentId,
                            ExtractionDropsService.Reason.NAME_NOT_IN_TEXT,
                            "an extra name is not in the chunk", name);
                    continue;
                }
                // 一个名字不会同时是两样东西的名字：这一块或本文档前面已经把它认成
                // 另一个实体的，不记
                String key = tech.seexun.jknow.store.Names.normalizeName(name)
                        .toLowerCase(java.util.Locale.ROOT);
                boolean claimed = false;
                List<UUID> holders = st.handledByName.get(key);
                if (holders != null) {
                    for (UUID x : holders) {
                        if (!x.equals(id)) {
                            claimed = true;
                            break;
                        }
                    }
                }
                if (claimed) {
                    drops.dropSignal(kbId, documentId,
                            ExtractionDropsService.Reason.NAME_CLAIMED_BY_ANOTHER,
                            "an extra name is already another thing's name", name);
                    continue;
                }
                String quote = n.quote() == null ? null : n.quote().strip();
                names.record(kbId, id, name, new NamesService.NameSource(chunk.id(),
                        quote == null || quote.isEmpty() ? chunk.text() : quote), attestedAt);
                st.touchedNames.add(key);
            }
        }

        // 本块抽完即打标：更新时被认领的块携带标记跳过；中断的抽取可续跑
        writes.markChunkExtracted(chunk.id());
    }

    private static boolean hasAlphanumeric(String s) {
        if (s == null) {
            return false;
        }
        return s.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    private static boolean hasAsciiDigit(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * eq_ignore_ascii_case 两边各 trim（#743 那两档只比字面）。<b>只折 ASCII 字母的大小写</b>
     * ——Rust 的 eq_ignore_ascii_case 对非 ASCII 逐位相等才算；Java 的
     * String.equalsIgnoreCase 连 Unicode 大小写也折（'K' 会等于 'K' 的 Kelvin 记号），
     * 范围放宽会把不该判等的判等（审查 M-3）。
     */
    static boolean sameWords(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        String x = a.strip();
        String y = b.strip();
        if (x.length() != y.length()) {
            return false;
        }
        for (int i = 0; i < x.length(); i++) {
            char ca = x.charAt(i);
            char cb = y.charAt(i);
            if (ca != cb) {
                char la = asciiLower(ca);
                char lb = asciiLower(cb);
                if (la != lb) {
                    return false;
                }
            }
        }
        return true;
    }

    private static char asciiLower(char c) {
        return (c >= 'A' && c <= 'Z') ? (char) (c + 32) : c;
    }

    private static JsonNode qualifiersJson(List<Object[]> qualifiers) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (Object[] q : qualifiers) {
            ObjectNode row = arr.addObject();
            row.put("role", (String) q[0]);
            if (q[2] != null) {
                row.put("entity_id", ((UUID) q[2]).toString());
            } else {
                row.set("value", (JsonNode) q[1]);
            }
        }
        return arr;
    }

    private static JsonNode timeJson(List<Object[]> timeWords) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (Object[] t : timeWords) {
            ObjectNode row = arr.addObject();
            row.put("text", (String) t[0]);
            row.put("char_start", (Integer) t[1]);
            row.put("role", (String) t[2]);
        }
        return arr;
    }

    // ====================================================================
    // 消解的句柄层（extraction.rs:200-333）
    // ====================================================================

    /**
     * extraction.rs:263-333 resolve_handle：名字照旧走身份消解（同一回复里两个同名的
     * 东西不会塌成一个，0041 的名字事实照记）。排除项 = response_claims（本回复内已
     * 占用）+ handled_by_name（本文档跨回复占用 &gt;1）；歧义裸名保证「新回复为歧义裸名
     * 新开句柄」仍落到同一个文档级 C——一个新拼写不是猜 A 或 B 的许可。
     */
    UUID resolveHandle(UUID kbId, UUID typeId, String name, float[] ctx, float[] nameVec,
                       String text, Map<String, List<UUID>> responseClaims, RunState st) {
        String normalized = tech.seexun.jknow.store.Names.normalizeName(name).toLowerCase(java.util.Locale.ROOT);
        List<UUID> responseExcluded = responseClaims.getOrDefault(normalized, List.of());
        List<UUID> documentExcluded = new ArrayList<>();
        List<UUID> holders = st.handledByName.get(normalized);
        if (holders != null && holders.size() > 1) {
            documentExcluded.addAll(holders);
        }
        List<UUID> excluded = new ArrayList<>(responseExcluded);
        for (UUID id : documentExcluded) {
            if (!excluded.contains(id)) {
                excluded.add(id);
            }
        }
        boolean reuseDocumentProvisional = responseExcluded.isEmpty() && documentExcluded.size() > 1;
        UUID id;
        if (reuseDocumentProvisional && st.ambiguousBareCache.containsKey(normalized)) {
            id = st.ambiguousBareCache.get(normalized);
        } else {
            ResolutionService.QueuedResolution r = resolution.resolveAndQueue(kbId, typeId, name,
                    ctx, nameVec, text, excluded, excluded);
            id = r.entityId();
            // 只有批量可裁的（Adjudicating）才唤醒裁决任务；纯人工审核对不该唤醒裁决器
            if (r.needsAdjudication()) {
                st.needsAdjudication = true;
            }
            if (reuseDocumentProvisional) {
                st.ambiguousBareCache.put(normalized, id);
            }
        }
        // extraction.rs:239-260 create_namesake_reviews：排除名单里每个别的实体一条
        // namesake 人工对（Human 阶段——分不出谁是谁，只能等人裁，绝不唤醒 LLM 裁决器）
        if (createNamesakeReviews(kbId, id, excluded)) {
            st.humanReviewsFound = true;
        }
        responseClaims.computeIfAbsent(normalized, k -> new ArrayList<>()).add(id);
        List<UUID> documentClaims = st.handledByName.computeIfAbsent(normalized,
                k -> new ArrayList<>());
        if (!documentClaims.contains(id)) {
            documentClaims.add(id);
        }
        return id;
    }

    /** extraction.rs:239-260 create_namesake_reviews：排除名单里每个别的实体一条 namesake 人工对。 */
    private boolean createNamesakeReviews(UUID kbId, UUID entityId, List<UUID> others) {
        boolean created = false;
        for (UUID otherId : others) {
            if (otherId.equals(entityId)) {
                continue;
            }
            resolution.createReview(kbId, entityId, otherId, 1.0f, "namesake",
                    ResolutionService.ReviewStage.HUMAN);
            created = true;
        }
        return created;
    }

    // ====================================================================
    // place（extraction_open.rs:897-929）
    // ====================================================================

    /**
     * 陈述里写的名字 → 实体。先看这一块列出的，再看本文档前面认下的（提示词里的清单）。
     * <b>被描述的东西用到才建</b>：没人指着的描述不是实体，只是一句话的一部分——描述先记
     * 在 deferred 里，第一条指到它的陈述才把它建出来；同一篇里同一段描述只建一次。
     */
    UUID place(UUID kbId, Map<String, UUID> local, Map<String, UUID> known,
               Map<String, Map.Entry<String, String>> deferred, Map<String, UUID> described,
               String name) {
        String key = nameKey(name);
        UUID id = local.containsKey(key) ? local.get(key) : known.get(key);
        if (id != null) {
            return id;
        }
        Map.Entry<String, String> entry = deferred.get(key);
        if (entry == null) {
            return null;
        }
        UUID found = described.get(key);
        if (found == null) {
            found = resolution.createDescribed(kbId, entry.getKey(),
                    entry.getValue().isEmpty() ? null : entry.getValue());
            described.put(key, found);
        }
        local.put(key, found);
        return found;
    }
}
