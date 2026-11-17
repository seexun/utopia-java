package tech.seexun.jknow.store;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.store.entity.EntityInstance;
import tech.seexun.jknow.store.entity.EntityTypeView;
import tech.seexun.jknow.store.entity.RelationTypeView;
import tech.seexun.jknow.store.mapper.OntologyMapper;
import tech.seexun.jknow.store.mapper.OntologyMapper.IdKey;
import tech.seexun.jknow.store.mapper.OntologyMapper.OldCriteria;

/**
 * ontology 读路径装配（对齐旧仓库 ontology.rs 的函数编排：entity_type_views 13-35 /
 * relation_type_views 82-108 / entity_instances 38-71）。Rust 侧 get 端点两视图
 * tokio::try_join! 并行取——Java 顺序执行结果等价（两条只读查询，无相互依赖）。
 * M3 任务 2 起补写族的入口（create_relation_type，temporal 测试造数路径）；
 * 任务 3 补 update_relation_type / 链三查 / 限定项声明 / create_entity_type。
 */
@Service
public class OntologyService {

    private final OntologyMapper ontology;
    private final TransactionTemplate tx;
    /** 任务 5 接线：本体改判据时的公理对账（0062，detection_keys 写前 / reconcile 写后）。 */
    private final ReasoningService reasoning;

    public OntologyService(OntologyMapper ontology, ReasoningService reasoning,
                           PlatformTransactionManager txManager) {
        this.ontology = ontology;
        this.reasoning = reasoning;
        this.tx = new TransactionTemplate(txManager);
    }

    public List<EntityTypeView> entityTypes(UUID kbId) {
        return ontology.selectEntityTypeViews(kbId);
    }

    /** ontology.rs:1432-1449 nearest_relation_type_ids（短语对齐短名单的近邻路）。 */
    public List<UUID> nearestRelationTypeIds(UUID kbId, String vector, long limit,
                                             String onlyKind) {
        return ontology.nearestRelationTypeIds(kbId, vector, limit, onlyKind);
    }

    /** ontology.rs:1461-1471 has_relation_type_vectors。 */
    public boolean hasRelationTypeVectors(UUID kbId, String onlyKind) {
        return ontology.hasRelationTypeVectors(kbId, onlyKind);
    }

    // ---- 本体向量（ontology.rs:1078-1297；M3 任务 10 随 embed_ontology job 落地） ----

    /** ontology.rs:1096-1100 TypeKind：本体行属于哪张表（类/关系+属性）。 */
    public enum TypeKind {
        ENTITY,
        RELATION
    }

    /**
     * ontology.rs:1089-1093 EmbedField：同一行的两份向量——<b>短查询比 Label，长画像比
     * Full</b>。查询分了两种形状，文档也得分两种，否则短查询会被同义反复的类接管
     * （{@code Map\nA map.} 那一类）。
     */
    public enum EmbedField {
        FULL,
        LABEL
    }

    /** ontology.rs:1077-1085 TypeToEmbed：一条待嵌入的本体行（text=要嵌的那段字）。 */
    public record TypeToEmbed(UUID id, TypeKind kind, String text, EmbedField field) {
    }

    /**
     * ontology.rs:1107-1114 embed_text：嵌入用的那段字——标签在前、描述在后。
     * <b>key 不进去</b>：key 是模型读写的令牌，描述才是这个类型的意思所在；把 key 混
     * 进来，检索会被「两个 key 长得像」带偏——而 {@code position}（列表位次）与
     * {@code position}（职位）正是长得一模一样的两回事。
     */
    public static String embedText(String label, String description) {
        String d = description == null ? "" : description.trim();
        if (d.isEmpty()) {
            return label.trim();
        }
        return label.trim() + "\n" + d;
    }

    /** ontology.rs:1252-1259 is_stale：没嵌过、换了模型、或当时嵌的原文与现在要嵌的不一样。 */
    static boolean isStale(boolean embedded, String storedModel, String storedText, String model,
                           String text) {
        return !embedded || !Objects.equals(storedModel, model) || !Objects.equals(storedText, text);
    }

    /**
     * ontology.rs:1123-1192 types_needing_embedding：哪些本体行的向量是陈的。判据是
     * <b>比对当时嵌的原文与模型名</b>（生成原文用 {@link #embedText} 同一函数，#672），
     * 不是看时间戳——描述改了、模型换了，时间戳一样看不出来；这样也不必在每个改描述
     * 的写入点挂钩子，漏一个就悄悄烂掉。
     *
     * <p>{@code only} 限定只补一半（类型消解只用类，等 1633 个关系嵌完是白等）；null =
     * 全部（后台补齐任务）。
     */
    public List<TypeToEmbed> typesNeedingEmbedding(UUID kbId, String model, TypeKind only) {
        if (only == TypeKind.RELATION) {
            return relationsNeedingEmbedding(kbId, model);
        }
        // Rust 先集齐整段（full）再集 label（ontology.rs:1164-1189）：两份各自保序、
        // 整段在前——批划分按这份顺序走，部分失败时哪几条落同一批与 Rust 一致
        List<TypeToEmbed> full = new ArrayList<>();
        List<TypeToEmbed> labels = new ArrayList<>();
        for (OntologyMapper.StoredEmbedding r : ontology.selectEntityTypeEmbeddingRows(kbId)) {
            String text = embedText(r.label(), r.description());
            if (isStale(r.embedded(), r.embeddedModel(), r.embeddedText(), model, text)) {
                full.add(new TypeToEmbed(r.id(), TypeKind.ENTITY, text, EmbedField.FULL));
            }
            // 只嵌 label 的那一份（entity_types.label_embedding）：短查询走这个索引
            String short_ = r.label().trim();
            if (isStale(r.labelEmbedded(), r.labelEmbeddedModel(), r.labelEmbeddedText(), model,
                    short_)) {
                labels.add(new TypeToEmbed(r.id(), TypeKind.ENTITY, short_, EmbedField.LABEL));
            }
        }
        List<TypeToEmbed> out = full;
        out.addAll(labels);
        if (only == TypeKind.ENTITY) {
            return out;
        }
        out.addAll(relationsNeedingEmbedding(kbId, model));
        return out;
    }

