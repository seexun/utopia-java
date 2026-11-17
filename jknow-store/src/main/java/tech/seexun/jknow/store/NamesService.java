package tech.seexun.jknow.store;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.NameView;
import tech.seexun.jknow.store.mapper.NamesMapper;
import tech.seexun.jknow.store.temporal.Validity;

/**
 * 名字域（names.rs 头注，0041 决定 1）：一个实体的每个名字——本名、简称、曾用名、
 * 另一种文字的写法——都是内建属性 {@code known_as} 上的一条值事实：
 * {@code object_value = {"value": 名字}}、{@code object_id} 为空。它有出处（证据引文）、
 * 两个时钟、可以有有效期（更名），能被撤回，合并时跟着其它事实一起搬、撤回合并时一起
 * 搬回去。<b>它是值，不是节点</b>：画布只画带 object_id 的边。
 *
 * <p>读的时候要分清两件事（SQL 片段生成器，消解候选召回要用）：
 * <ul>
 *   <li>召回：{@link #hasNameIn}——这个实体有没有叫这些名字之一的（含曾用名：世界轴上
 *       结束了的名字仍然认得出旧文档里的它，所以只看记录轴 invalidated_at）；</li>
 *   <li>列事实：{@link #notAName}——实体面板、审阅卡、度数、规则这些地方，名字不算
 *       一条「事实」，不能冒充证据，也不能把计数撑大。</li>
 * </ul>
 */
@Service
public class NamesService {

    private final NamesMapper names;
    private final GraphWriteService writes;
    private final tech.seexun.jknow.store.mapper.ResolutionMapper resolution;

    public NamesService(NamesMapper names, GraphWriteService writes,
                        tech.seexun.jknow.store.mapper.ResolutionMapper resolution) {
        this.names = names;
        this.writes = writes;
        this.resolution = resolution;
    }

    /** names.rs:24 KNOWN_AS：内建属性的 key。builtin = TRUE，建库时不铺，第一次要时建。 */
    public static final String KNOWN_AS = "known_as";

    /**
     * names.rs:27-59 {@code ensure_known_as}：取（没有就建）这个库的 known_as 属性。
     * ON CONFLICT 命中说明并发建过了，取回那一条。
     */
    public UUID ensureKnownAs(UUID kbId) {
        UUID existing = names.selectKnownAs(kbId, KNOWN_AS);
        if (existing != null) {
            return existing;
        }
        names.insertKnownAs(Uuids.nowV7(), kbId, KNOWN_AS,
                "A name a text uses for this entity. Each name is a fact with its source and both clocks; "
                        + "a name is a value and never becomes a node.");
        // ON CONFLICT 命中说明并发建过了，取回那一条
        return names.selectKnownAs(kbId, KNOWN_AS);
    }

    /** names.rs:62-66 NameSource：一个名字的出处——哪一块、引文。没有出处的名字传 null。 */
    public record NameSource(UUID chunkId, String quote) {
    }

    /**
     * names.rs:72-102 {@code record}：记下「这段文字管这个实体叫这个名字」。返回名字
     * 事实的 id；名字是空的返回 null。
     *
     * <p>同一个实体同一个名字只有一行（insert_value_fact 按主语、谓词、值去重），
     * 再被提到只是多一条证据、证据日期往早挪。经 {@link GraphWriteService#insertValueFact}
     * 落 {"value": name}，置信度 1.0——名字是照抄的字，不是模型的判断。
     */
    public UUID record(UUID kbId, UUID entityId, String name, NameSource source,
                       OffsetDateTime attestedAt) {
        String normalized = Names.normalizeName(name);
        if (normalized.isEmpty()) {
            return null;
        }
        UUID attr = ensureKnownAs(kbId);
        GraphWriteService.FactWrite written = writes.insertValueFact(kbId, entityId, attr,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                        .put("value", normalized),
                new Validity(null, null, null, null, attestedAt, null), 1.0f);
        if (source != null) {
            writes.addEvidence(written.id(), source.chunkId(), source.quote(), KNOWN_AS);
        }
        return written.id();
    }

