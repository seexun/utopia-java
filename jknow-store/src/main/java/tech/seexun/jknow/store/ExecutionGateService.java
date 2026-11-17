package tech.seexun.jknow.store;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.ExecutionGate.Impact;
import tech.seexun.jknow.store.mapper.ExecutionGateMapper;
import tech.seexun.jknow.store.temporal.TemporalService;

/**
 * 执行闸门取数（execution_gate.rs:110-217）：三条查询，只在裁决器有把握要合的时候才跑。
 * 写路径的守卫看的是「当前那一行」（invalidated_at IS NULL），与 resolve_mention 数度数
 * 一个写法；不走记录轴谓词——修正永远发生在现在（0019）。
 */
@Service
public class ExecutionGateService {

    private final ExecutionGateMapper mapper;
    private final TemporalService temporal;

    public ExecutionGateService(ExecutionGateMapper mapper, TemporalService temporal) {
        this.mapper = mapper;
        this.temporal = temporal;
    }

    /**
     * execution_gate.rs:114-150 {@code impact_of}：一对实体合并会牵动什么。
     * 矛盾与一致性检查同一个口径：只看实体宾语的边，<b>按时间看</b>——两边各有一个不同
     * 的值，合了之后若能排成先后接替（房东先 HPBB1、后 BBHQ1），一致性检查不会报，
     * 这里也不拦。从前这里不看时间，一份换过房东的租约有两个名字就永远合不起来。
     */
    public Impact impactOf(UUID kbId, UUID a, UUID b) {
        Impact impact = new Impact();
        impact.contradictions.addAll(temporal.mergeWouldOverlap(kbId, a, b));
        impact.derived = mapper.countDerivedTouching(kbId, a, b);
        // resolved 是每一轮回答认下的实体（id、名字、类型）；被认过就是被问过
        impact.answered = mapper.countAnswered(kbId, a.toString(), b.toString());
        return impact;
    }

    /**
     * execution_gate.rs:155-181 {@code impact_of_fact}：撤一条事实会牵动什么
     * （0044 决定 7 的勘误 agent 走这同一道闸门）——以它为前提、还成立的派生；把它的
     * 主语认下过的回答（回答记的是它认下的东西，不是引的事实——主语被问过，关于它的
     * 一条事实就可能进过答案；与合并同一个口径）。矛盾一栏空着：撤掉一条不会开出违规。
     */
    public Impact impactOfFact(UUID kbId, UUID factId) {
        Impact impact = new Impact();
        impact.derived = mapper.countDerivedFromPremise(kbId, factId);
        impact.answered = mapper.countAnsweredBySubject(kbId, factId);
        return impact;
    }

    /**
     * execution_gate.rs:185-217 {@code impact_of_write}：写一条事实会牵动什么——谓词
     * 只许一个值而主语已经有另一个东西，一致性检查下一次跑就开出 functional 违规。
     * 与 0027 §5 同一个口径：只看实体宾语的边，不看时间，不看字面值。
     * {@code replacing} 是这次写要取代的那一行：改一条事实时旧行还活着，它不算撞。
     */
    public Impact impactOfWrite(UUID kbId, UUID subjectId, UUID predicateId, UUID objectId,
                                UUID replacing) {
        Impact impact = new Impact();
        if (objectId != null) {
            String clash = mapper.selectFunctionalClashLabel(kbId, predicateId, subjectId,
                    objectId, replacing);
            if (clash != null) {
                impact.contradictions.add(clash);
            }
        }
        return impact;
    }
}
