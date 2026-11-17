package tech.seexun.jknow.web.knowledge.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;

/**
 * 工具清单——<b>给模型看的那一份</b>（chat.rs base_tools/tools_schema 逐字移植）。
 *
 * <p>留在 chat 域而不在执行侧：它是提示词的一部分，随对话策略走；执行侧
 * （{@link ToolExecutor}）只管拿到参数之后干什么。描述是写给模型读的，每一轮都在请求里：
 * 不许有连着的空格（chat.rs 的单空格测试钉着这条）。
 *
 * <p>11 基础工具 + 条件 query_data（挂了数据源才入列）/remember（editor 及以上）。
 * 判据（required/uuid 格式/字符串类型）全部取自这一份 JSON——check_call 直接读表，
 * 加一个必填参数校验自动跟上，不必记得来改第二处。
 */
@Component
public class ChatToolSchema {

    /**
     * remember 曾整个停用过一段（0015）：它那时会把一句话直接变成图上一条活边。
     * 现在抽取侧接上了 pending_facts：记忆抽出的事实先等人点头，再进账本。
     * 这个开关留着，是为了下次再发现「工具会悄悄改图」时有地方立刻拉闸——
     * 宁可没有这个工具，也不要一个会悄悄改图的工具。
     */
    public static final boolean REMEMBER_ENABLED = true;

    /** 隐藏闸刀：让「你好」这类不需要库的问题有一个诚实的出口（agent.rs NO_EVIDENCE_TOOL）。 */
    public static final String NO_EVIDENCE_TOOL = "no_evidence_needed";

    public static final String NO_EVIDENCE_DESCRIPTION = """
            Declare that this question does not need the knowledge base: a greeting, a question \
            about this conversation itself, or a request to reword or shorten a previous answer. \
            Never use it for a question about facts, entities, documents, dates or data; for \
            those, call the tool that gathers the evidence. After calling it, answer directly.""";

    static final String MEMORY_PROMPT = """
            Memory: you can persist knowledge with the remember tool. Use it when the user says \
            "remember/record this" or states a decision meant to last. In your reply, say that the \
            sentence was recorded and that the facts extracted from it will be shown for the user's \
            confirmation before entering the graph; never say a fact is already in the graph. \
            Never invent memories, and never call it for small talk.""";

