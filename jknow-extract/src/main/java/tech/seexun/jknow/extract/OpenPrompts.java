package tech.seexun.jknow.extract;

import java.util.List;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 开放抽取（0044 第 1 刀，#729）的提示词侧（open.rs:106-314 逐语义）：模型读一块，用
 * <b>文档自己的话</b>写下它陈述了什么。
 *
 * <p>提示词里<b>没有类型清单、没有关系清单、没有文档日期</b>：关系短语照原文写，时间照
 * 原文的字抄，不算日期、不归一。本体是之后套上去的视图，不是抽取时的筛子。提示词里唯一
 * 逐块变化的是正文、开头与已知实体，所以系统消息是一个常量——前缀缓存能命中的就是它。
 *
 * <p>陈述指东西用名字不按编号、引文每条自己抄一遍：编号版在一块密集的财报上实测
 * deepseek-v4-flash 把 id 串了位（同一块两次回复编号还不一致）；名字 + 逐条引文版
 * 333 条里 0 条编造。多花的 token 买的是不串位。
 */
public final class OpenPrompts {

    private OpenPrompts() {
    }

    /** lib.rs:23-27 KnownEntity：回复域内的既有实体引用；数据库 UUID 永不进提示词。 */
    public record KnownEntity(String handle, String typeKey, String name) {
    }

    /** lib.rs:31 OPENING_BUDGET_CHARS：文件开头进提示词的字符预算（1500）。 */
    public static final int OPENING_BUDGET_CHARS = 1500;

    /** lib.rs:60 KNOWN_BUDGET_CHARS：已知实体清单的字符预算（1200）——超出截断，保留先出现的。 */
    public static final int KNOWN_BUDGET_CHARS = 1200;

    /**
     * lib.rs:37-54 opening_block：文件开头排版成提示词里的一段。开头为空（或只有空白）
     * 返回空串；「这一块就是开头本身」由调用方判断（那时传 null）。按字符截：不会截断
     * 一个字符，但会截在词中间。
     */
    public static String openingBlock(String opening) {
        if (opening == null || opening.strip().isEmpty()) {
            return "";
        }
        String text = opening.strip();
        String cut = text.codePoints().limit(OPENING_BUDGET_CHARS)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        String more = cut.codePointCount(0, cut.length()) < text.codePointCount(0, text.length())
                ? " …" : "";
        return "\nOpening of this document, for context only (do not extract facts from it; they are "
                + "extracted from that part separately). Use it to know what the text below belongs to — "
                + "which agreement, company or event it concerns, who the parties are, and the date it "
                + "takes effect — so that facts in the text below attach to the right entity and carry "
                + "the right dates:\n\"\"\"\n" + cut + more + "\n\"\"\"\n";
    }

