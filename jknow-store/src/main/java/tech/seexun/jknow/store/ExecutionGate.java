package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行闸门纯函数（execution_gate.rs:18-108 逐字移植，0027/#357）：一次自动合并能不能
 * 不经人就落地，看它<b>撤得回什么</b>，不只看把握。
 *
 * <p>合并在图里撤得回：revertMerge 把事实搬回去，0019 之后连合并前的图都读得出。
 * 撤不回的是它活着那几天离开图的东西——推理据它推出的派生、据它开出的违规与告警、
 * 进了导出的三元组、带引用给出去的答案。撤销还原的是图，不是图被读进去的那个世界。
 *
 * <p>这里问的只有一件事：<b>这一次合并会不会立刻把什么送出图外</b>。会，就留给人，
 * 把握再高也不动手。只管合并：分开撤起来不费什么，下一次合并就是撤销。
 *
 * <p>{@link #hold} 是纯函数，取数在 {@link ExecutionGateService#impactOf}。
 * 攒批裁决器与治理 agent 走同一个闸门（M4 接线）；路线图上那道「执行闸门」
 * （检查 agent 的调用）以后也接这一个，不长第二个。
 */
public final class ExecutionGate {

    private ExecutionGate() {
    }

    /** execution_gate.rs:19-29 一次合并会立刻牵动的东西。 */
    public static final class Impact {

        /**
         * 两边各持一条同一个 functional 谓词的事实、指向不同的实体，合了之后按时间也
         * 排不开：functional 违规，一致性检查下一次跑就会开出来——那是离开图的第一站。
         * 记谓词标签；inverse_functional 对称地算。
         */
        public final List<String> contradictions = new ArrayList<>();

        /** 任一边参与的、还成立的派生事实数：合并改了前提，这些派生会被重写。 */
        public long derived;

        /** 任一边在对话里被认过的回答数：这个实体是人在问的，错合并就进下一个答案。 */
        public long answered;

        @Override
        public String toString() {
            return "Impact{contradictions=" + contradictions + ", derived=" + derived
                    + ", answered=" + answered + "}";
        }
    }

    /**
     * execution_gate.rs:33-61 留给人的理由。toString 写成 {@code kind value}，直接接在
     * {@code escalate_impact|} 后面，界面按 kind 措辞。
     */
    public record Hold(String kind, String value) {

        /** Hold::Contradiction。 */
        public static Hold contradiction(String predicate) {
            return new Hold("contradiction", predicate);
        }

        /** Hold::Derived。 */
        public static Hold derived(long n) {
            return new Hold("derived", Long.toString(n));
        }

        /** Hold::Answered。 */
        public static Hold answered(long n) {
            return new Hold("answered", Long.toString(n));
        }

        @Override
        public String toString() {
            return kind + " " + value;
        }

        /** execution_gate.rs:52-60 explain：人在 Agent 队列里看到 agent 为什么没动手。 */
        public String explain() {
            return switch (kind) {
                case "contradiction" -> "merging would put two \"" + value + "\" facts on one entity";
                case "derived" -> value + " derived facts rest on one side";
                default -> "one side was named in " + value + " answers";
            };
        }
    }

    /**
     * execution_gate.rs:97-108 {@code hold}：这一次合并该不该留给人。矛盾最先说——它会
     * 立刻开出违规；其次派生，其次答案。什么都不牵动就是 null：把握够就照旧自动。
     */
    public static Hold hold(Impact impact) {
        if (!impact.contradictions.isEmpty()) {
            return Hold.contradiction(impact.contradictions.get(0));
        }
        if (impact.derived > 0) {
            return Hold.derived(impact.derived);
        }
        if (impact.answered > 0) {
            return Hold.answered(impact.answered);
        }
        return null;
    }

    /**
     * execution_gate.rs:64-92 {@code describe}：给第二层的工具看的一段（0028）——
     * 合领会牵动什么，一行一件；什么都不牵动也说出来。
     */
    public static String describe(Impact impact) {
        List<String> lines = new ArrayList<>();
        for (String p : impact.contradictions) {
            lines.add("- merging would put two \"" + p + "\" facts on one entity; \"" + p
                    + "\" allows one value");
        }
        if (impact.derived > 0) {
            lines.add("- " + impact.derived + " derived fact(s) rest on one side and would be rewritten");
        }
        if (impact.answered > 0) {
            lines.add("- one side was named in " + impact.answered + " chat answer(s)");
        }
        return lines.isEmpty()
                ? "(merging would touch nothing outside the graph: no one-value relation clashes,"
                        + " no derived facts, no answers named either side)"
                : String.join("\n", lines);
    }
}