    /** ontology.rs:1194-1235 relations_needing_embedding：关系只有整段那一份向量。 */
    private List<TypeToEmbed> relationsNeedingEmbedding(UUID kbId, String model) {
        List<TypeToEmbed> out = new ArrayList<>();
        for (OntologyMapper.StoredEmbedding r : ontology.selectRelationTypeEmbeddingRows(kbId)) {
            String text = embedText(r.label(), r.description());
            if (isStale(r.embedded(), r.embeddedModel(), r.embeddedText(), model, text)) {
                out.add(new TypeToEmbed(r.id(), TypeKind.RELATION, text, EmbedField.FULL));
            }
        }
        return out;
    }

    /**
     * ontology.rs:1264-1297 set_type_embeddings：回写一批向量，连同「嵌的是哪段字、用的
     * 哪个模型」——三者同一次事务写入。items 是 (待嵌行, 向量) 配对（位置配对由调用方
     * 保证，数量对不上整批放弃）。
     */
    public void setTypeEmbeddings(String model, List<Map.Entry<TypeToEmbed, float[]>> items) {
        tx.executeWithoutResult(status -> {
            for (Map.Entry<TypeToEmbed, float[]> item : items) {
                TypeToEmbed t = item.getKey();
                String vector = tech.seexun.jknow.store.mapper.ResolutionMapper.VectorText.literal(
                        item.getValue());
                if (t.kind() == TypeKind.ENTITY) {
                    if (t.field() == EmbedField.LABEL) {
                        ontology.updateEntityTypeEmbeddingLabel(t.id(), vector, t.text(), model);
                    } else {
                        ontology.updateEntityTypeEmbeddingFull(t.id(), vector, t.text(), model);
                    }
                } else {
                    ontology.updateRelationTypeEmbeddingFull(t.id(), vector, t.text(), model);
                }
            }
        });
    }

    // ---- 检索候选地板与未覆盖计数 ----

    /** ontology.rs:1908-1925 ancestors_of：检索候选要带上祖先，否则提示词里没有泛化基类。 */
    public List<UUID> ancestorsOf(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return ontology.selectAncestorsOf(ids);
    }

    /** ontology.rs:2041-2051 uncovered：没绑到类的类别词、没绑到属性的短语各有多少。 */
    public OntologyMapper.Uncovered uncovered(UUID kbId) {
        return ontology.selectUncovered(kbId);
    }

    public List<RelationTypeView> relationTypes(UUID kbId) {
        return ontology.selectRelationTypeViews(kbId);
    }

    /** 某类下实例（按名称序，分页）。返回 (rows, total)，ontology.rs:38-71。 */
    public Instances entityInstances(UUID kbId, UUID typeId, long limit, long offset) {
        List<EntityInstance> rows = ontology.selectEntityInstances(kbId, typeId, limit, offset);
        long total = ontology.countEntityInstances(kbId, typeId);
        return new Instances(rows, total);
    }

    public record Instances(List<EntityInstance> entities, long total) {
    }

    /** 抽取未匹配（活的 + 已忽略的）——参照实例二进制契约（四键响应的后两键）。 */
    public record Misses(List<OntologyMapper.OntologyMiss> misses,
                         List<OntologyMapper.OntologyMiss> dismissedMisses) {
    }

    public Misses misses(UUID kbId) {
        return new Misses(ontology.selectOntologyMisses(kbId),
                ontology.selectDismissedOntologyMisses(kbId));
    }

    /** ontology.rs:341-350 RelationAxioms（写入口的公理参数）。 */
    public record RelationAxioms(boolean functional, boolean inverseFunctional, boolean transitive,
                                 boolean symmetric, boolean asymmetric, boolean irreflexive,
                                 UUID inverseOf, UUID subPropertyOf) {

        public static RelationAxioms empty() {
            return new RelationAxioms(false, false, false, false, false, false, null, null);
        }
    }

    /**
     * ontology.rs:458-525 {@code create_relation_type}：新建一条关系/属性。key 小写
     * snake_case；label 统一小驼峰（0042）；attribute 必须有 domain 与 datatype；
     * 属性不带 inverse/sub_property（validate_property_links 拦 + 兜底）。
     * 同库同 key 冲突 → Conflict。新建的行 id 还不存在，指向自己无从谈起——
     * validate_property_links 的 selfId 传 null。
     */
    public UUID createRelationType(UUID kbId, String key, String label, String temporal,
                                   RelationAxioms ax, String description, String kind,
                                   List<UUID> domains, List<UUID> ranges, String datatype,
                                   String unit) {
        validateKey(key);
        if (!"state".equals(temporal) && !"event".equals(temporal) && !"eternal".equals(temporal)) {
            throw AppException.validation("temporal must be state / event / eternal");
        }
        validateAttributeFields(kind, domains, datatype);
        String camel = lowerCamel(label);
        validatePropertyLinks(kbId, null, kind, ax);
        boolean isAttr = "attribute".equals(kind);
        UUID id = Uuids.nowV7();
        try {
            ontology.insertRelationType(id, kbId, key, camel, temporal, ax.functional(),
                    ax.inverseFunctional(), description, kind,
                    isAttr ? datatype : null, isAttr ? unit : null,
                    ax.transitive(), ax.symmetric(), ax.asymmetric(), ax.irreflexive(),
                    isAttr ? null : ax.inverseOf(), isAttr ? null : ax.subPropertyOf());
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("Relation key '" + key + "' already exists");
        }
        // attribute 不写 range：它的值域是字面量类型，落在 datatype 上
        setDomainsRanges(id, domains, isAttr ? List.of() : ranges);
        return id;
    }