    /**
     * 系统消息（open.rs:108-209 逐字——前缀缓存友好的常量，规则 1–8 是原型上测过的措辞，
     * 例子是中性的，不出自任何测量语料）。规则 2 结尾的 {@code {TABLE}} 占位由
     * {@link #systemFor} 按段落有没有表格替换。
     */
    public static final String OPEN_SYSTEM = """
            You read one passage of a document and write down what it states, in the passage's own words. Output one JSON object and nothing else, shaped like this:
            
            {"e": [["name or description", "kind word", 1]],
            "s": [["the sentence that states it, verbatim", "subject name", "relation phrase as written", "object name", null, {"to": "name of a listed thing", "amount": "$2 million"}, "when it holds or happened, as written", "when it ended, as written"]],
            "n": [["name as listed in e", "another name", "the sentence that uses it, verbatim"]],
            "t": [["now", "label as written", "the date as written", {"y": 2011, "m": 3, "d": 4}, null]]}
            
            1. "e" lists the things the passage talks about, one entry each: [name, kind, named]. Every thing a statement refers to is listed here once, under exactly the name the statements use. named = 1 for a proper name or a fixed term: the name of one particular person, organization, product, place, document, law or event, or a term that means the same thing in any document (a disease, a drug, an industry, a product category, an indicator), written as the passage writes it, without citation words ("the Harbor Treaty signed on May 3, 1998" is named "Harbor Treaty"). named = 0 for a role or a generic phrase whose referent this passage decides, however particular it is here: "the company", "the enterprises visited", "patients", "各部门", "the northern wing" are described things, named by the words that say what they are. kind is what the thing is, in two or three words, as the passage says it. A name never carries a figure: "about 40 hectares of farmland" is the thing "farmland" with a statement "area" = "about 40 hectares". List each thing once.
            2. "s" lists the statements, one entry each: [quote, subject, phrase, object, value, qualifiers, when, ended]. quote comes first: the sentence of the passage that states it, copied verbatim, one sentence, and when two sentences are needed, the one that carries the link; the rest of the entry is written from that sentence. subject and object are names, written exactly as listed in "e" or in the list of things already recorded, with the same spelling every time. Exactly one of object and value is set; the other is null.
            A statement with an object links two things. phrase is how the passage says the link, lowercase, in the passage's own words; do not translate it into any vocabulary of your own. It is the verb with the words that belong to it, so that subject —phrase→ object reads as a sentence on its own ("acquired", "was designed by", "is the captain of", "went into partnership with", "细化解读"), never a bare verb cut from a longer verb phrase ("went", "细化"). Several verbs sharing one object make one statement whose phrase carries them all ("designed and built"); one verb with several objects makes one statement per object. The object is what the verb acts on, and a described thing that is the subject or the object is listed like any other ("the coating should not be used on surfaces exposed to seawater" gives coating —should not be used on→ surfaces exposed to seawater, a described thing); words that say where, how, why, with what or for whom go in qualifiers under the passage's own role word. A named thing mentioned in a qualifier is still listed and the qualifier names it; a generic phrase that appears in a qualifier alone is words there, not a listed thing: "各部门要走进学校、社区等基层单位开展宣传活动" gives 各部门 —开展→ 宣传活动 with {"走进": "学校、社区等基层单位"}, and neither 学校 nor 社区 is listed.
            A statement with a value is what the passage says about one thing by itself: a figure with its units, a percentage, an amount, a count, a title, a status. phrase is how the passage says what is measured or stated ("area", "was completed", "joined", "占地面积"); value is the literal as written. Wording that names or describes something is not a value: that something goes in "e" and is linked by a statement with an object. "1,200 beekeepers joined" is the thing "beekeepers" with "joined" = "1,200". Write every figure the passage states.{TABLE}
            A list is one statement per member, and so is a subject or object that joins several things ("the city and the county funded the bridge" is two statements).
            3. Nothing in a sentence is dropped. When more than two things take part, or the link carries a figure, a title, a condition or a comparison, put the main pair in subject and object and the rest in qualifiers: an object keyed by a word or two for each part's role, whose values are the name of a listed thing or the words as written. "the city council awarded the paving contract to Brightway Builders for $2 million" gives city council —awarded→ paving contract with {"to": "Brightway Builders", "amount": "$2 million"}; "Jane Doe is interim headmaster of Hillside School" gives Jane Doe —is headmaster of→ Hillside School with {"title": "interim headmaster"}. A statement with a value takes qualifiers the same way: "the plant cut water use by 12% compared to 2019" gives the plant "cut water use" = "12%" with {"compared to": "2019"}. A statement the passage does not assert as holding but requires, plans, expects, forecasts or makes conditional carries a qualifier keyed "mood" whose value is the passage's own words for that ("要", "should", "will", "is expected to", "if the merger closes"); the phrase stays as written. A verb that reports (announced, said, revealed) is not a mood: what was announced is stated. qualifiers is null when there are none.
            4. When a description has another thing folded into it, also write the statement that unfolds it: "hospitals accredited by the Joint Commission" also gives Joint Commission —accredited→ hospitals. Do not unfold a description that only names what it belongs to or is about: "the northern wing of the palace" and "the use of the coating" give no statement, the palace and the coating are part of the description.
            5. when is the words that say when the statement holds, happened or began, and ended is the words that say when it stopped, each copied exactly as written ("March 4, 2011", "去年冬天", "by the end of next season"). Never compute, convert or normalise a date, and never write one the passage does not. Each is null when the passage gives none.
            Words that say when go in when or ended and nowhere else, never in the phrase, the object, the value or a qualifier; also when they count from now or from another event ("三个月后", "last week", "six months after the launch") and when the statement is planned. If that leaves no value, the value is the word for what happened ("发布"). A statement with no time of its own, under a heading that names one time and nothing else ("## 2025年第三季度"), takes the heading's words as when.
            6. Every "s" and "n" entry carries its own quote, copied verbatim from the passage: the first slot of an "s" entry, the last slot of an "n" entry.
            7. "n" lists other names, one entry each: [name as listed, other name, quote] — a short form, a former name, a spelling in another script that this passage uses for a thing in "e" or a thing already recorded. Only names actually written in the passage; never a pronoun or a description.
            8. "t" lists the dates the passage states about the document itself, one entry each: [kind, label, words, from, to]. kind is "now" for the moment the text speaks from (a dateline, the date a report was submitted or published, a meeting's date), "date" for any other labelled date (a cut-off, an effective date, a deadline), "period" for a named period with stated bounds. label and words are copied as written; from and to transcribe the numbers the words state (y, m, d), to only for a period. A date inside a sentence about something that happened is that statement's when and is not listed. [] when there are none.
            9. State nothing the passage does not state, and state each thing once: with a value or with an object, not both. The Document line, the opening of the document and the list of things already recorded only say where the passage comes from; write nothing about them. If the passage states nothing, output {"e":[],"s":[],"n":[],"t":[]}.
            
            Example. The passage "The city council awarded the paving contract to Brightway Builders for $2 million on March 4, 2011." gives:
            {"e": [["city council", "council", 0], ["paving contract", "contract", 0], ["Brightway Builders", "builders", 1]],
            "s": [["The city council awarded the paving contract to Brightway Builders for $2 million on March 4, 2011.", "city council", "awarded", "paving contract", null, {"to": "Brightway Builders", "amount": "$2 million"}, "March 4, 2011", null]],
            "n": [],
            "t": []}""";

