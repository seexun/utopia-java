package tech.seexun.jknow.web.knowledge.jobs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.web.knowledge.events.AppEventBus;
import tech.seexun.jknow.web.knowledge.llm.LlmSupport;
import tech.seexun.jknow.web.knowledge.query.QueryEngine;
import tech.seexun.jknow.web.knowledge.query.QueryEngines;
import tech.seexun.jknow.web.knowledge.query.SchemaColumn;
import tech.seexun.jknow.store.DatasourcesService;
import tech.seexun.jknow.store.ExplorationRunsService;
import tech.seexun.jknow.store.ExplorationRunsService.DropReason;
import tech.seexun.jknow.store.MappingsService;
import tech.seexun.jknow.store.ResolutionService;
import tech.seexun.jknow.store.SettingsService;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.entity.KbRow;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.AlertsMapper;
import tech.seexun.jknow.store.mapper.KbFamilyMapper;
import tech.seexun.jknow.store.mapper.KbMapper;

/**
 * 问数语义映射的 Agentic 探索（server/mappings.rs 623 行逐语义——main.rs:406-416 的
 * {@code explore_mappings} job；**无探索游标、纯单轮 LLM 辅助**）：读挂载源的 schema +
 * KB 既有概念，让 LLM 提议「业务概念（Metric/Dimension 实体）→ 数据资产定义」的映射。
 * 提议写进 concept_mappings（status=proposed）→ Review 页自成一档，Confirm/Reject 之后
 * status 变 confirmed，问数只读确认过的那些（0011：口径生效权在人）。
 */
@Component
public class ExploreMappingsJob {