    /**
     * ontology.rs:406-455 {@code validate_property_links}：inverse_of / sub_property_of
     * 两条链的三查。**这里守的第一条是知识库隔离**——列上的外键 {@code REFERENCES
     * relation_types(id)} 不认 kb_id，数据库层面 A 库的关系完全可以指向 B 库的关系；
     * 前端只列本库的关系是界面礼貌不是边界，校验必须在 store 层。
     *
     * <p>不区分「不存在」与「在别的库」（unknown_relation）——能问出哪个 UUID 存在于
     * 别处，本身就是一点不该给的信息。属性没有链（宾语是字面值，无从谈起）、不能当
     * 链的目标；子属性不能是自己（库里有 CHECK，但撞上去是 500，得在到达 CHECK 之前
     * 给出人话）。逆是<b>自己不拦</b>：那等于 symmetric，是合法声明。
     */
    private void validatePropertyLinks(UUID kbId, UUID selfId, String kind, RelationAxioms ax) {
        UUID[] links = {ax.inverseOf(), ax.subPropertyOf()};
        if (links[0] == null && links[1] == null) {
            return;
        }
        if ("attribute".equals(kind)) {
            throw AppException.invalid("attr_has_no_link",
                    "An attribute cannot have an inverse or a super-property");
        }
        if (selfId != null && selfId.equals(ax.subPropertyOf())) {
            throw AppException.invalid("sub_property_self",
                    "A relation cannot be its own super-property");
        }
        for (UUID target : links) {
            if (target == null) {
                continue;
            }
            String targetKind = ontology.selectRelationKind(target, kbId);
            if (targetKind == null) {
                throw AppException.invalid("unknown_relation",
                        "That relation is not in this knowledge base");
            }
            if (!"relation".equals(targetKind)) {
                throw AppException.invalid("link_target_is_attr",
                        "An attribute cannot be an inverse or a super-property");
            }
        }
    }

    /**
     * ontology.rs:658-822 {@code update_relation_type}：改一条关系/属性。
     *
     * <p>名字属性是内建的（0041）：标成 functional 会让每个第二个名字都成一条冲突、
     * 甚至把本名关掉；改名、改时态也一样没有正当用途。两条链<b>跟着六位公理一起
     * 覆盖式写</b>：缺省 = 清空，不是「不动」——同一个表单里同时提交的一组声明，
     * 一半覆盖一半保留才是真正会出事的语义。domain/range 可改——它们是签名不是身份。
     *
     * <p><b>判据一动，靠着它的 open 违规当场对账</b>（0062，任务 5 接线）：动笔之前
     * 记下旧本体下的检出键集（detection_keys），落笔之后按新本体重检收编
     * （reconcile_ontology）——{@code open} 的意思是「已对账于当前本体」，不许留一行
     * 只对旧判据成立的 open 违规走出提交点。label/description/datatype/unit 不进判据：
     * 改了不值得跑一遍检测。
     *
     * @param domains/ranges 整组语义：两侧都 null = 签名不动；任一侧非 null = 两侧都
     *                       按传入值重写（没传的那一侧清空）
     */
    public void updateRelationType(UUID kbId, UUID id, String label, String temporal,
                                   RelationAxioms ax, String description, String datatype,
                                   String unit, List<UUID> domains, List<UUID> ranges) {
        tx.executeWithoutResult(status -> {
            Boolean nameAttribute = ontology.selectBuiltinNameAttribute(id, kbId);
            if (Boolean.TRUE.equals(nameAttribute)) {
                throw AppException.invalid("builtin_name_attribute",
                        "The name attribute is built in and cannot be edited");
            }
            // 改名也归一：不然界面上改一次就能把小驼峰改回 "access to"
            String camel = lowerCamel(label);
            if (!"state".equals(temporal) && !"event".equals(temporal)
                    && !"eternal".equals(temporal)) {
                throw AppException.validation("temporal must be state / event / eternal");
            }
            if (datatype != null && !"text".equals(datatype) && !"number".equals(datatype)
                    && !"date".equals(datatype) && !"bool".equals(datatype)) {
                throw AppException.validation("datatype must be text / number / date / bool");
            }
            // 行不在就是 NotFound——UPDATE 反正会空
            OldCriteria old = ontology.selectOldCriteria(id, kbId);
            if (old == null) {
                throw AppException.NOT_FOUND;
            }
            // 指向别的关系的两条要先问过库：目标在不在本库、是不是关系。
            // 只在真的填了的时候查——清空（两个都 null）没有目标可验
            if (ax.inverseOf() != null || ax.subPropertyOf() != null) {
                validatePropertyLinks(kbId, id, old.kind(), ax);
            }
            // domain/range 可改——它们是签名不是身份，"这个属性也适用于承包商" 是个正当的
            // 编辑。kind 不可变（改它会让存量事实语义错乱）；属性没有 range：值域落在
            // datatype 上。签名的「变」按集合比：传了但没变，不算动判据
            boolean signatureTouched = domains != null || ranges != null;
            boolean signatureChanged = false;
            List<UUID> nextDomains = domains == null ? List.of() : domains;
            List<UUID> nextRanges = "attribute".equals(old.kind()) || ranges == null
                    ? List.of() : ranges;
            if (signatureTouched) {
                if ("attribute".equals(old.kind()) && nextDomains.isEmpty()) {
                    throw AppException.invalid("attr_needs_class",
                            "An attribute needs a class (domain)");
                }
                signatureChanged = !new HashSet<>(ontology.selectOldDomains(id))
                        .equals(new HashSet<>(nextDomains))
                        || !new HashSet<>(ontology.selectOldRanges(id))
                        .equals(new HashSet<>(nextRanges));
            }
            // label / description / datatype / unit 不进这里：它们不动判据，
            // 改了不值得跑一遍检测
            boolean criteriaChanged = !Objects.equals(old.temporal(), temporal)
                    || old.functional() != ax.functional()
                    || old.inverseFunctional() != ax.inverseFunctional()
                    || old.isTransitive() != ax.transitive()
                    || old.isSymmetric() != ax.symmetric()
                    || old.isAsymmetric() != ax.asymmetric()
                    || old.isIrreflexive() != ax.irreflexive()
                    || !Objects.equals(old.inverseOf(), ax.inverseOf())
                    || !Objects.equals(old.subPropertyOf(), ax.subPropertyOf())
                    || signatureChanged;
            // 检出必须在写入**之前**：此刻库里还是旧本体，记下的正是「旧判据下
            // 会算出来的违规」，提交前对照用
            Set<ReasoningService.ViolationKey> wasDetected = criteriaChanged
                    ? reasoning.detectionKeys(kbId) : null;
            int updated = ontology.updateRelationType(kbId, id, camel, temporal, ax.functional(),
                    ax.inverseFunctional(), description, datatype, unit, ax.transitive(),
                    ax.symmetric(), ax.asymmetric(), ax.irreflexive(), ax.inverseOf(),
                    ax.subPropertyOf());
            if (updated == 0) {
                throw AppException.NOT_FOUND;
            }
            if (signatureTouched) {
                setDomainsRanges(id, nextDomains, nextRanges);
            }
            if (wasDetected != null) {
                reasoning.reconcileOntology(kbId, wasDetected);
            }
        });
    }