    /**
     * 表格的读法（open.rs:213-222 逐字），只在段落里有表格时附加进系统消息：没有表格的
     * 段落用不上它，而它占系统消息的一成不到。
     */
    public static final String OPEN_TABLE_RULES = """
            In a table, a cell is a statement about its row's thing whose phrase is the column heading. When the column heading names one time (a date, a quarter, a period), it is when instead, the statement is about the thing the table's caption names (the company of a financial statement) and the phrase is the row label as written, path included ("Operating expenses › Research and development"); a heading that names a comparison between periods (a change from the quarter before, a change from a year ago) is not a time, it stays the phrase; a unit the caption or a heading gives ("In millions") is a qualifier on each such statement. Leave out cells whose column heading is not in the passage.""";

    /**
     * open.rs:226-240 has_table：这一段里有没有表格——Markdown 竖线、HTML 单元格、制表符，
     * 或一行里用成串空格隔开的三栏以上。宁可多认：认错了只是多带一段规则。
     */
    static boolean hasTable(String passage) {
        String lower = passage.toLowerCase(java.util.Locale.ROOT);
        if (passage.contains("|") || passage.contains("\t") || lower.contains("<td")
                || lower.contains("<th") || lower.contains("<tr")) {
            return true;
        }
        for (String l : passage.split("\n", -1)) {
            String trimmed = l.strip();
            int cols = 0;
            for (String col : trimmed.split("  ", -1)) {
                if (!col.strip().isEmpty()) {
                    cols++;
                }
            }
            if (cols >= 3) {
                return true;
            }
        }
        return false;
    }

    /** open.rs:243-250 system_for：规则不变，只有表格那几句按段落带或不带。 */
    public static String systemFor(String passage) {
        String table = hasTable(passage) ? " " + OPEN_TABLE_RULES : "";
        return OPEN_SYSTEM.replace("{TABLE}", table);
    }

    /**
     * open.rs:256-278 build_open_messages：两条消息 = 常量系统消息 + {@code Document:} /
     * 开头 / 已知实体 / {@code Passage:}。已知实体紧挨着正文（服从性靠位置）。没有文档
     * 日期、没有类型与关系清单、没有属性。
     */
    public static List<ChatMessage> buildOpenMessages(String filename, List<KnownEntity> known,
                                                      String opening, String chunkText) {
        String user = "Document: " + filename + "\n" + openingBlock(opening) + knownBlock(known)
                + "\nPassage:\n" + chunkText;
        return List.of(ChatMessage.system(systemFor(chunkText)), ChatMessage.user(user));
    }

    /**
     * open.rs:284-314 known_block：「本文档已经认下的实体」的开放版：{@code - <name> (<kind>)}，
     * 句柄不给模型看——这条路陈述里写的是名字，模型照这里的拼写写，对回哪个实体由服务端
     * 拿名字去查。预算截断保留先出现的（中文商业文本先出全称、主角先出场）。
     */
    public static String knownBlock(List<KnownEntity> known) {
        if (known == null || known.isEmpty()) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        int used = 0;
        for (KnownEntity entity : known) {
            used += entity.typeKey().codePointCount(0, entity.typeKey().length())
                    + entity.name().codePointCount(0, entity.name().length()) + 6;
            if (used > KNOWN_BUDGET_CHARS) {
                break;
            }
            String kind = entity.typeKey().strip();
            lines.add(kind.isEmpty() ? "- " + entity.name() : "- " + entity.name() + " (" + kind + ")");
        }
        if (lines.isEmpty()) {
            return "";
        }
        return "\nAlready recorded from earlier parts of this same document:\n" + String.join("\n", lines)
                + "\n\nWhen the passage refers to one of these, write its name exactly as it is listed here "
                + "and do not list it again in \"e\"; a shortened form the passage uses for it goes in "
                + "\"n\". If it is a different thing, list it as the passage names it; do not force it "
                + "onto this list.\n";
    }
}