    static final String SYSTEM_PROMPT = """
            You are the assistant of Utopia, a temporal knowledge platform. You have tools: \
            search_chunks (document search) and get_document (the full text of one document found \
            by search), find_entities, entity_facts, neighbors, timeline, paths_between and \
            changes (a bi-temporal knowledge graph), and search_docs (Utopia's own manual, the \
            Charter).\n\
            The knowledge base holds whatever its owners ingested: documents, and a graph extracted \
            from them. You do not know what is in it until you look; public companies, well-known \
            people and events are as likely to be there as private material. A question you could \
            answer from memory is still answered from the base, and "general knowledge" is never a \
            reason to skip the tools. Never say the base lacks something you have not searched for.\n\
            search_chunks returns short excerpts of the best-matching sections only. When a hit is \
            clearly the right document but the excerpt does not carry the answer, read the whole \
            document with get_document before saying the knowledge base does not have it.\n\
            The graph has TWO independent time axes:\n\
            - World time \u2014 when something was true. entity_facts(`at` = as of that date).\n\
            - Record time \u2014 when we came to believe it, and when we revised it. entity_facts and \
            search_chunks take `as_of` = the base as it stood at that moment, before later \
            corrections, retractions and merges; changes lists what moved in a window.\n\
            "Who was CTO in 2019" is world time; "what did we learn last month" and "what did \
            we get wrong" are record time. The same fact has a position on both.\n\
            Boundary: search_docs answers questions about Utopia itself (features, ingestion, \
            permissions, what fields like 'missing' or validity ranges mean); the other tools answer \
            questions about the knowledge stored in it. Never mix the manual into answers about the \
            base's contents unless they asked about Utopia's behavior.\n\
            \n\
            Method:\n\
            First decide what the message is about. A message about THIS CONVERSATION \u2014 translate it, \
            say it shorter, rephrase it, "what did you just say", "why" \u2014 is answered from the \
            transcript above with NO tool calls: the evidence is already in it. Gathering it again is \
            not merely wasted work \u2014 with several entities sharing a name the second pass can land on \
            a different one, and the "translation" then says something else. Just deliver it \u2014 no \
            preamble about what you are or are not looking up. Everything below is for messages about \
            the knowledge base.\n\
            1. For factual questions \u2014 questions about the knowledge base, never one about this \
            conversation \u2014 ALWAYS gather evidence with tools before answering. For how two \
            things are related, call paths_between (names are fine); for the history of one \
            thing, timeline; to see what is linked to it, neighbors; for its facts, entity_facts, \
            narrowed with predicate / object_type / since / until. search_chunks answers content \
            and detail questions. Combine both when useful.\n\
            2. Facts carry validity ranges (from \u2192 to). For "as of <date>" questions pass `at` to \
            entity_facts and the server filters to that moment; for history questions omit `at` \
            to see the full timeline. For 'what did we know / have on record / believe as of <date>' \
            or 'before <memo> arrived' pass `as_of` \u2014 that is the record axis and the ONLY way to \
            answer such a question; do not narrate a plan, call the tool. The two combine: `at` for \
            the date asked about, `as_of` for when. State dates in the answer. Dates in tool output \
            carry their own precision: `2023` means the year and `2023-06` the month \u2014 never turn \
            them into a specific day; `undated` marks a fact with no stated start; `ended, date \
            unknown` marks one the text says is over, date not given.\n\
            2a. For 'before a correction or memo arrived', call changes to find its exact record \
            timestamp, then call entity_facts with `before` set to that timestamp, copied exactly as \
            printed \u2014 the server reads the base as it stood strictly before that change. Never \
            compute an earlier `as_of` yourself. Keep `at` for the world date asked about.\n\
            2b. For "what changed / what is new / what did we get wrong since <date>", call changes \u2014 \
            it needs no entity. Name the document a correction came from in plain prose. changes \
            returns no [n] numbers and no URLs, so never write a bracketed citation or a placeholder \
            like [Link] after one of its lines \u2014 the document's name IS the attribution.\n\
            3. Several entities can share one name \u2014 check the disambiguator and pick the right one; \
            if genuinely ambiguous, ask the user which one they mean.\n\
            4. Stop calling tools as soon as you have enough evidence. Then answer concisely: cite \
            with [n] at the end of supported sentences, using only numbers a tool printed. Search \
            results carry them, and so does each fact line of entity_facts, neighbors, timeline and \
            paths_between: its [n] opens the passage that fact was read from. A fact line without \
            a number (a derived fact, or one whose documents are gone) is stated without a bracket. \
            An end written `superseded by X [m]` was set by the timeline when that later fact \
            began: cite [m] for that end, never the line's own [n]. An end marked `end derived` came \
            from the timeline too. A line marked `start corrected` or `end corrected` had that date \
            changed by a person, and one marked `corrected` had both (the note after the colon is \
            theirs, not a document's). A derived or changed date is not in the passage the line's [n] \
            opens, so never attribute it to that passage; a date the line does not mark still is. \
            If the evidence is insufficient, say so explicitly \u2014 never fabricate.\n\
            5. Always respond in the same language as the user's question.""";

    private final ChatFrames frames;

    public ChatToolSchema(ChatFrames frames) {
        this.frames = frames;
    }

