package tech.seexun.jknow.web.knowledge.governance;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tech.seexun.jknow.extract.ExtractLib.AdjudicationPair;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.entity.ReviewItem;

/**
 * 第二眼（governance.rs:454-503 {@code look_again} 的端口）——攒批判不定的对带工具
 * 再看一遍（0028）：同一个循环、同一份预算，能读两边的事实、引文、台账再定。
 *
 * <p><b>M4 任务 3 的形态</b>：裁决器（{@code adjudicate_entities}）在治理关着或第二层
 * 尚未落地时也走同一道口。本任务只落接口与「第二眼不可用」的缺省实现
 * （{@link #UNAVAILABLE}——回 Optional.empty()，调用方照「第二眼没跑成」的分支办：
 * 相似提的 same 上交 {@code escalate_unsure|second_look_unavailable}，不落地不进缓存，
 * #889）；<b>任务 4 的 GovernJob 落地真身后注册为 Spring bean 即自动接管</b>
 * （AdjudicateEntitiesJob 以 ObjectProvider 懒取——有 bean 用 bean、没有用缺省，
 * 沿 DerivedPoolProvider 的懒取先例），本接口与裁决器侧不再改。
 *
 * <p>实现侧注意（任务 4）：每次调用自造 run_id（Rust look_again 内部
 * {@code Uuid::now_v7()}）——一次裁决任务里的每一对各是一条 run；预算
 * （loop_calls_today / LOOP_DAILY_CALLS）与失败语义（看不成回 None，不拖累第一层）
 * 都在实现里，不在这个口上。
 */
public interface SecondLookPort {

    /**
     * governance.rs:454-475 {@code look_again}：带工具再看一对。
     *
     * @return 看完的看法（替换第一层的）；empty = 没跑成（预算用完、模型出错、
     *         第二层未配置）——照第一层的看法办，相似提的 same 例外（#889）
     */
    Optional<Look> lookAgain(UUID kbId, ChatClient client, LlmSettingsRow settings,
                             ReviewItem item, AdjudicationPair pair, Look earlier);

    /**
     * 对一对的一次看法（governance.rs:53-73 {@code Look}）：第一层给的，或第二层
     * 看完改过的。裁决器（治理关着时）也用它。
     */
    record Look(
            /** 合 (true) / 分 (false) / 不确定 (null)。 */
            Boolean same,
            float conf,
            String why,
            /** defer 留下的问题。 */
            String question,
            /** 第二层看了什么：[{tool, args, note}]。 */
            List<JsonNode> trace,
            /** 第二层花的模型调用。 */
            int calls) {

        /** governance.rs:66-73 {@code from_batch}：攒批那一眼的看法原样进第二层。 */
        public static Look fromBatch(Boolean same, float conf, String why) {
            return new Look(same, conf, why, null, List.of(), 0);
        }
    }

    /** 缺省实现（任务 3 期）：第二眼不可用——回 empty，走「没跑成」分支。 */
    SecondLookPort UNAVAILABLE = (kbId, client, settings, item, pair, earlier) -> Optional.empty();
}