    /**
     * ontology.rs:195-239 {@code update_entity_type}：改一个类的 label/颜色/形状/描述，
     * 覆盖式重设父链（第一个当主父）。
     *
     * <p><b>color: null = 保持原色</b>，不是重置——从前调用方不给就写死一个默认灰蓝，
     * 于是任何一次不带颜色的改名都会抹掉用户挑过的颜色。
     *
     * <p>父链是签名判据（0062）：违规按祖先链算。只比父集——label、颜色那些不改判据的
     * 字段照常写，不必跑检测；父集变了就动笔前记旧检出、落笔后对账。
     */
    public void updateEntityType(UUID kbId, UUID id, String label, String color, String shape,
                                 List<UUID> parents, String description) {
        if (!"circle".equals(shape) && !"square".equals(shape)) {
            throw AppException.validation("shape must be circle or square");
        }
        tx.executeWithoutResult(status -> {
            List<UUID> oldParents = ontology.selectParentIds(id);
            boolean parentsChanged = parents.size() != oldParents.size()
                    || !parents.stream().allMatch(oldParents::contains);
            Set<ReasoningService.ViolationKey> wasDetected = parentsChanged
                    ? reasoning.detectionKeys(kbId) : null;
            int updated = ontology.updateEntityType(kbId, id, label, color, shape, description);
            if (updated == 0) {
                throw AppException.NOT_FOUND;
            }
            setParents(kbId, id, parents);
            if (wasDetected != null) {
                reasoning.reconcileOntology(kbId, wasDetected);
            }
        });
    }

    /**
     * ontology.rs:301-363 {@code delete_entity_type}：删一个类。有人用不能删；这个类若
     * 牵着签名判据（是某个类的父类，或挂在某条关系的 domain/range 上），删掉它就改了
     * 「哪些违规成立」——动笔前记旧检出、提交前对账。只剩这一个 domain 的属性随类
     * 一起走（属性必须挂在类上）；还挂在别的类上的只掉一条关联（外键 CASCADE 负责）。
     */
    public void deleteEntityType(UUID kbId, UUID id) {
        tx.executeWithoutResult(status -> {
            if (ontology.countEntitiesOfType(id) > 0) {
                throw AppException.conflict(
                        "Cannot delete: " + ontology.countEntitiesOfType(id)
                                + " entities use this type");
            }
            boolean affectsCriteria = ontology.selectTypeAffectsCriteria(id);
            Set<ReasoningService.ViolationKey> wasDetected = affectsCriteria
                    ? reasoning.detectionKeys(kbId) : null;
            // 连带效果（0070 的 FK CASCADE）：属性没了 → 用它做谓词的事实没了 → 指向
            // 那些事实的违规行也被物理删掉。这些行不走 criterion_changed——它们的判据
            // 没换，是事实本身没了，与 run 对消失事实的清陈同一条规矩
            ontology.deleteAttributesOnlyOnType(kbId, id);
            if (ontology.deleteEntityType(kbId, id) == 0) {
                throw AppException.conflict("Built-in types cannot be deleted");
            }
            if (wasDetected != null) {
                reasoning.reconcileOntology(kbId, wasDetected);
            }
        });
    }

