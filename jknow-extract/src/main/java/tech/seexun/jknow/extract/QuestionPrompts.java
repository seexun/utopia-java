package tech.seexun.jknow.extract;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tech.seexun.jknow.extract.OntologyAgentPrompts.Glossary;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;

/**
 * 给没有能力问题的库提问题（extract/question_agent.rs 273 行逐语义，0061 决定 1）：从开放
 * 图谱说得最多的东西——陈述最多的形状、最多的类别词、连接最多的实体——出发，让模型写出
 * 这个库该答得上来的问题。提出来的是 {@code proposed}，人接受、改或拒；代理不替人决定库
 * 是干什么的。
 */
public final class QuestionPrompts {

    private QuestionPrompts() {
    }

    /** 一条说得多的形状；{@code property} 是对齐绑上的属性键（绑上了才有）。 */
    public record TopSignature(String phrase, String subjectClass, String objectClass,
                               boolean objectIsValue, long statementCount, String property,
                               String example) {
    }

    /** 说得最多的类别词。 */
    public record TopKindWord(String kindWord, long count) {
    }

    /** 连接最多的实体。 */
    public record TopEntity(String name, String klass, long degree) {
    }

    /** 一条提出来的问题：原话，加上答它要走的类与属性（都是现有的键；模型编的键丢掉）。 */
    public record ProposedQuestion(String question, List<String> classes,
                                   List<String> properties) {
    }

    /**
     * question_agent.rs:46-62 SYSTEM 逐字（续行折掉换行与前导空白、续行处留一个空格）。
     */
    public static final String SYSTEM = "You write competency questions for a knowledge base built from documents. "
            + "A competency question is a question a person would actually ask this base and expect it to answer "
            + "from its graph; the ontology (its classes and properties) is judged by whether such questions can be "
            + "answered. You are given what the documents say most: the most frequent statement shapes (a phrase "
            + "between a subject class and an object class or a value, with the property it binds to if any), the "
            + "most frequent kind words, and the most connected entities, plus the current ontology.\n"
            + "Rules:\n"
            + "- Write questions in the language of the documents, as a person would phrase them, specific enough "
            + "to have a checkable answer (name the kind of thing asked for; a concrete entity from the list may "
            + "be used when it makes the question natural).\n"
            + "- Prefer questions that need the graph to answer: several statements, or a join across two "
            + "properties. Do not ask what a single sentence answers.\n"
            + "- Each question lists the classes and properties an answer must traverse, as keys from the "
            + "ontology; leave the lists empty when the ontology has no element for it yet (that gap is the point).\n"
            + "- No duplicates, no yes/no questions, at most the number asked for.\n"
            + "Answer with one JSON object and nothing else:\n"
            + "{\"q\":[{\"question\":\"...\",\"classes\":[\"class_key\"],\"properties\":[\"property_key\"]}]}";