    /** 工具清单的 JSON（MCP 语义层后续里程碑共用这一份——名字/描述/schema 抄两套迟早分叉）。 */
    public JsonNode baseTools() {
        return frames.parse("""
                [
                  {"type":"function","function":{
                    "name":"search_chunks",
                    "description":"Full-text + semantic search over the knowledge base documents. Returns numbered source excerpts you can cite as [n], each with the document_id it came from. Excerpts are cut short and only the best-matching sections come back, so when the answer may sit elsewhere in a hit, call get_document with that document_id to read the whole document.",
                    "parameters":{"type":"object","properties":{
                      "query":{"type":"string","description":"Search query, phrased in the corpus language."},
                      "as_of":{"type":"string","description":"Optional RECORD-time moment (YYYY-MM-DD or RFC3339): search only what the knowledge base held at that moment \u2014 earlier versions of documents, documents deleted since. Full-text recall stays current, so hits are correct but may be incomplete. Omit for the current base."}
                    },"required":["query"]}}},
                  {"type":"function","function":{
                    "name":"get_document",
                    "description":"Read the full text of ONE knowledge base document, all sections in order, by the document_id shown in search_chunks results. Use it whenever a search hit looks like the right document but the excerpt does not contain the answer \u2014 action items, decisions and lists usually sit past the excerpt or in a section that did not match the query.",
                    "parameters":{"type":"object","properties":{
                      "document_id":{"type":"string","format":"uuid","description":"Document id (uuid) from a search_chunks result line."}
                    },"required":["document_id"]}}},
                  {"type":"function","function":{
                    "name":"search_docs",
                    "description":"Search the Utopia product manual (the \\"Utopia Charter\\") \u2014 how the platform itself works (ingestion and sync, missing markers and versions, the graph and review flow, roles, settings) \u2014 and never the user's own documents, which live in search_chunks.",
                    "parameters":{"type":"object","properties":{
                      "query":{"type":"string","description":"What to look up in the manual."}
                    },"required":["query"]}}},
                  {"type":"function","function":{
                    "name":"find_entities",
                    "description":"Look up entities in the knowledge graph by (partial) name. Returns id, name, type and a disambiguator when several entities share a name.",
                    "parameters":{"type":"object","properties":{
                      "name":{"type":"string","description":"Entity name or a fragment of it."}
                    },"required":["name"]}}},
                  {"type":"function","function":{
                    "name":"entity_facts",
                    "description":"Facts about one entity from the bi-temporal knowledge graph: relations with validity ranges (from \u2192 to; 'now' = still ongoing). The best tool for who/when/history questions. Use after find_entities. Pass `at` to see the world as of that date (server-side filter) \u2014 always do this for \\"who was X in <year/month>\\" questions. Conclusions a business rule reached about this entity come back too, marked `[rule: <name>]` with the readings that made them true \u2014 take those as given rather than re-deriving them from the readings yourself. Output is grouped by predicate and cut at `limit`; narrow with predicate, object_type, since / until, or use timeline / neighbors / paths_between.",
                    "parameters":{"type":"object","properties":{
                      "entity_id":{"type":"string","description":"Entity id (uuid) from find_entities, or an entity name (the server picks the best match and says which)."},
                      "predicate":{"type":"string","description":"Optional: only facts whose relation name contains this (e.g. 'founder')."},
                      "object_type":{"type":"string","description":"Optional: only facts whose other end is of this type (e.g. 'Person')."},
                      "since":{"type":"string","description":"Optional WORLD-time window start: facts still holding after it."},
                      "until":{"type":"string","description":"Optional WORLD-time window end: facts that had started by it."},
                      "limit":{"type":"integer","description":"How many facts to return (default 80, max 300); the reply says when it is cut."},
                      "at":{"type":"string","description":"Optional as-of moment on the WORLD axis (YYYY, YYYY-MM, YYYY-MM-DD, or a zoned time). Only facts valid at that moment are returned. Omit for the full history."},
                      "as_of":{"type":"string","description":"Optional RECORD-time moment (YYYY-MM-DD or RFC3339): the facts as the knowledge base held them at that moment, before later corrections, retractions and merges. Use for 'what did we think / know / have on record as of <date>'. Independent of `at`: `at` is when something was true, `as_of` is when we believed it. Omit for today's understanding."},
                      "before":{"type":"string","description":"Optional RECORD-time instant copied exactly from a changes event: the facts as the knowledge base held them strictly before that change landed. Use it for 'before <correction / memo> arrived' \u2014 paste the event's timestamp, do not compute an earlier as_of yourself. Overrides as_of."}
                    },"required":["entity_id"]}}},
                  {"type":"function","function":{
                    "name":"paths_between",
                    "description":"How two entities are connected in the knowledge graph: the chains of facts that join them, up to 3 hops, shortest first. THE tool for 'what is the relation between A and B', 'how is X linked to Y', 'who connects A and B'. Both ends take an entity name or an id; with a name the server picks the best match and says which. Pass `at` to require every edge to hold at that moment (world time), `as_of` to read the base as recorded then. Each edge comes with its validity range.",
                    "parameters":{"type":"object","properties":{
                      "from":{"type":"string","description":"One end: entity name, or an id from find_entities."},
                      "to":{"type":"string","description":"The other end: entity name, or an id."},
                      "max_hops":{"type":"integer","description":"Longest chain to consider, 1-3 (default 3)."},
                      "at":{"type":"string","description":"Optional WORLD-time moment (YYYY, YYYY-MM, YYYY-MM-DD): every edge on a path must hold then."},
                      "as_of":{"type":"string","description":"Optional RECORD-time moment: the paths as the base held them then."}
                    },"required":["from","to"]}}},
                  {"type":"function","function":{
                    "name":"neighbors",
                    "description":"The entities linked to one entity, grouped by predicate, one hop. Use it to see what surrounds an entity before deciding where to look next (each further hop is another call); narrow with `predicate` (e.g. 'founder', 'employee') or `object_type` (e.g. 'Person'). Attribute values are not neighbors; entity_facts has them.",
                    "parameters":{"type":"object","properties":{
                      "entity":{"type":"string","description":"Entity name, or an id from find_entities."},
                      "predicate":{"type":"string","description":"Optional: only relations whose name contains this."},
                      "object_type":{"type":"string","description":"Optional: only neighbors of this type (name contains)."},
                      "at":{"type":"string","description":"Optional WORLD-time moment: only links valid then."},
                      "as_of":{"type":"string","description":"Optional RECORD-time moment."},
                      "limit":{"type":"integer","description":"How many to return (default 40, max 300)."}
                    },"required":["entity"]}}},
                  {"type":"function","function":{
                    "name":"timeline",
                    "description":"One entity's dated facts in world-time order: THE tool for 'the timeline of X', 'the history of X', 'what happened to X between <year> and <year>'. Only facts with a stated date appear; narrow with `since` / `until` (world time) or `predicate`.",
                    "parameters":{"type":"object","properties":{
                      "entity":{"type":"string","description":"Entity name, or an id from find_entities."},
                      "since":{"type":"string","description":"Optional start of the window (YYYY, YYYY-MM, YYYY-MM-DD)."},
                      "until":{"type":"string","description":"Optional end of the window."},
                      "predicate":{"type":"string","description":"Optional: only relations whose name contains this."},
                      "as_of":{"type":"string","description":"Optional RECORD-time moment: the timeline as the base held it then."},
                      "limit":{"type":"integer","description":"How many to return (default 60, max 300)."}
                    },"required":["entity"]}}},
                  {"type":"function","function":{
                    "name":"list_rules",
                    "description":"The business rules this base runs: what each one concludes and the exact conditions it tests, thresholds included. A rule is written by a person, and its conclusions are already in the graph \u2014 read the rule to explain WHY something was concluded, or to answer \\"what counts as X here\\". Do not re-implement a rule's comparison yourself; ask entity_facts or rule_matches for what it actually concluded.",
                    "parameters":{"type":"object","properties":{}}}},
                  {"type":"function","function":{
                    "name":"rule_matches",
                    "description":"Which entities a business rule currently marks, with the readings that made each one true. Use it for \\"which wells are gas-bearing\\" style questions \u2014 one call instead of checking every entity. Get the rule id from list_rules.",
                    "parameters":{"type":"object","properties":{
                      "rule_id":{"type":"string","description":"Rule id (uuid) from list_rules."},
                      "limit":{"type":"integer","description":"How many to return (default 50, max 200)."}
                    },"required":["rule_id"]}}},
                  {"type":"function","function":{
                    "name":"changes",
                    "description":"What the graph LEARNED or REVISED in a window of record time \u2014 the belief axis. Answers \\"what changed since X\\", \\"what did we get wrong\\", \\"what is new this quarter\\", and needs no entity, so use it when the question names a period rather than a subject. Events: asserted (new claim), corrected (a claim replaced by a revised one), rejected (a claim withdrawn), merged (folded into another claim) \u2014 each with the document it came from. NOT the same axis as entity_facts(at): that asks \\"what was true on date D\\"; this asks \\"what did we change our mind about between D1 and D2\\". A fact about 2019 can be recorded in 2026 \u2014 this windows on when we recorded it. Each event starts with its exact UTC RFC3339 record timestamp, including fractional seconds. For 'before a correction arrived', find that event here and pass its timestamp, exactly as printed, to entity_facts as `before`. Keep at for the world date asked about.",
                    "parameters":{"type":"object","properties":{
                      "since":{"type":"string","description":"Start of the window (YYYY, YYYY-MM or YYYY-MM-DD), inclusive \u2014 a year or month means its first day."},
                      "until":{"type":"string","description":"End of the window (YYYY, YYYY-MM or YYYY-MM-DD), inclusive of that whole day, month or year. Omit for 'up to now'."},
                      "entity_id":{"type":"string","description":"Optional entity id from find_entities, to narrow the window to changes touching that one entity."},
                      "kinds":{"type":"array","items":{"type":"string","enum":["asserted","corrected","rejected","merged"]},"description":"Optional filter. A freshly ingested corpus is nearly all 'asserted'; pass [\\"corrected\\", \\"rejected\\"] to isolate the places we actually changed our mind."}
                    },"required":["since"]}}}
                ]""");
    }

