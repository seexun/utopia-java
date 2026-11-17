package tech.seexun.jknow.store;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.mapper.OntologyRegressionsMapper;
import tech.seexun.jknow.store.mapper.OntologyRegressionsMapper.Case;
import tech.seexun.jknow.store.mapper.PhraseBindingsMapper.PhraseSignature;

/**
 * 人的对齐例子回归（ontology_regressions.rs 230 行对等，0061 cut 2 第一片）：人的例子
 * 比较现有对齐账本，不额外调用模型。写入加入调用方事务（判定与它的回归观测一次提交）。
 */
@Service
public class OntologyRegressionsService {

    private final OntologyRegressionsMapper mapper;

    public OntologyRegressionsService(OntologyRegressionsMapper mapper) {
        this.mapper = mapper;
    }

    /** ontology_regressions.rs:51-71 工作台读：保存的结果与活的源标签。 */
    public List<JsonNode> views(UUID kbId) {
        return mapper.selectViews(kbId);
    }

    /** ontology_regressions.rs:74-83。 */
    public List<Case> list(UUID kbId) {
        return mapper.selectList(kbId);
    }

    /** ontology_regressions.rs:85-93。 */
    public Case get(UUID kbId, UUID id) {
        return mapper.selectGet(kbId, id);
    }

    /** ontology_regressions.rs:24-30 NewCase。 */
    public record NewCase(UUID statementId, UUID expectedPropertyId, String expectedDirection,
                          UUID createdBy, String origin) {
    }

    /** ontology_regressions.rs:95-100 add：自己的事务。 */
    public UUID add(UUID kbId, NewCase c) {
        return addOn(kbId, c);
    }

    /**
     * ontology_regressions.rs:104-173 {@code add_on}：形状校验（方向 forward/reverse、
     * 来源 adoption/person、属性形状合陈述宾语），FOR SHARE 锁陈述与属性，撞键幂等捡回。
     * 加入调用方事务（采纳提案与留例一起提交）。
     */
    public UUID addOn(UUID kbId, NewCase c) {
        if (!"forward".equals(c.expectedDirection()) && !"reverse".equals(c.expectedDirection())) {
            throw AppException.invalid("bad_direction", "Direction is forward or reverse.");
        }
        if (!"adoption".equals(c.origin()) && !"person".equals(c.origin())) {
            throw AppException.invalid("bad_origin", "Origin is adoption or person.");
        }
        Boolean value = mapper.selectStatementIsValueForShare(kbId, c.statementId());
        if (value == null) {
            throw AppException.NOT_FOUND;
        }
        String kind = mapper.selectPropertyKindForShare(kbId, c.expectedPropertyId());
        if (kind == null) {
            throw AppException.NOT_FOUND;
        }
        if (value != ("attribute".equals(kind)) || (value && !"forward".equals(c.expectedDirection()))) {
            throw AppException.invalid("bad_property_shape",
                    "The property's kind and direction must fit the statement's object.");
        }
        UUID inserted = mapper.insertCase(Uuids.nowV7(), kbId, c.statementId(),
                c.expectedPropertyId(), c.expectedDirection(), c.createdBy(), c.origin());
        UUID id = inserted != null ? inserted
                : mapper.selectCaseId(kbId, c.statementId(), c.expectedPropertyId(),
                        c.expectedDirection());
        // 既有的判定按它原时刻报，不当成一次新的模型检查
        mapper.recordExisting(kbId, id);
        return id;
    }

    /**
     * ontology_regressions.rs:197-219 {@code record_for_signature}：短语判定写下的同一
     * 事务里，把活陈述此刻的签名命中的绑定记成一次 fresh 检查。加入调用方事务。
     */
    public void recordForSignature(UUID kbId, PhraseSignature sig) {
        mapper.recordForSignature(kbId, PhraseBindingsService.normalize(sig.phrase()),
                sig.subjectTypeId(), sig.objectIsValue() ? null : sig.objectTypeId(),
                sig.objectIsValue());
    }

    /** ontology_regressions.rs:222-230 {@code record_human}：护住生产跳过时的人的选择。 */
    public void recordHuman(UUID kbId) {
        mapper.recordHuman(kbId);
    }
}