    /**
     * names.rs:113-121 {@code has_name_in}：召回用 SQL 片段——实体 {entity} 有一条现行
     * 的名字事实，库 id 在第 {kb} 个参数，小写值在第 {names} 个参数（text[]）里。
     *
     * <p><b>为什么写成不相关子查询并带库与谓词过滤</b>：相关子查询 {@code nf.subject_id
     * = e.id} 不带库，规划器用不上 facts_value_text_idx（(kb_id, lower(value)) WHERE
     * invalidated_at IS NULL AND object_value IS NOT NULL），每次召回都扫全表 facts。
     * 占位符序号由调用方按自己的参数表指定（与 Rust 的 ${kb} 同一套约定）。
     */
    public static String hasNameIn(String entity, int kb, int namesParam) {
        return """
                %s.id IN (SELECT nf.subject_id FROM facts nf
                           JOIN relation_types nr ON nr.id = nf.predicate_id
                          WHERE nf.kb_id = $%d AND nr.kb_id = $%d AND nr.builtin AND nr.key = '%s'
                            AND nf.invalidated_at IS NULL AND nf.object_value IS NOT NULL
                            AND lower(nf.object_value->>'value') = ANY($%d))"""
                .formatted(entity, kb, kb, KNOWN_AS, namesParam);
    }

    /**
     * names.rs:124-133 {@code has_name_like}：查找用 SQL 片段——实体 {entity} 有一条
     * 现行的名字事实 ILIKE 第 {param} 个参数（这一条是相关子查询：单实体查找不撞召回
     * 的全表扫问题）。
     */
    public static String hasNameLike(String entity, int param) {
        return """
                EXISTS (SELECT 1 FROM facts nf
                           JOIN relation_types nr ON nr.id = nf.predicate_id
                          WHERE nf.subject_id = %s.id AND nf.kb_id = %s.kb_id
                            AND nr.builtin AND nr.key = '%s'
                            AND nf.invalidated_at IS NULL
                            AND nf.object_value->>'value' ILIKE $%d)"""
                .formatted(entity, entity, KNOWN_AS, param);
    }

    /**
     * names.rs:136-141 {@code not_a_name}：列事实用 SQL 片段——事实 {fact} 不是名字事实。
     */
    public static String notAName(String fact) {
        return """
                NOT EXISTS (SELECT 1 FROM relation_types nr
                              WHERE nr.id = %s.predicate_id AND nr.builtin AND nr.key = '%s')"""
                .formatted(fact, KNOWN_AS);
    }

    /**
     * names.rs:144-172 {@code for_entity}：一个实体的名字栏——本名在前，其余按首次
     * 记下的先后。as_of 回放到当时库里记着的名字（owner_at / facts_held_at 两形态）。
     */
    public List<NameView> forEntity(UUID kbId, UUID entityId, OffsetDateTime asOf) {
        return asOf == null
                ? names.selectNamesForEntity(kbId, entityId)
                : names.selectNamesForEntityAsOf(kbId, entityId, asOf);
    }

    /**
     * names.rs:186-229 {@code pair_shared_name}：一个刚从原文里读到的别名，别的实体
     * 已经在用——把这一对送去裁决（0041 决定 5 的第一步）。<b>只排队，不合并</b>：
     * 两个实体叫同一个名字，可能是一个东西被拆开了，也可能就是两个东西，判断归裁决器
     * 与治理闸门。不排的话，入库顺序又一次决定了结果。
     *
     * <p><b>已经判过「不是一个」的对不再排</b>：同一个简称每出现在一块里就会走到这里
     * 一次，分开过的一对要是每次都重新进队列，分开这个决定就等于没有记住。
     *
     * @return 排进去的对数
     */
    public int pairSharedName(UUID kbId, UUID entityId, String name) {
        String normalized = Names.normalizeName(name);
        List<UUID> others = names.selectSharedNameOthers(kbId, entityId, normalized, KNOWN_AS);
        for (UUID other : others) {
            // resolution.rs:1393-1420 create_review 的 upsert（逐字）——shared_name 走
            // Adjudicating 阶段。M3 任务 4 起 create_review 全族统一在 ResolutionMapper
            // （ResolutionService.createReview 同一条语句），两处口径不分叉。
            resolution.insertResolutionReview(Uuids.nowV7(), kbId, entityId, other, 0.0f,
                    "shared_name|" + normalized, "adjudicating");
        }
        return others.size();
    }
}