    /**
     * 完整工具表（chat.rs tools_schema）：基础 + 挂了数据源时的 query_data + 可写时的
     * remember。query_data 是问数的安全边界入列条件；remember 跟人走（viewer 纯只读）。
     */
    public JsonNode toolsSchema(boolean canWrite, List<String> dataSourceNames) {
        List<JsonNode> tools = new ArrayList<>();
        baseTools().forEach(tools::add);
        if (!dataSourceNames.isEmpty()) {
            String description = "Run a read-only SQL query against a mounted database. "
                    + "Available sources: " + String.join(", ", dataSourceNames) + ". "
                    + "Search the source's schema document first (search_chunks) if unsure "
                    + "of tables/columns. Only a single SELECT/WITH statement is allowed; a "
                    + "LIMIT is enforced server-side; results come back as JSON lines. If the "
                    + "query errors, fix the SQL and retry once.";
            ObjectNode queryData = frames.mapper.createObjectNode();
            queryData.put("type", "function");
            ObjectNode fn = queryData.putObject("function");
            fn.put("name", "query_data");
            fn.put("description", description);
            ObjectNode params = fn.putObject("parameters");
            params.put("type", "object");
            ObjectNode props = params.putObject("properties");
            props.putObject("data_source").put("type", "string")
                    .put("description", "Name of the mounted data source to query.");
            props.putObject("sql").put("type", "string")
                    .put("description", "One SELECT/WITH statement in the source's own SQL "
                            + "dialect (PostgreSQL, MySQL, ClickHouse, Trino, Databricks or "
                            + "Snowflake; the schema document names the engine).");
            props.putObject("purpose").put("type", "string")
                    .put("description", "One short phrase: what this query answers "
                            + "(shown to the user).");
            params.putArray("required").add("data_source").add("sql");
            tools.add(queryData);
        }
        if (canWrite && REMEMBER_ENABLED) {
            tools.add(frames.parse("""
                    {"type":"function","function":{
                      "name":"remember",
                      "description":"Record one memory episode into the knowledge base's temporal memory. Use ONLY when the user explicitly asks to remember/record something, or clearly states a decision or fact to keep. The sentence is stored immediately; facts extracted from it are PROPOSED and shown to the user for confirmation before they enter the knowledge graph. Never claim a fact was added to the graph. Do not use for casual conversation.",
                      "parameters":{"type":"object","properties":{
                        "text":{"type":"string","description":"The episode to remember, one self-contained statement (who/what, with names spelled out)."},
                        "occurred_at":{"type":"string","description":"Optional date the stated fact took effect (YYYY, YYYY-MM, YYYY-MM-DD or RFC3339). Omit to use today."}
                      },"required":["text"]}}}"""));
        }
        return frames.mapper.valueToTree(tools);
    }