    /**
     * ontology.rs:823-875 {@code delete_relation_type}：删一条关系。有事实用不能删；
     * 判据贡献不只「有没有人引用它」这一半——这行自己的旗标与出向
     * inverse_of/sub_property_of 链接喂着别的谓词的派生链，rules_disagree 缺陷行也以
     * 谓词 id 指着它。枚举哪些列算判据已被证明会漏——删除又足够稀少，不为省检测设
     * 门槛，一律记下旧判据下的检出键集（0062）。0070 把两条外键建成 DEFERRABLE 的
     * SET NULL——级联动作提交时才落地，而对账检测要看见改完的判据，所以先显式断链。
     */
    public void deleteRelationType(UUID kbId, UUID id) {
        tx.executeWithoutResult(status -> {
            long usage = ontology.countFactsOfPredicate(id);
            if (usage > 0) {
                throw AppException.conflict("Cannot delete: " + usage + " facts use this relation");
            }
            Set<ReasoningService.ViolationKey> wasDetected = reasoning.detectionKeys(kbId);
            ontology.unlinkInverseOf(id);
            ontology.unlinkSubPropertyOf(id);
            if (ontology.deleteRelationTypeRow(kbId, id) == 0) {
                throw AppException.conflict("Built-in relations cannot be deleted");
            }
            reasoning.reconcileOntology(kbId, wasDetected);
        });
    }

    /** ontology.rs:1527-1546 BulkRelation：批量词表行（OWL/unece 导入）。 */
    public record BulkRelation(String key, String label, String description, String iri,
                                String kind, String datatype, boolean functional,
                                boolean inverseFunctional, boolean transitive, boolean symmetric,
                                boolean asymmetric, boolean irreflexive) {
    }

    /**
     * ontology.rs:1559-1625 {@code create_relation_types_bulk}：一次插完一批关系或属性，
     * 返回 key → id。temporal 固定 state——OWL 里没有对应概念，猜 event 或 eternal 都
     * 更差（与单条版的判断一致）。公理位必须照词汇表写下去：写错的方向不同，后果
     * 也不同——该为真的全成了假，于是<b>该检测出的时态冲突一条都检测不到</b>，静悄悄地。
     */
    public Map<String, UUID> createRelationTypesBulk(UUID kbId, List<BulkRelation> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        for (BulkRelation r : rows) {
            validateKey(r.key());
        }
        // 导入来的词表自己就可能不一致——落库前统一，真身留在 iri 里
        List<String> keys = rows.stream().map(BulkRelation::key).toList();
        List<String> labels = rows.stream().map(r -> lowerCamel(r.label())).toList();
        List<String> descs = rows.stream().map(BulkRelation::description).toList();
        List<String> iris = rows.stream().map(BulkRelation::iri).toList();
        List<String> kinds = rows.stream().map(BulkRelation::kind).toList();
        List<String> datatypes = rows.stream().map(BulkRelation::datatype).toList();
        List<Boolean> functional = rows.stream().map(BulkRelation::functional).toList();
        List<Boolean> inverseFunctional = rows.stream().map(BulkRelation::inverseFunctional)
                .toList();
        List<Boolean> transitive = rows.stream().map(BulkRelation::transitive).toList();
        List<Boolean> symmetric = rows.stream().map(BulkRelation::symmetric).toList();
        List<Boolean> asymmetric = rows.stream().map(BulkRelation::asymmetric).toList();
        List<Boolean> irreflexive = rows.stream().map(BulkRelation::irreflexive).toList();
        Map<String, UUID> out = new LinkedHashMap<>();
        for (IdKey row : ontology.insertRelationTypesBulk(kbId, keys, labels, descs, iris, kinds,
                datatypes, functional, inverseFunctional, transitive, symmetric, asymmetric,
                irreflexive)) {
            out.put(row.key(), row.id());
        }
        return out;
    }

    /**
     * ontology.rs:1715-1738 {@code set_disjoint_for}：把 others 设成这个类的<b>全部</b>
     * 互斥对象（不在其中的解除）。与 bulk 版的分工：那个是导入用的「只增不减」，这个
     * 是编辑用的「这就是全部」。编辑必须能取消——否则界面上取消勾选没有任何效果，
     * 而用户会以为自己改了。两个方向各写一行，与导入侧一致：查「A 与 B 互斥吗」因此
     * 不必关心从哪头问。
     */
    public void setDisjointFor(UUID kbId, UUID classId, List<UUID> others) {
        tx.executeWithoutResult(status -> {
            // 先清掉这个类参与的全部互斥边——两个方向都要清，因为它两边都可能出现
            ontology.deleteDisjointFor(kbId, classId);
            for (UUID other : others) {
                if (other.equals(classId)) {
                    continue;
                }
                ontology.insertDisjointPair(kbId, classId, other);
            }
        });
    }

    /**
     * ontology.rs:569-617 {@code set_relation_qualifiers}：声明一条关系的限定项——
     * 只有本库的<b>属性</b>能当限定项（值是字面值），关系不能（那是另一条边），
     * 自己也不能。覆盖式：先删后插。
     */
    public void setRelationQualifiers(UUID kbId, UUID relationTypeId, List<UUID> qualifierTypeIds) {
        if (qualifierTypeIds.contains(relationTypeId)) {
            throw AppException.invalid("qualifier_is_self",
                    "A relation cannot be its own qualifier");
        }
        if (!qualifierTypeIds.isEmpty()
                && ontology.countAttributeQualifiers(kbId, qualifierTypeIds)
                        != qualifierTypeIds.size()) {
            throw AppException.invalid("qualifier_not_attribute",
                    "Every qualifier must be an attribute of this base");
        }
        ontology.deleteRelationQualifiers(relationTypeId);
        if (!qualifierTypeIds.isEmpty()) {
            ontology.insertRelationQualifiers(relationTypeId, qualifierTypeIds);
        }
    }