    /** question_agent.rs:64-150 build_question_messages 逐字拼装。 */
    public static List<ChatMessage> buildQuestionMessages(List<TopSignature> signatures,
                                                          List<TopKindWord> kindWords,
                                                          List<TopEntity> entities,
                                                          Glossary glossary, List<String> existing,
                                                          int want) {
        StringBuilder user = new StringBuilder();
        user.append("Ontology classes:\n");
        if (glossary.classes().isEmpty()) {
            user.append("  (none)\n");
        }
        for (String[] c : glossary.classes()) {
            user.append("- ").append(c[0]).append(" · ").append(c[1]);
            if (c.length > 2 && c[2] != null && !c[2].isEmpty()) {
                user.append(" · ").append(c[2]);
            }
            user.append('\n');
        }
        user.append("Ontology properties:\n");
        if (glossary.properties().isEmpty()) {
            user.append("  (none)\n");
        }
        for (OntologyAgentPrompts.GlossaryProperty p : glossary.properties()) {
            user.append("- ").append(p.key()).append(" · ").append(p.label()).append(" · ")
                    .append(p.kind());
            if (!p.domains().isEmpty() || !p.ranges().isEmpty()) {
                user.append(" · ").append(String.join("|", p.domains())).append(" → ")
                        .append(String.join("|", p.ranges()));
            }
            if (p.description() != null && !p.description().isEmpty()) {
                user.append(" · ").append(p.description());
            }
            user.append('\n');
        }
        user.append("\nMost frequent statement shapes:\n");
        for (TopSignature s : signatures) {
            user.append("- ").append(s.subjectClass() == null ? "?" : s.subjectClass())
                    .append(" — \"").append(s.phrase()).append("\" → ");
            if (s.objectIsValue()) {
                user.append("value");
            } else {
                user.append(s.objectClass() == null ? "?" : s.objectClass());
            }
            user.append(" · ").append(s.statementCount()).append(" statements");
            if (s.property() != null) {
                user.append(" · bound to ").append(s.property());
            } else {
                user.append(" · not bound to any property");
            }
            if (s.example() != null) {
                user.append(" · e.g. ").append(s.example());
            }
            user.append('\n');
        }
        user.append("\nMost frequent kind words:\n");
        for (TopKindWord k : kindWords) {
            user.append("- ").append(k.kindWord()).append(" · ").append(k.count())
                    .append(" entities\n");
        }
        user.append("\nMost connected entities:\n");
        for (TopEntity e : entities) {
            user.append("- ").append(e.name()).append(" (")
                    .append(e.klass() == null ? "?" : e.klass()).append(") · ")
                    .append(e.degree()).append(" statements\n");
        }
        if (!existing.isEmpty()) {
            user.append("\nQuestions the base already has (do not repeat them):\n");
            for (String q : existing) {
                user.append("- ").append(q).append('\n');
            }
        }
        user.append("\nWrite up to ").append(want).append(" competency questions.");
        return List.of(ChatMessage.system(SYSTEM), new ChatMessage("user", user.toString()));
    }

    /**
     * 读回复（parse_question_response）。{@code classKeys}/{@code propertyKeys} 是本体现在
     * 的键：答案里不存在的键丢掉（<b>不算坏项</b>——模型常把它想要的属性写成键，那正是
     * 缺口）。返回 (问题, 坏项数)。
     */
    public static QuestionParse parseQuestionResponse(String raw, Set<String> classKeys,
                                                      Set<String> propertyKeys) throws Exception {
        JsonNode v = Align.parseValue(raw);
        List<ProposedQuestion> out = new ArrayList<>();
        int[] malformed = {0};
        JsonNode q = v.get("q");
        if (q != null && !q.isArray() && !q.isNull()) {
            throw new IllegalStateException("`q` is not a list");
        }
        if (q != null) {
            for (JsonNode it : q) {
                if (!it.isObject()) {
                    malformed[0]++;
                    continue;
                }
                JsonNode obj = it;
                String question = obj.hasNonNull("question")
                        ? obj.get("question").asText().trim().replaceAll("\n+$", "") : "";
                // 短于 8 个**字符**（Rust chars().count() 的对等是码点数）
                if (question.codePoints().count() < 8) {
                    malformed[0]++;
                    continue;
                }
                boolean dup = out.stream().anyMatch(
                        x -> x.question().equalsIgnoreCase(question));
                if (dup) {
                    continue;
                }
                out.add(new ProposedQuestion(question, keys(obj.get("classes"), classKeys),
                        keys(obj.get("properties"), propertyKeys)));
            }
        }
        return new QuestionParse(out, malformed[0]);
    }

    /** (问题列表, 坏项数)。 */
    public record QuestionParse(List<ProposedQuestion> questions, int malformed) {
    }

    private static List<String> keys(JsonNode v, Set<String> allowed) {
        List<String> out = new ArrayList<>();
        if (v == null || !v.isArray()) {
            return out;
        }
        for (JsonNode x : v) {
            if (!x.isTextual()) {
                continue;
            }
            String k = x.asText().trim();
            if (allowed.contains(k)) {
                out.add(k);
            }
        }
        return out;
    }
}
