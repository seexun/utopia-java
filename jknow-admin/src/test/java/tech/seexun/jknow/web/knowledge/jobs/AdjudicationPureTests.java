package tech.seexun.jknow.web.knowledge.jobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.store.entity.Precedents;
import tech.seexun.jknow.store.entity.ReviewItem;
import tech.seexun.jknow.store.entity.ReviewItem.ReviewSide;

/**
 * 移植自 crates/utopia-server/src/adjudication.rs:523-565 内联 mod tests（3 例，断言
 * 不改写）——名字向量召回提的对（0041 第 2 刀）在攒批裁决器里的特判：
 * <b>相似提的 same 必走第二眼</b>，第二眼没跑成时不落地也不进缓存（#889）；
 * 同名家族（shared_name/ambiguous_name）不受影响，够线照旧自动落地。
 */
class AdjudicationPureTests {

    /** adjudication.rs:500-520 item()：名字向量召回的经典样例——「张伟」并进「财务部总监张伟」。 */
    private static ReviewItem item(String reason) {
        return new ReviewItem(UUID.randomUUID(), 0.78f, reason, "adjudicating",
                OffsetDateTime.now(), side("张伟"), side("财务部总监张伟"), null);
    }

    private static ReviewSide side(String name) {
        return new ReviewSide(UUID.randomUUID(), name, "person", "", null, 0L, List.of());
    }

    /// 名字向量提的对：批量说 same 再有把握也要第二眼；说 different / unsure 照旧
    @Test
    void a_similarity_proposed_same_always_gets_the_second_look() {
        Precedents p = Precedents.none();
        ReviewItem it = item("name_vector|0.78");
        assertTrue(AdjudicateEntitiesJob.needsSecondLook(it, p, Boolean.TRUE, 0.99f));
        assertTrue(AdjudicateEntitiesJob.needsSecondLook(it, p, Boolean.TRUE, 0.85f));
        assertFalse(AdjudicateEntitiesJob.needsSecondLook(it, p, Boolean.FALSE, 0.9f),
                "分开是安全方向，照旧落地");
        assertTrue(AdjudicateEntitiesJob.needsSecondLook(it, p, null, 0.5f), "没定的本来就要再看");
    }

    /// 第二眼没跑成时：相似提的 same 不落地也不进缓存；其余照攒批的看法办
    @Test
    void a_similarity_proposed_same_never_applies_on_the_batch_verdict_alone() {
        assertFalse(AdjudicateEntitiesJob.batchVerdictMayApply(
                item("name_vector|0.78"), Boolean.TRUE));
        assertTrue(AdjudicateEntitiesJob.batchVerdictMayApply(
                item("name_vector|0.78"), Boolean.FALSE));
        assertTrue(AdjudicateEntitiesJob.batchVerdictMayApply(
                item("name_vector|0.78"), null));
        assertTrue(AdjudicateEntitiesJob.batchVerdictMayApply(
                item("ambiguous_name|0.41"), Boolean.TRUE));
        assertTrue(AdjudicateEntitiesJob.batchVerdictMayApply(
                item("shared_name|张伟"), Boolean.TRUE));
    }

    /// 同名家族的对不受影响：够线就照旧自动落地
    @Test
    void a_same_name_pair_keeps_the_old_rule() {
        Precedents p = Precedents.none();
        ReviewItem it = item("ambiguous_name|0.41");
        assertFalse(AdjudicateEntitiesJob.needsSecondLook(it, p, Boolean.TRUE, 0.9f));
        assertTrue(AdjudicateEntitiesJob.needsSecondLook(it, p, Boolean.TRUE, 0.6f), "不到线才再看");
    }
}