    /**
     * ontology.rs:620-652 {@code add_relation_qualifier}：给一条关系<b>追加</b>一个
     * 边上的属性声明（0037）。与覆盖式不同：抽取时几篇文档并行，各自从语料里补声明，
     * 覆盖式写入会把别人刚补的冲掉（实测 round 补过又没了）。只加不删，撞上已有的
     * 什么都不做。校验与覆盖式同一套。
     */
    public void addRelationQualifier(UUID kbId, UUID relationTypeId, UUID qualifierTypeId) {
        if (relationTypeId.equals(qualifierTypeId)) {
            throw AppException.invalid("qualifier_is_self",
                    "A relation cannot be its own qualifier");
        }
        if (ontology.countAttributeQualifier(kbId, qualifierTypeId) != 1) {
            throw AppException.invalid("qualifier_not_attribute",
                    "Every qualifier must be an attribute of this base");
        }
        ontology.insertRelationQualifier(relationTypeId, qualifierTypeId);
    }

    /**
     * ontology.rs:156-192 {@code create_entity_type}：建一个实体类。shape 只有
     * circle / square；第一个父类当主父。
     */
    public UUID createEntityType(UUID kbId, String key, String label, String color, String shape,
                                 List<UUID> parents, String description) {
        validateKey(key);
        if (!"circle".equals(shape) && !"square".equals(shape)) {
            throw AppException.validation("shape must be circle or square");
        }
        UUID id = Uuids.nowV7();
        try {
            ontology.insertEntityType(id, kbId, key, label, color, shape, description);
        } catch (DuplicateKeyException e) {
            throw AppException.conflict("Type key '" + key + "' already exists");
        }
        setParents(kbId, id, parents);
        return id;
    }

    /**
     * ontology.rs:252-295 {@code set_parents}：自指与成环两查，覆盖式写。
     *
     * <p><b>先查环再写</b>。单父时代只需要挡自环，一条链天然不会成环；DAG 里 A→B→A
     * 完全可能，而 type_matches_domain 沿父链上溯，成环就是死循环。SQL 拦不住这个——
     * 外键只挡自环，更长的要应用来查。
     *
     * <p>空列表照走全流程（DELETE 清空父链）——「移除全部父类」必须能生效（任务 5
     * update_entity_type 落地，任务 3 审查 M-1 指出的早退已去：新建行 DELETE 影响 0 行，
     * 行为不变）。
     */
    private void setParents(UUID kbId, UUID child, List<UUID> parents) {
        List<UUID> list = parents == null ? List.of() : parents;
        if (list.contains(child)) {
            throw AppException.invalid("self_parent", "A class cannot be its own parent");
        }
        // 候选父类的全部祖先里出现 child，就说明这条边会成环
        if (!list.isEmpty() && ontology.countParentCycle(child, list) > 0) {
            throw AppException.invalid("parent_cycle",
                    "That parent is already a subclass of this one");
        }
        ontology.deleteParents(child);
        for (int i = 0; i < list.size(); i++) {
            // 第一个当主父：界面上说明了「画在第一个下面」，不再多一个控件
            ontology.insertParent(child, list.get(i), i == 0);
        }
    }

