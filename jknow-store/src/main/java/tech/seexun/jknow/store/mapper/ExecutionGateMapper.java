package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 执行闸门取数 SQL（execution_gate.rs 逐字搬迁）：派生计数、回答计数、functional 撞车。
 */
public interface ExecutionGateMapper {

    /** execution_gate.rs:120-124：任一边参与的、还成立的派生事实数。 */
    long countDerivedTouching(@Param("kbId") UUID kbId, @Param("a") UUID a, @Param("b") UUID b);

    /**
     * execution_gate.rs:132-136：把任一侧认作实体的 assistant 回答数。
     * id 以文本进 jsonb 比较（e->>'id' 是 text）。
     */
    long countAnswered(@Param("kbId") UUID kbId, @Param("a") String a, @Param("b") String b);

    /** execution_gate.rs:156-160 impact_of_fact：以它为前提、还成立的派生。 */
    long countDerivedFromPremise(@Param("kbId") UUID kbId, @Param("factId") UUID factId);

    /** execution_gate.rs:165-170 impact_of_fact：把它的主语认下过的回答。 */
    long countAnsweredBySubject(@Param("kbId") UUID kbId, @Param("factId") UUID factId);

    /**
     * execution_gate.rs:197-204 impact_of_write：functional 谓词撞不同实体宾语的标签
     * （没有撞车 → null）。replacing 是这次写要取代的旧行，它不算撞。
     */
    String selectFunctionalClashLabel(@Param("kbId") UUID kbId, @Param("predicateId") UUID predicateId,
                                      @Param("subjectId") UUID subjectId, @Param("object") UUID object,
                                      @Param("replacing") UUID replacing);
}
