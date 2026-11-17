package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import tech.seexun.jknow.reason.Violation;

/**
 * 派生池的注入接缝（M3 任务 5 定义，任务 6 接真身）。
 *
 * <p><b>单一求解源不变量（m3-recon 附录第 8 条）</b>：Rust 里 reason 的 {@code detect()}
 * 与 materialize 的 {@code resolve()} 共用同一次求解——候选里有规则推出的关系边，被拦下的
 * 也在——<b>队列报的正是图拦下的</b>（0017 / 0047 决定 3）。任何实现必须与物化侧共用同一次
 * 求解（同一批候选、同一批拦截），两边各算一套的话，队列会跟图对不上。
 *
 * <p>任务 5 的默认实现是<b>空池</b>：{@code detect} 只做断言边部分（timed_edges 只有断言，
 * 派生分表天然隔离），第六类（派生撞断言）与 rules_disagree 恒为零。任务 6 落
 * {@code resolve()}（断言边 → check → 业务规则不动点）后把本接口换成真身：
 * {@code detect} 的取数/落库/清陈一侧不再动。
 *
 * <p>实现方须知（对齐 reasoning.rs:277-336 detect 的派生侧）：
 * <ul>
 *   <li>矛盾行的键与外键都必须回到 {@code facts} 的行——链上最后一条<b>断言</b>前提
 *       （每轮都不同的临时 id 不属于任何表）；整条前提链都推出来时该撞法不落行</li>
 *   <li>单谓词进队列上限 {@code MAX_CLASHES_PER_PREDICATE = 50}（0017 §1）：超出的只计数
 *       （{@link Pool#contradictionsCapped}），不为零说明根子在规则</li>
 *   <li>detail 写人读的话（axiom/rule/via_label/subject/predicate/object/区间/premises）
 *       ——派生没有落库、没有文本可查，名字在 detail 里补</li>
 *   <li>派生之间互撞的按规则对聚合（谓词对唯一键），example 取前三个三元组文本</li>
 * </ul>
 */
public interface DerivedPoolProvider {

    /** 一次求解产出的派生侧结果（断言边之外的部分）。 */
    Pool solve(UUID kbId);

    /**
     * @param contradictions       派生撞断言的行（Violation + detail），键=(被撞断言, 链上最后断言前提)
     * @param contradictionsCapped 撞上单谓词上限、没进队列的条数
     * @param ruleClashes          派生之间互撞的规则对（进 ontology_defects 的 rules_disagree）
     */
    record Pool(List<Contradiction> contradictions, int contradictionsCapped,
                List<RuleDisagreement> ruleClashes) {

        public static final Pool EMPTY = new Pool(List.of(), 0, List.of());
    }

    /** 一条派生撞断言：落库行（DERIVED_CONTRADICTION）+ detail（人读的三元组与证明）。 */
    record Contradiction(Violation violation, JsonNode detail) {
    }

    /**
     * 一对互撞的规则（同一对谓词上可能有几种撞法——functional 与 asymmetric 各撞各的，
     * 唯一键只到谓词对，几种撞法由落库侧合成一行 detail）。
     *
     * @param viaA    规则甲的谓词（标签由落库侧查 relation_types.label）
     * @param ruleA   规则甲的种类字面量（sub_property/transitive/symmetric/inverse/business）
     * @param viaB    规则乙的谓词
     * @param ruleB   规则乙的种类字面量
     * @param axiom   撞上的公理种类（functional/inverse_functional/asymmetric…）
     * @param pairs   这对规则互撞的条数
     * @param examples 前 3 个例句（每例两个「主语 · 谓词 · 宾语」三元组文本，名字由求解侧补）
     */
    record RuleDisagreement(UUID viaA, String ruleA, UUID viaB, String ruleB, String axiom,
                            int pairs, List<String[]> examples) {
    }

    /** 空池（任务 5 默认：只有断言边，第六类与 rules_disagree 恒为零）。 */
    DerivedPoolProvider EMPTY = kbId -> Pool.EMPTY;
}