    private static final Logger log = LoggerFactory.getLogger(ExploreMappingsJob.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** MAX_SCHEMA_CHARS：schema 提示词的跨源总预算（Rust String::len 的字节口径）。 */
    static final int MAX_SCHEMA_CHARS = 12_000;

    /** schema 文本开头那一句图例：列行里的方括号是什么。 */
    static final String COLUMN_MARKERS =
            "Column markers: [PK] single-column primary key; [FK→schema.table] foreign key "
                    + "to that table.\n";

    /** 一轮描述最多留几个问题（MAX_DATA_QUESTIONS）。多了没人答；八个已经是一页。 */
    static final int MAX_DATA_QUESTIONS = 8;

    /** accepted=0 时的告警 kind（前端按它查文案，稳定契约）。 */
    static final String MAPPING_EXPLORATION_EMPTY = "mapping.exploration_empty";

    private final KbMapper kbs;
    private final KbFamilyMapper kbFamily;
    private final SettingsService settingsService;
    private final DatasourcesService datasources;
    private final MappingsService mappings;
    private final ExplorationRunsService runs;
    private final ResolutionService resolution;
    private final LlmSupport llm;
    private final AlertsMapper alerts;
    private final AppEventBus events;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public ExploreMappingsJob(KbMapper kbs, KbFamilyMapper kbFamily,
                              SettingsService settingsService, DatasourcesService datasources,
                              MappingsService mappings, ExplorationRunsService runs,
                              ResolutionService resolution, LlmSupport llm,
                              AlertsMapper alerts, AppEventBus events,
                              javax.sql.DataSource dataSource) {
        this.kbs = kbs;
        this.kbFamily = kbFamily;
        this.settingsService = settingsService;
        this.datasources = datasources;
        this.mappings = mappings;
        this.runs = runs;
        this.resolution = resolution;
        this.llm = llm;
        this.alerts = alerts;
        this.events = events;
        this.jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
    }

    /** main.rs:406-416：载荷取 kb_id。 */
    public void handle(Job job) {
        JsonNode kbNode = job.payload().get("kb_id");
        if (kbNode == null || !kbNode.isTextual()) {
            throw new IllegalStateException("payload 缺少 kb_id");
        }
        exploreMappings(UUID.fromString(kbNode.asText()));
    }

    /**
     * 一轮探索开账、干活、收账（#503）。<b>先开行再干活</b>：跑挂了的那一轮也留一行，
     * 因为「失败」与「跑了但一条都没提」从前在页面上都是「没有新提议」，而该做的事
     * 完全不同。
     */
    public void exploreMappings(UUID kbId) {
        UUID run = runs.start(kbId);
        try {
            explore(kbId, run);
        } catch (Exception e) {
            // 报错路径上的失败不该淹掉它要报的那件事（与 source_name 同一条理由）
            try {
                runs.fail(run, e.toString());
            } catch (Exception fe) {
                log.warn("kb={} run={} 探索失败账也没记上: {}", kbId, run, fe.toString());
            }
            throw e instanceof RuntimeException r ? r : new IllegalStateException(e);
        }
    }

    private void explore(UUID kbId, UUID run) throws Exception {
        KbRow kb = kbs.selectById(kbId);
        if (kb == null) {
            throw new IllegalStateException("no such knowledge base: " + kbId);
        }
        LlmSettingsRow settings = settingsService.get(kb.workspaceId());
        ChatClient client = LlmSupport.chatClient(settings);
        if (client == null) {
            throw new IllegalStateException("Chat model not configured");
        }

        List<DataSourceView> sources = datasources.mounted(kbId);
        if (sources.isEmpty()) {
            throw new IllegalStateException("No data sources mounted");
        }
        // Metric/Dimension 不在任何内置包里——0009 之后建库不再自带类，没有它们每条
        // 提议都被吞掉（#223）
        mappings.ensureConceptTypes(kbId);

        // 各源 schema（引擎直读，保证新鲜）。**上限是跨源的一个总数**——从前那个 break
        // 只跳出当前源的列循环，MAX_SCHEMA_CHARS 读起来像个上限，实际上是「每个源各自
        // 超一次」的下限。字节数按 Rust String::len 口径累计（UTF-8）。
        StringBuilder schemaTxt = new StringBuilder(COLUMN_MARKERS);
        long schemaBytes = utf8(COLUMN_MARKERS);
        int tablesScanned = 0;
        int columnsScanned = 0;
        boolean truncated = false;
        for (DataSourceView ds : sources) {
            if (truncated) {
                break;
            }
            DatasourcesService.EngineAndConn ec = datasources.engineAndConn(ds.id());
            QueryEngine engine = QueryEngines.engineFor(ec.engine(), ec.conn());
            List<SchemaColumn> cols = engine.fetchSchema();
            String header = "\n=== source: " + ds.name() + " ===\n";
            schemaTxt.append(header);
            schemaBytes += utf8(header);
            String current = "";
            for (SchemaColumn c : cols) {
                String key = c.schema() + "." + c.table();
                if (!key.equals(current)) {
                    current = key;
                    tablesScanned++;
                    String tableLine = "table " + key + ":\n";
                    schemaTxt.append(tableLine);
                    schemaBytes += utf8(tableLine);
                }
                columnsScanned++;
                String line = columnLine(c);
                schemaTxt.append(line);
                schemaBytes += utf8(line);
                if (schemaBytes > MAX_SCHEMA_CHARS) {
                    schemaTxt.append("(truncated)\n");
                    truncated = true;
                    break;
                }
            }
        }

        int cap = proposalCap(tablesScanned);
        List<String> sourceList = sources.stream().map(DataSourceView::name).toList();
        try {
            runs.scanned(run, sourceList, tablesScanned, columnsScanned, truncated, cap);
        } catch (Exception e) {
            log.debug("kb={} run={} scanned 账没记上: {}", kbId, run, e.toString());
        }

        // 先写库的数据描述与拿不准的问题，再提口径。**描述不依赖提议成不成**：提议那
        // 一步解析失败整轮报错，描述已经落下了；反过来描述写不出来也不该拖垮提议
        try {
            describeData(kbId, settings, client, kb, schemaTxt.toString());
        } catch (Exception e) {
            log.warn("kb={} 数据描述没写成，提议照常: {}", kbId, e.toString());
        }

        // 既有概念（供归并复用，避免重复起名）
        List<String> existingNames = jdbc.queryForList(
                "SELECT e.canonical_name FROM entities e JOIN entity_types t ON t.id = e.type_id "
                        + "WHERE e.kb_id = ? AND e.merged_into IS NULL "
                        + "AND t.key IN ('metric','dimension') ORDER BY e.canonical_name LIMIT 100",
                String.class, kbId);

        // 人写的约定进提议的提示词。schema 里没有「测试单不算数」这句话（#570）
        String conventions = kb.dataConventions() != null && !kb.dataConventions().trim().isEmpty()
                ? kb.dataConventions().trim() : "(none stated)";
        String prompt = "You are building the semantic layer of a BI system. Given database "
                + "schemas, propose business concepts a user would ask about, each mapped to a "
                + "concrete definition.\n"
                + "Existing concepts (reuse these names when the meaning matches): "
                + (existingNames.isEmpty() ? "(none)" : String.join(", ", existingNames)) + "\n"
                + "Conventions stated by the owner of this base — apply them in every "
                + "definition, as filters, unit conversions, or the choice of column: "
                + conventions + "\n"
                + "Schemas:\n" + schemaTxt + "\n"
                + "Reply with ONLY a JSON array, each item:\n"
                + "{\"name\": \"business concept name\", \"kind\": \"metric\"|\"dimension\", "
                + "\"source\": \"data source name\", "
                + "\"definition\": {\"table\": \"schema.table\", \"expr\": \"SQL expression\", "
                + "\"sql\": \"full SELECT if joins are needed (optional)\", \"unit\": "
                + "\"optional\"}, "
                + "\"summary\": \"one line: source + expression, shown to reviewers\", "
                + "\"rationale\": \"why this mapping, citing column comments\"}\n"
                + "Metrics are aggregatable quantities (use sum/count/avg in expr); dimensions "
                + "are group-by columns. Propose at most " + cap + ", only well-grounded ones.";

        ChatClient.Reply reply = llm.chatRetryingRateLimits(settings, client,
                List.of(new ChatClient.ChatMessage("user", prompt)), null);
        String jsonStr = reply.text().trim();
        if (jsonStr.startsWith("```json")) {
            jsonStr = jsonStr.substring("```json".length());
        } else if (jsonStr.startsWith("```")) {
            jsonStr = jsonStr.substring("```".length());
        }
        if (jsonStr.endsWith("```")) {
            jsonStr = jsonStr.substring(0, jsonStr.length() - "```".length());
        }
        jsonStr = jsonStr.trim();
        List<JsonNode> proposals;
        try {
            proposals = new ArrayList<>();
            for (JsonNode n : JSON.readTree(jsonStr)) {
                proposals.add(n);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Mapping proposal parse error: " + e.getMessage(), e);
        }

        String[] sourceNames = sources.stream().map(DataSourceView::name).toArray(String[]::new);
        int accepted = 0;
        // 丢弃要计数，还要留一条例子——光有计数诊断不动：「十二条源名对不上」得配上
        // 「模型说的是 tpch，挂的是 tpch-2026-09-08」才知道该改什么
        TreeMap<String, Drop> drops = new TreeMap<>();
        java.util.function.BiConsumer<String, String> note = (reason, example) ->
                drops.computeIfAbsent(reason, k -> new Drop(example)).n++;
        TreeSet<String> covered = new TreeSet<>();
        int look = Math.min(proposals.size(), cap);
        for (JsonNode p : proposals.subList(0, look)) {
            String name = p.path("name").isTextual() ? p.path("name").asText().trim() : "";
            String kind = p.path("kind").isTextual() ? p.path("kind").asText() : "";
            String said = p.path("source").isTextual() ? p.path("source").asText().trim() : "";
            if (name.isEmpty() || !("metric".equals(kind) || "dimension".equals(kind))) {
                note.accept(DropReason.KIND, "name=\"" + name + "\" kind=\"" + kind + "\"");
                continue;
            }
            // **只挂了一个源时，模型说什么都算它**（#501）：没有歧义可言，而对不上的
            // 代价是整条提议消失——实测源叫 tpch-2026-09-08-12-30，模型照着 schema
            // 回 tpch，十二条一条不剩地被吞掉，任务照样 done
            String source;
            if (sources.size() == 1) {
                source = sourceNames[0];
            } else {
                source = null;
                for (String s : sourceNames) {
                    if (s.equalsIgnoreCase(said)) {
                        source = s;
                        break;
                    }
                }
                if (source == null) {
                    note.accept(DropReason.SOURCE, "model said \"" + said + "\", mounted: "
                            + String.join(", ", sourceNames));
                    continue;
                }
            }
            List<UUID> typeHit = jdbc.queryForList(
                    "SELECT id FROM entity_types WHERE kb_id = ? AND key = ?", UUID.class, kbId,
                    kind);
            UUID typeId = typeHit.isEmpty() ? null : typeHit.get(0);
            if (typeId == null) {
                note.accept(DropReason.TYPE, "no entity type \"" + kind + "\" in this base");
                continue;
            }

            // 概念实体：走消解（同名归并）。没有块原文可给——这些名字来自数据源的
            // schema 探索，不是从文档句子里抽的
            var resolved = resolution.resolveMention(kbId, typeId, name, null, null, null,
                    List.of());

            // 定义拆成列写进 concept_mappings（0011）——它不是关于世界的断言，是配置
            JsonNode def = p.get("definition");
            if (def == null || !def.isObject()) {
                note.accept(DropReason.DEFINITION, "\"" + name + "\" has no definition object");
                continue;
            }
            // 覆盖率的分子。分母是这一轮扫见的表数——十一条提议对着八十列的宽表，与
            // 十一条刚好覆盖完一个小库，从 concept_mappings 里看长得一模一样
            String table = fieldOf(def, "table");
            if (table != null) {
                covered.add(table);
            }
            String summary = p.path("summary").isTextual() ? p.path("summary").asText()
                    : (def.path("summary").isTextual() ? def.path("summary").asText() : null);
            boolean written = mappings.propose(kbId, resolved.entityId(), source, table,
                    fieldOf(def, "expr"), fieldOf(def, "sql"), fieldOf(def, "unit"), summary,
                    def.path("derived").isBoolean() && def.path("derived").asBoolean()).written();
            if (written) {
                accepted++;
            } else {
                note.accept(DropReason.DECIDED,
                        "\"" + name + "\" on " + source + ": already confirmed or rejected");
            }
        }
        // 超过上限的那些一条没看，也得记：不然 returned 与 accepted + dropped 对不上
        if (proposals.size() > cap) {
            Drop capDrop = new Drop("model returned " + proposals.size() + ", cap " + cap);
            capDrop.n = proposals.size() - cap;
            drops.put(DropReason.CAP, capDrop);
        }

        ObjectNode dropped = JSON.createObjectNode();
        drops.forEach((k, v) -> {
            ObjectNode d = dropped.putObject(k);
            d.put("n", v.n);
            d.put("example", v.example);
        });
        List<String> coveredList = new ArrayList<>(covered);
        try {
            runs.finish(run, proposals.size(), accepted, dropped, coveredList);
        } catch (Exception e) {
            // 账没记上不该让提议白跑，但也不能装作记上了：留在日志里
            log.warn("kb={} run={} 探索账没记上: {}", kbId, run, e.toString());
        }
        log.info("kb={} proposals={} returned={} tables={} covered={} truncated={} "
                + "映射探索完成，提议已入审核队列", kbId, accepted, proposals.size(),
                tablesScanned, coveredList.size(), truncated);
        // 一条都没提出来时页面上什么都不会变——Pending 还是 0。走告警中心说一声，人才
        // 知道该去刷新结构或给列加注释。**告警要带上是怎么空的**：「模型一条没回」与
        // 「回了十二条全被源名挡掉」是两件事，dropped 就是这个区别
        if (accepted == 0) {
            ObjectNode detail = JSON.createObjectNode();
            detail.put("proposals", 0);
            detail.set("sources", arrayOf(sourceNames));
            detail.put("returned", proposals.size());
            ObjectNode dropCounts = detail.putObject("dropped");
            drops.forEach((k, v) -> dropCounts.put(k, (long) v.n));
            detail.put("tables_scanned", tablesScanned);
            try {
                alerts.insertAlert(Uuids.nowV7(), kbId, "info", MAPPING_EXPLORATION_EMPTY,
                        "editor", null, null, detail);
            } catch (Exception e) {
                log.warn("kb={} 映射探索空结果的告警没写进去: {}", kbId, e.toString());
            }
            events.emitAlert();
        }
        events.emitReview(kbId);
    }

    private static ArrayNode arrayOf(String[] xs) {
        ArrayNode n = JSON.createArrayNode();
        for (String x : xs) {
            n.add(x);
        }
        return n;
    }

    /** 一个丢弃原因的账：几条 + 头一条的例子（Rust 的 (i64, String)）。 */
    private static final class Drop {
        int n;
        String example;

        Drop(String example) {
            this.example = example;
        }
    }

    private static String fieldOf(JsonNode def, String key) {
        JsonNode v = def.get(key);
        return v != null && v.isTextual() && !v.asText().isEmpty() ? v.asText() : null;
    }

    private static int utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * schema 文本里的一列（column_line）：{@code   名字 类型 [PK, FK→schema.table] -- 注释}。
     * 键标记放在类型后面、注释前面——{@code -- } 已经是注释的分隔，写成 {@code -- PK}
     * 就与一条内容恰好是「PK」的注释分不开。可空不写进来（#502）。
     */
    static String columnLine(SchemaColumn c) {
        List<String> marks = new ArrayList<>();
        if (c.isPrimaryKey()) {
            marks.add("PK");
        }
        if (c.referencesTable() != null) {
            marks.add("FK→" + c.referencesTable());
        }
        String mark = marks.isEmpty() ? "" : " [" + String.join(", ", marks) + "]";
        String comment = c.comment() != null ? " -- " + c.comment() : "";
        return "  " + c.column() + " " + c.dataType() + mark + comment + "\n";
    }

    /**
     * 一轮允许提几条口径（proposal_cap）。**上限跟着 schema 的大小走**：写死的 12 对
     * 三张表的小库绰绰有余，对 TPC-H 八张表就是覆盖率的天花板（24 条真值 12 条上限
     * 覆盖率 25%，#501）。下限 12 上限 60；一张表都没扫见（连不上/schema 空）也不该
     * 是 0——0 条上限会把「连不上」变成「模型什么都没提」。
     */
    static int proposalCap(int tables) {
        return Math.max(12, Math.min(60, tables * 3));
    }

    /** 模型回的 JSON 常裹着代码栅栏；剥掉它（json_body）。 */
    static String jsonBody(String reply) {
        String s = reply.trim();
        if (s.startsWith("```json")) {
            s = s.substring("```json".length());
        } else if (s.startsWith("```")) {
            s = s.substring("```".length());
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - "```".length());
        }
        return s.trim();
    }

    /**
     * 模型回的 {@code {"description":…,"questions":[…]}}（parse_description）。描述空的
     * 整个不要——一个空描述盖掉上一轮的好描述，比没写更糟。
     */
    static java.util.Map.Entry<String, List<String>> parseDescription(String reply) {
        JsonNode v;
        try {
            v = JSON.readTree(jsonBody(reply));
        } catch (Exception e) {
            return null;
        }
        if (!v.path("description").isTextual()) {
            return null;
        }
        String description = v.get("description").asText().trim();
        if (description.isEmpty()) {
            return null;
        }
        List<String> questions = new ArrayList<>();
        for (JsonNode q : v.path("questions")) {
            if (!q.isTextual()) {
                continue;
            }
            String t = q.asText().trim();
            if (!t.isEmpty() && questions.size() < MAX_DATA_QUESTIONS) {
                questions.add(t);
            }
        }
        return Map.entry(description, questions);
    }

    /**
     * 探索写库的数据描述，并列出它拿不准的地方（describe_data，#570）。两半两个来路：
     * schema 与注释说了的这里写；schema 没说的（测试单不算数、GMV 用实付不用优惠前）
     * <b>不能猜</b>——猜出来的约定进了提示词，问数会照着算，比没有更糟。所以拿不准的
     * 写成问题，人答，答案进 data_conventions，这里不碰那一列。语言跟本体语言走（0004）。
     */
    private void describeData(UUID kbId, LlmSettingsRow settings, ChatClient client, KbRow kb,
                              String schemaTxt) throws Exception {
        String lang = "zh".equals(kb.ontologyLang()) ? "Chinese" : "English";
        String prompt = "You are documenting a database for analysts who will ask questions "
                + "about it in plain language. Reply with ONLY a JSON object "
                + "{\"description\": \"...\", \"questions\": [\"...\"]}.\n"
                + "description (" + lang + ", 10 to 20 short lines): what each table is a table "
                + "of and what one row is; which columns are keys; the unit of money and "
                + "quantity columns ONLY where a comment states it; what status or code values "
                + "mean ONLY where a comment states it; which columns are the time axis; which "
                + "columns look alike and how they differ (gross vs net, list vs paid). State "
                + "only what the schema and its comments say. Do not invent business rules, "
                + "thresholds, or which rows count.\n"
                + "questions (" + lang + ", at most " + MAX_DATA_QUESTIONS + "): the conventions "
                + "you would need to compute business figures correctly but the schema does not "
                + "state, each phrased so the owner of the data can answer in one line — which "
                + "status values count, whether flagged rows (test, internal, gift) are "
                + "excluded, which of two similar amount columns is the figure, units where no "
                + "comment states them, whether net figures subtract refunds. Ask only what the "
                + "schema leaves open.\n"
                + "Schemas:\n" + schemaTxt;
        ChatClient.Reply reply = llm.chatRetryingRateLimits(settings, client,
                List.of(new ChatClient.ChatMessage("user", prompt)), null);
        var parsed = parseDescription(reply.text());
        if (parsed == null) {
            throw new IllegalStateException("description reply did not parse: "
                    + codePointsOf(reply.text(), 120));
        }
        kbFamily.setDataDescription(kbId, parsed.getKey(),
                arrayOf(parsed.getValue().toArray(new String[0])));
        log.info("kb={} lines={} questions={} 数据描述已写", kbId,
                parsed.getKey().split("\n", -1).length, parsed.getValue().size());
    }

    private static String codePointsOf(String s, int limit) {
        return s.codePoints().limit(limit)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }
}