    /**
     * 执行之前先看这次调用说清楚了没有（chat.rs check_call 统一参数闸）。
     *
     * <p><b>两种「没说清」以前都会安静地变成一次正常调用。</b>一是参数解析不出来（模型撞上
     * token 上限时 arguments 半路断掉）；二是必填参数干脆没给。两种都不该猜——回落产出的
     * 是一个看起来没问题的错误答案，那比报错坏得多。判据直接取自工具表里的
     * {@code required}：加一个必填参数这里自动跟上。
     *
     * <p>拒绝时回 (message, step)：step 是界面那一步（#942 按字段说：哪个参数、没给还是
     * 给错了；整个解析不出来的没有 param）。
     */
    public CheckResult checkCall(JsonNode tools, String name, String rawArgs) {
        JsonNode args;
        try {
            args = frames.mapper.readTree(rawArgs);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            args = null;
        }
        if (args == null || !args.isObject()) {
            return CheckResult.refuse(
                    "The arguments for " + name + " were not valid JSON, so the call was not run. "
                            + "They were probably cut off. Call it again with complete arguments.",
                    ToolStep.of(frames, "tool", name, "bad arguments").status("invalid").json());
        }
        JsonNode function = null;
        for (JsonNode t : tools) {
            if (t.path("function").path("name").asText("").equals(name)) {
                function = t.path("function");
                break;
            }
        }
        for (JsonNode keyNode : function == null ? frames.mapper.createArrayNode()
                : function.path("parameters").path("required")) {
            String key = keyNode.asText();
            JsonNode value = args.get(key);
            boolean missing = value == null || value.isNull()
                    || (value.isTextual() && value.asText().trim().isEmpty());
            if (missing) {
                return CheckResult.refuse(
                        name + " needs `" + key + "`, and it was missing or empty, so the call "
                                + "was not run. Call it again with `" + key + "` set.",
                        ToolStep.of(frames, "tool", name, "missing " + key)
                                .missing(key).json());
            }
            // schema 里写了 format: uuid 的参数，格式也在这一关挡——编出来的 id 到了
            // 工具里只能回「本库没有这篇文档」，模型会把它读成「库里真的没有」而放弃
            boolean isUuid = "uuid".equals(function.path("parameters").path("properties")
                    .path(key).path("format").asText(null));
            if (isUuid && (!value.isTextual() || !isUuid(value.asText().trim()))) {
                return CheckResult.refuse(
                        name + " needs `" + key + "` to be a uuid returned by another tool, and "
                                + "it was not, so the call was not run. Look the id up first, "
                                + "then call it again.",
                        ToolStep.of(frames, "tool", name, "invalid " + key)
                                .invalid(key).json());
            }
            boolean isString = "string".equals(function.path("parameters").path("properties")
                    .path(key).path("type").asText(null));
            if (isString && !value.isTextual()) {
                return CheckResult.refuse(
                        name + " needs `" + key + "`, which must be a string, so the call was "
                                + "not run. Call it again with `" + key + "` set to a string.",
                        ToolStep.of(frames, "tool", name, "invalid " + key)
                                .invalid(key).json());
            }
        }
        return CheckResult.ok(args);
    }

    static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            // Rust uuid::Uuid::parse_str 也收 32 位简单形态（无连字符）
            return s.length() == 32 && s.chars().allMatch(
                    c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'));
        }
    }

    /** check_call 的产物：通过（原样参数，一个不少）或拒绝（给模型的话 + 界面那一步）。 */
    public record CheckResult(JsonNode args, String refuseMessage, JsonNode refuseStep) {

        static CheckResult ok(JsonNode args) {
            return new CheckResult(args, null, null);
        }

        static CheckResult refuse(String message, JsonNode step) {
            return new CheckResult(null, message, step);
        }

        public boolean refused() {
            return refuseMessage != null;
        }
    }
}