    /** ontology.rs:140-149 validate_key。 */
    private static void validateKey(String key) {
        boolean ok = key != null && !key.isEmpty() && key.length() <= 40
                && key.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                        || c == '_');
        if (!ok) {
            throw AppException.invalid("bad_key",
                    "Key must be lowercase snake_case (a-z, 0-9, _), max 40 chars");
        }
    }

    /** ontology.rs:368-393 validate_attribute_fields。 */
    private static void validateAttributeFields(String kind, List<UUID> domains, String datatype) {
        switch (kind == null ? "" : kind) {
            case "relation" -> {
            }
            case "attribute" -> {
                if (domains == null || domains.isEmpty()) {
                    throw AppException.invalid("attr_needs_class",
                            "An attribute needs a class (domain)");
                }
                if (!"text".equals(datatype) && !"number".equals(datatype) && !"date".equals(datatype)
                        && !"bool".equals(datatype)) {
                    throw AppException.validation("datatype must be text / number / date / bool");
                }
            }
            default -> throw AppException.validation("kind must be relation / attribute");
        }
    }

    /** ontology.rs:119-136 lower_camel：只动分隔符与首字母，不碰词内部的大小写。 */
    static String lowerCamel(String label) {
        String trimmed = label == null ? "" : label.trim();
        StringBuilder out = new StringBuilder(trimmed.length());
        boolean startOfWord = false;
        for (char c : trimmed.toCharArray()) {
            if (c == ' ' || c == '_' || c == '-') {
                if (!out.isEmpty()) {
                    startOfWord = true;
                }
                continue;
            }
            if (out.isEmpty()) {
                out.append(Character.toLowerCase(c));
            } else if (startOfWord) {
                out.append(Character.toUpperCase(c));
                startOfWord = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** ontology.rs:530-558 set_domains_ranges：覆盖式——先删后插。 */
    private void setDomainsRanges(UUID id, List<UUID> domains, List<UUID> ranges) {
        ontology.deleteDomains(id);
        if (domains != null && !domains.isEmpty()) {
            ontology.insertDomains(id, domains);
        }
        ontology.deleteRanges(id);
        if (ranges != null && !ranges.isEmpty()) {
            ontology.insertRanges(id, ranges);
        }
    }

    // ====================================================================
    // M5 任务 5：OWL 导入的 store 编排（owl_import.rs 依赖的 ontology.rs 批量面）
    // 与本体代理提案/看过的形状（ontology.rs:1778-1931 + agent_reviews.rs 全量）。
    // ====================================================================

    /** 现有类索引（id, key, iri）——iri 可空。 */
    public List<OntologyMapper.IdKeyIri> entityTypesWithIri(UUID kbId) {
        return ontology.selectEntityTypesWithIri(kbId);
    }

    /** 现有关系/属性索引（id, key, iri）。 */
    public List<OntologyMapper.IdKeyIri> relationTypesWithIri(UUID kbId) {
        return ontology.selectRelationTypesWithIri(kbId);
    }

    /** 批量建类的一行：(key, label, description, iri)——key 用计划里的（对齐表改过名）。 */
    public record BulkClass(String key, String label, String description, String iri) {
    }

    /**
     * ontology.rs:1487-1558 {@code create_entity_types_bulk}：一次插完一批类，返回
     * key → id。颜色按 key、形状按 iri 在 Java 侧算好（SQL 里调不到 Java 函数，
     * 而这一批正是导入大本体走的路）。
     */
    public Map<String, UUID> createEntityTypesBulk(UUID kbId, List<BulkClass> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        for (BulkClass r : rows) {
            validateKey(r.key());
        }
        List<String> keys = rows.stream().map(BulkClass::key).toList();
        List<String> labels = rows.stream().map(BulkClass::label).toList();
        List<String> descs = rows.stream().map(BulkClass::description).toList();
        List<String> iris = rows.stream().map(BulkClass::iri).toList();
        List<String> colors = keys.stream().map(Palette::colorForKey).toList();
        List<String> shapes = iris.stream().map(Palette::shapeFor).toList();
        Map<String, UUID> out = new LinkedHashMap<>();
        for (IdKey row : ontology.insertEntityTypesBulk(kbId, keys, labels, descs, iris, colors,
                shapes)) {
            out.put(row.key(), row.id());
        }
        return out;
    }

    /**
     * ontology.rs:925-950 {@code update_type_from_import}：重导入时按 IRI 更新标签与
     * 描述。key 不动——可能已被实体和提示词引用；返回 null 表示这个 IRI 不在库里。
     */
    public UUID updateTypeFromImport(UUID kbId, String iri, String label, String description) {
        return ontology.updateTypeFromImport(kbId, iri, label, description);
    }

    /**
     * ontology.rs:1413-1426 {@code adopt_iri_onto_key}：把 IRI 认到已有的本地类上
     * （原本没有 IRI 的那种）。只写 IRI 与形状，不动 label/描述/颜色。
     */
    public UUID adoptIriOntoKey(UUID kbId, String key, String iri) {
        return ontology.adoptIriOntoKey(kbId, key, iri);
    }

    /** ontology.rs:1657-1681 {@code set_parents_bulk}：一次设完一批父类（不查环）。 */
    public void setParentsBulk(List<Map.Entry<UUID, UUID>> pairs) {
        if (pairs.isEmpty()) {
            return;
        }
        ontology.insertParentsBulk(pairs.stream().map(Map.Entry::getKey).toList(),
                pairs.stream().map(Map.Entry::getValue).toList());
    }

    /** ontology.rs:1684-1713 {@code set_disjoint_bulk}：类互斥只增不减。 */
    public void setDisjointBulk(UUID kbId, List<Map.Entry<UUID, UUID>> pairs) {
        if (pairs.isEmpty()) {
            return;
        }
        ontology.insertDisjointBulk(kbId, pairs.stream().map(Map.Entry::getKey).toList(),
                pairs.stream().map(Map.Entry::getValue).toList());
    }

    /**
     * ontology.rs:1002-1031 {@code update_relation_from_import}：重导入时更新已按
     * IRI 认下的关系。key 不动、空描述不覆盖、domain/range 整体重写（上游声明的结构，
     * 不是人调过的措辞）。返回 false 表示这个 IRI 不在库里。
     */
    public boolean updateRelationFromImport(UUID kbId, String iri, String label,
                                            String description, List<UUID> domains,
                                            List<UUID> ranges) {
        UUID id = ontology.updateRelationFromImport(kbId, iri, label, description);
        if (id == null) {
            return false;
        }
        setDomainsRanges(id, domains, ranges);
        return true;
    }

    /**
     * ontology.rs:1624-1655 {@code link_domains_ranges_bulk}：一次写完一批关系的
     * domain/range。不 DELETE：调用方是刚建出来的新关系；更新既有关系仍走单条版。
     */
    public void linkDomainsRangesBulk(List<Map.Entry<UUID, UUID>> domains,
                                      List<Map.Entry<UUID, UUID>> ranges) {
        if (!domains.isEmpty()) {
            ontology.insertDomainsBulk(domains.stream().map(Map.Entry::getKey).toList(),
                    domains.stream().map(Map.Entry::getValue).toList());
        }
        if (!ranges.isEmpty()) {
            ontology.insertRangesBulk(ranges.stream().map(Map.Entry::getKey).toList(),
                    ranges.stream().map(Map.Entry::getValue).toList());
        }
    }

    /**
     * ontology.rs:1934-2034 {@code link_property_axioms_bulk}：把 owl:inverseOf /
     * rdfs:subPropertyOf 从 IRI 解析成 id。必须是第二遍——这两条指的是另一个关系
     * 类型，id 要等全部插完才有。返回 (逆连上几条, 父属性连上几条)。
     */
    public long[] linkPropertyAxiomsBulk(UUID kbId, List<Map.Entry<String, String>> inverse,
                                         List<Map.Entry<String, String>> subProperty) {
        long inv = inverse.isEmpty() ? 0 : ontology.linkInverseOfBulk(kbId,
                inverse.stream().map(Map.Entry::getKey).toList(),
                inverse.stream().map(Map.Entry::getValue).toList());
        long sub = subProperty.isEmpty() ? 0 : ontology.linkSubPropertyOfBulk(kbId,
                subProperty.stream().map(Map.Entry::getKey).toList(),
                subProperty.stream().map(Map.Entry::getValue).toList());
        return new long[] {inv, sub};
    }

    /**
     * ontology.rs:1033-1061 {@code record_import}：记一次导入（原文已按内容寻址存进
     * blob，这里只记账）。
     */
    public UUID recordImport(UUID kbId, String sha256, String filename, String format,
                             long byteSize, com.fasterxml.jackson.databind.JsonNode summary,
                             UUID actor) {
        UUID id = Uuids.nowV7();
        ontology.insertImport(id, kbId, sha256, filename, format, byteSize, summary, actor);
        return id;
    }

    /** 一条代理提案（ontology.rs:1770-1775 AgentProposal）。 */
    public record AgentProposal(String section, String key,
                                com.fasterxml.jackson.databind.JsonNode payload,
                                List<UUID> serves,
                                com.fasterxml.jackson.databind.JsonNode signatures) {
    }

    /** ontology.rs:1778-1805 {@code save_agent_proposals}：只刷 open 的行。 */
    public void saveAgentProposals(UUID kbId, List<AgentProposal> items) {
        for (AgentProposal it : items) {
            ontology.upsertAgentProposal(Uuids.nowV7(), kbId,
                    it.section(), it.key(), it.payload(), it.serves(), it.signatures());
        }
    }

    /** ontology.rs:1808-1816 {@code signatures_already_proposed}。 */
    public List<com.fasterxml.jackson.databind.JsonNode> signaturesAlreadyProposed(UUID kbId) {
        return ontology.selectAgentProposalSignatures(kbId);
    }

    /** 存库的一条提案（ontology.rs:1757-1765 StoredProposal）。 */
    public record StoredProposal(String section, String key,
                                 com.fasterxml.jackson.databind.JsonNode payload,
                                 String proposedBy, List<UUID> serves,
                                 com.fasterxml.jackson.databind.JsonNode signatures) {
    }

    /** ontology.rs:1829-1843 {@code open_proposal}：按键取一条 open 的。 */
    public StoredProposal openProposal(UUID kbId, String section, String key) {
        OntologyMapper.StoredProposalRow row = ontology.selectOpenProposal(kbId, section, key);
        return row == null ? null
                : new StoredProposal(row.section(), row.key(), row.payload(), row.proposedBy(),
                        row.serves(), row.signatures());
    }

    /** ontology.rs:1863-1870 {@code open_proposals}：还等着人看的（新的排前面）。 */
    public List<StoredProposal> openProposals(UUID kbId) {
        return ontology.selectOpenProposals(kbId).stream()
                .map(r -> new StoredProposal(r.section(), r.key(), r.payload(), r.proposedBy(),
                        r.serves(), r.signatures()))
                .toList();
    }

    /** ontology.rs:1879-1898 {@code decide_proposal}：改状态不删行。 */
    public void decideProposal(UUID kbId, String section, String key, String status, UUID actor) {
        ontology.decideProposal(kbId, section, key, status, actor);
    }

    /** ontology.rs:1821-1843 {@code reject_proposal}：连理由。 */
    public void rejectProposal(UUID kbId, String section, String key, String reason, UUID actor) {
        String r = reason == null ? null : reason.trim();
        ontology.rejectProposal(kbId, section, key, r == null || r.isEmpty() ? null : r, actor);
    }

    /** adopt 后的 edited 位（报告 changed_share 的分子要它）。 */
    public void markProposalEdited(UUID kbId, String section, String key) {
        ontology.markProposalEdited(kbId, section, key);
    }

    /** agent_reviews.rs:7-16 Review。 */
    public record AgentReview(String kind,
                              com.fasterxml.jackson.databind.JsonNode shape, String outcome,
                              String target, String basis) {
    }

    /** agent_reviews.rs:22-28 {@code list}。 */
    public List<AgentReview> agentReviews(UUID kbId) {
        return ontology.selectAgentReviews(kbId).stream()
                .map(r -> new AgentReview(r.kind(), r.shape(), r.outcome(), r.target(), r.basis()))
                .toList();
    }

    /** agent_reviews.rs:31-45 {@code record}：同一形状再看是刷新。 */
    public void recordAgentReviews(UUID kbId, List<AgentReview> items) {
        for (AgentReview it : items) {
            ontology.upsertAgentReview(kbId, it.kind(), it.shape(), it.outcome(), it.target(),
                    it.basis());
        }
    }

    /** 近邻候选（utopia_core::models::TypeCandidate 的服务层形）。 */
    public record TypeCandidate(UUID id, String key, String label, String description, String kind,
                                float distance) {
    }

    /** ontology.rs:1314-1356 {@code nearest_entity_types}（byLabel 分档两份索引）。 */
    public List<TypeCandidate> nearestEntityTypes(UUID kbId, float[] embedding, long limit,
                                                  boolean byLabel) {
        return ontology.selectNearestEntityTypes(kbId, vectorText(embedding), limit,
                        byLabel ? "label_embedding" : "embedding").stream()
                .map(r -> new TypeCandidate(r.id(), r.key(), r.label(), r.description(), r.kind(),
                        (float) r.distance()))
                .toList();
    }

    /** ontology.rs:1360-1390 {@code nearest_relation_types} 的整行版。 */
    public List<TypeCandidate> nearestRelationTypes(UUID kbId, float[] embedding, long limit,
                                                    String onlyKind) {
        return ontology.selectNearestRelationTypes(kbId, vectorText(embedding), limit, onlyKind)
                .stream()
                .map(r -> new TypeCandidate(r.id(), r.key(), r.label(), r.description(), r.kind(),
                        (float) r.distance()))
                .toList();
    }

    /** pgvector 的文本形态（pgjdbc 无 vector 类型，与既有 mapper 同法）。 */
    private static String vectorText(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }
}
