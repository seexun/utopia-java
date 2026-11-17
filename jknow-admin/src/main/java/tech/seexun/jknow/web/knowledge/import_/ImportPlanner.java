package tech.seexun.jknow.web.knowledge.import_;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.web.knowledge.auth.AuditService;
import tech.seexun.jknow.web.knowledge.ingest.BlobStore;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlClass;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlProjection;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlProperty;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.RangeMapping;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.RdfFormat;
import tech.seexun.jknow.web.knowledge.import_.PackAlignment.Alignment;
import tech.seexun.jknow.web.knowledge.import_.PackAlignment.Result;
import tech.seexun.jknow.store.JobsService;
import tech.seexun.jknow.store.OntologyService;
import tech.seexun.jknow.store.OntologyService.BulkClass;
import tech.seexun.jknow.store.OntologyService.BulkRelation;
import tech.seexun.jknow.store.mapper.OntologyMapper.IdKeyIri;

/**
 * OWL 导入：原文保真 → 投影 → 预览 → 落库（server/owl_import.rs 795 行逐语义）。
 *
 * <p><b>预览与落库走同一个计划</b>。两条独立的代码路径迟早分叉，而分叉的后果是用户点
 * 确认之后发生的事与他刚看过的不一样——那比没有预览更糟。
 *
 * <p>匹配按 <b>IRI</b> 不按 key：上游改一次 rdfs:label，派生出的 key 就变了，按 key
 * 匹配会把同一个类当新类建出来，实体全留在孤儿上（见 0001 P2）。
 */
@Component
public class ImportPlanner {

    private static final Logger log = LoggerFactory.getLogger(ImportPlanner.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final OntologyService ontology;
    private final BlobStore blob;
    private final JobsService jobs;
    private final AuditService audit;

    public ImportPlanner(OntologyService ontology, BlobStore blob, JobsService jobs,
                         AuditService audit) {
        this.ontology = ontology;
        this.blob = blob;
        this.jobs = jobs;
        this.audit = audit;
    }

    /** 一个类/属性在这次导入里的去向（Disposition；wire 名 = serde snake_case）。 */
    public enum Disposition {
        /** 本体里没有这个 IRI → 新建。 */
        CREATE("create"),
        /** IRI 已在 → 更新标签与描述（key 不动：它可能已被引用）。 */
        UPDATE("update"),
        /** key 被另一个 IRI 占着 → 跳过并报告，不悄悄改名也不覆盖。 */
        KEY_TAKEN("key_taken"),
        /**
         * key 被另一个 IRI 占着，但对齐表说那两个是<b>同一个东西</b> → 跳过且不算冲突
         * （不需要人裁：少建一个重复的类正是想要的结果）。
         */
        ALIGNED("aligned"),
        /**
         * 词表自己说这条属性已被另一条取代（schema:supersededBy）→ 不建。建了就是两个
         * key 指同一个关系，抽取的精确 key 命中让 employees 与 employee 永久分家（#560）。
         */
        SUPERSEDED("superseded");

        private final String wire;

        Disposition(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }

    /**
     * 一个属性在这次导入里会不会被建出来，以及为什么（AttrNote——tag=outcome/
     * content=detail 的 serde 形状由 {@link PlannedItem#toJson} 手写：单元档不带
     * detail 键）。<b>预览必须说得出为什么</b>：只报「解析到 54 个属性」，读者无从
     * 判断那是「都会建」还是「一个都不建」。
     */
    public record AttrNote(String outcome, String detail) {

        /** 会建，用这个 datatype。 */
        public static AttrNote datatype(String dt) {
            return new AttrNote("datatype", dt);
        }

        /** 会建成 text：没写 range，text 是诚实的超集。 */
        public static AttrNote noRange() {
            return new AttrNote("no_range", null);
        }

        /** 会建成 text：range 是可读字面量但四种类型表达不了（xsd:time 等）——报告原 IRI。 */
        public static AttrNote degradedToText(String iri) {
            return new AttrNote("degraded_to_text", iri);
        }

        /** 不建：抽取器不可能从散文里读出这种值（二进制、XML 片段、XML 内部标识）。 */
        public static AttrNote unusableRange(String iri) {
            return new AttrNote("unusable_range", iri);
        }

        /** 不建：没写 domain。属性必须挂在一个类上，这是 store 层的硬约束。 */
        public static AttrNote noDomain() {
            return new AttrNote("no_domain", null);
        }

        /** 不建：domain 指向的类在这个文件里、但被跳过了（多半是 key 撞车）。 */
        public static AttrNote domainSkipped(String iri) {
            return new AttrNote("domain_skipped", iri);
        }

        /** 不建：domain 指向一个这个文件里压根没有的类（外部词汇表）。 */
        public static AttrNote unknownDomain(String iri) {
            return new AttrNote("unknown_domain", iri);
        }
    }

    /**
     * 计划里的一项（PlannedItem）。JSON 形状按 Rust serde 逐字：functional=false /
     * conflict_with=None / attr=None 不进体（skip_serializing_if），has_description
     * 恒在——手写 {@link #toJson} 而不是注解，record 组件上的注解对 boolean 缺省值
     * 的省略语义没有等价物。
     */
    public record PlannedItem(String iri, String key, String label, boolean hasDescription,
                              Disposition disposition, boolean functional, String conflictWith,
                              AttrNote attr) {

        ObjectNode toJson(ObjectMapper m) {
            ObjectNode n = m.createObjectNode();
            n.put("iri", iri);
            n.put("key", key);
            n.put("label", label);
            n.put("has_description", hasDescription);
            n.put("disposition", disposition.wire());
            if (functional) {
                n.put("functional", true);
            }
            if (conflictWith != null) {
                n.put("conflict_with", conflictWith);
            }
            if (attr != null) {
                ObjectNode a = n.putObject("attr");
                a.put("outcome", attr.outcome());
                if (attr.detail() != null) {
                    a.put("detail", attr.detail());
                }
            }
            return n;
        }
    }

    /** 一次导入的完整计划。预览返回它，落库也执行它。 */
    public record ImportPlan(String format, long triples, List<PlannedItem> classes,
                             List<PlannedItem> relations, List<PlannedItem> attributes,
                             List<Object[]> unprojected, long classesWithoutDescription,
                             long functionalRelations) {

        /**
         * 建不出来的属性，按原因分组计数（attr_skips）。<b>预览里报总数是不够的</b>——
         * 「54 个属性」读不出那是都会建还是一个都不建，而实际上取决于原因。
         */
        public Map<String, Long> attrSkips() {
            Map<String, Long> out = new TreeMap<>();
            for (PlannedItem a : attributes) {
                if (a.disposition() == Disposition.KEY_TAKEN) {
                    out.merge("key_taken", 1L, Long::sum);
                    continue;
                }
                if (a.disposition() == Disposition.SUPERSEDED) {
                    out.merge("superseded", 1L, Long::sum);
                    continue;
                }
                if (a.attr() == null) {
                    continue;
                }
                String reason;
                switch (a.attr().outcome()) {
                    case "datatype", "no_range", "degraded_to_text" -> {
                        continue;
                    }
                    case "unusable_range" -> reason = "unusable_range";
                    case "no_domain" -> reason = "no_domain";
                    case "domain_skipped" -> reason = "domain_skipped";
                    case "unknown_domain" -> reason = "unknown_domain";
                    default -> {
                        continue;
                    }
                }
                out.merge(reason, 1L, Long::sum);
            }
            return out;
        }

        /** serde 字段序逐字（format/triples/…/functional_relations）。 */
        ObjectNode toJson(ObjectMapper m) {
            ObjectNode n = m.createObjectNode();
            n.put("format", format);
            n.put("triples", triples);
            ArrayNode cs = n.putArray("classes");
            classes.forEach(c -> cs.add(c.toJson(m)));
            ArrayNode rs = n.putArray("relations");
            relations.forEach(r -> rs.add(r.toJson(m)));
            ArrayNode as = n.putArray("attributes");
            attributes.forEach(a -> as.add(a.toJson(m)));
            ArrayNode un = n.putArray("unprojected");
            unprojected.forEach(u -> un.addArray().add((String) u[0]).add((Integer) u[1]));
            n.put("classes_without_description", classesWithoutDescription);
            n.put("functional_relations", functionalRelations);
            return n;
        }
    }

    /** plan 的产物：计划 + 投影 + 判定的格式（apply 复用投影，不再解析第二遍）。 */
    public record Planned(ImportPlan plan, OwlProjection proj, RdfFormat format) {
    }

    /**
     * 解析文件并对着现有本体算出计划（plan）。不写任何东西。
     */
    public Planned plan(UUID kbId, String filename, byte[] bytes) {
        RdfFormat format = OwlProjector.detect(filename, bytes);
        OwlProjection proj;
        try {
            proj = OwlProjector.project(bytes, format);
        } catch (Exception e) {
            throw AppException.invalidDetail("bad_ontology_file",
                    "Could not parse this ontology file",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }

        // 现有本体：按 IRI 与按 key 各建一份索引，两种冲突分别判
        List<IdKeyIri> etypes = ontology.entityTypesWithIri(kbId);
        List<IdKeyIri> rtypes = ontology.relationTypesWithIri(kbId);
        Map<String, IdKeyIri> eByIri = byIri(etypes);
        Map<String, IdKeyIri> eByKey = byKey(etypes);
        Map<String, IdKeyIri> rByIri = byIri(rtypes);
        Map<String, IdKeyIri> rByKey = byKey(rtypes);

        // 两张 claimed 表连**本次导入内部的 key 撞车**也判：不同 IRI 派生出同一个短标签
        // （FOAF 的 familyName 与 family_name 都成了 family_name）。只对着库里查不够——
        // 第二个在预览里显示「会新建」，落库时被 ON CONFLICT 悄悄丢掉，预览就说了假话。
        // **两个命名空间，不是一个**：类进 entity_types、关系与属性进 relation_types，
        // 各有各的 (kb_id, key) 唯一约束
        Map<String, String> claimedClass = new HashMap<>();
        Map<String, String> claimedProp = new HashMap<>();

        List<PlannedItem> classes = new ArrayList<>();
        for (OwlClass c : proj.classes()) {
            String renamedKey = null;
            Disposition disposition;
            String conflictWith = null;
            String prev = claimedClass.get(c.key());
            if (prev != null) {
                disposition = Disposition.KEY_TAKEN;
                conflictWith = prev;
            } else if (eByIri.containsKey(c.iri())) {
                disposition = Disposition.UPDATE;
            } else if (eByKey.containsKey(c.key())) {
                IdKeyIri existing = eByKey.get(c.key());
                if (existing.iri() == null) {
                    // **占位者没有 IRI：认领它。** 没有 IRI 意味着这个类是本地起的名字
                    //（手工建的），不是另一个词汇表的同名词。导入说的是「这个 IRI 就是
                    // 那个类」——不做这步整棵树就是断的（内置基类一个子类都挂不上）
                    disposition = Disposition.UPDATE;
                } else {
                    Result al = PackAlignment.lookup(c.iri(), existing.iri());
                    if (al != null && al.alignment() == Alignment.SAME_AS) {
                        // 同义：已有的那个就是它，少建一个重复的类正是想要的
                        disposition = Disposition.ALIGNED;
                        conflictWith = existing.iri();
                    } else if (al != null) {
                        // 同名不同义：换一个声明好的 key 建出来（声明的处置，重导入一样）
                        renamedKey = al.renameKey();
                        disposition = Disposition.CREATE;
                    } else {
                        // 表里没有：这是真冲突。**不自动加后缀**——那会让重导入
                        // 认不出自己上次建的是哪个
                        disposition = Disposition.KEY_TAKEN;
                        conflictWith = existing.iri();
                    }
                }
            } else {
                disposition = Disposition.CREATE;
            }
            String key = renamedKey != null ? renamedKey : c.key();
            if (disposition != Disposition.KEY_TAKEN && disposition != Disposition.ALIGNED) {
                claimedClass.put(key, c.iri());
            }
            classes.add(new PlannedItem(c.iri(), key, c.label(),
                    !c.description().trim().isEmpty(), disposition, false, conflictWith, null));
        }

        // 文件里出现过的类（含被跳过的）——区分「被跳过了」与「压根不在这个文件里」
        Set<String> inFile = new HashSet<>();
        for (OwlClass c : proj.classes()) {
            inFile.add(c.iri());
        }
        Set<String> resolvable = new HashSet<>(eByIri.keySet());
        for (PlannedItem c : classes) {
            if (c.disposition() != Disposition.KEY_TAKEN) {
                resolvable.add(c.iri());
            }
        }

        // 让位的属性只在取代它的那条**能落地**时才跳过：取代者在这个文件里，或库里已有。
        // 取代者不在场时照建——否则一份只带了让位方的小词表会一条都建不出来
        Set<String> propIris = new HashSet<>();
        for (OwlProperty p : proj.properties()) {
            propIris.add(p.iri());
        }
        List<PlannedItem> relations = new ArrayList<>();
        List<PlannedItem> attributes = new ArrayList<>();
        for (OwlProperty p : proj.properties()) {
            String successor = p.supersededBy() != null
                    && (propIris.contains(p.supersededBy()) || rByIri.containsKey(p.supersededBy()))
                    ? p.supersededBy() : null;
            Disposition disposition;
            String conflictWith = null;
            if (successor != null) {
                disposition = Disposition.SUPERSEDED;
                conflictWith = successor;
            } else if (claimedProp.containsKey(p.key())) {
                disposition = Disposition.KEY_TAKEN;
                conflictWith = claimedProp.get(p.key());
            } else if (rByIri.containsKey(p.iri())) {
                disposition = Disposition.UPDATE;
            } else if (rByKey.containsKey(p.key())) {
                disposition = Disposition.KEY_TAKEN;
                conflictWith = rByKey.get(p.key()).iri();
            } else {
                disposition = Disposition.CREATE;
            }
            if (disposition != Disposition.KEY_TAKEN && disposition != Disposition.SUPERSEDED) {
                claimedProp.put(p.key(), p.iri());
            }
            PlannedItem item = new PlannedItem(p.iri(), p.key(), p.label(),
                    !p.description().trim().isEmpty(), disposition, p.functional(), conflictWith,
                    null);
            if (p.isDatatype()) {
                // **domain 先判**：没有 domain 的属性根本建不出来，range 是什么已不重要
                item = new PlannedItem(item.iri(), item.key(), item.label(),
                        item.hasDescription(), item.disposition(), item.functional(),
                        item.conflictWith(),
                        attrNote(p, proj.vocabDatatypes(), resolvable, inFile));
                attributes.add(item);
            } else {
                relations.add(item);
            }
        }

        List<Object[]> unprojected = new ArrayList<>();
        proj.unprojected().entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .forEach(e -> unprojected.add(new Object[] {e.getKey(), e.getValue()}));
        ImportPlan plan = new ImportPlan(format.wireName(), proj.triples(), classes, relations,
                attributes, unprojected,
                classes.stream().filter(c -> !c.hasDescription()).count(),
                relations.stream().filter(r -> r.functional()).count());
        return new Planned(plan, proj, format);
    }

    private static Map<String, IdKeyIri> byIri(List<IdKeyIri> rows) {
        Map<String, IdKeyIri> out = new HashMap<>();
        for (IdKeyIri r : rows) {
            if (r.iri() != null) {
                out.putIfAbsent(r.iri(), r);
            }
        }
        return out;
    }

    private static Map<String, IdKeyIri> byKey(List<IdKeyIri> rows) {
        Map<String, IdKeyIri> out = new HashMap<>();
        for (IdKeyIri r : rows) {
            out.putIfAbsent(r.key(), r);
        }
        return out;
    }

    /**
     * 属性的去向（attr_note）。**domain 先判**：一个都解析不出来才算失败——多个
     * domain 里有一部分被跳过时，属性仍然建，只是少挂几个类（比整条丢掉强）。
     */
    private static AttrNote attrNote(OwlProperty p, Map<String, String> vocab,
                                     Set<String> resolvable, Set<String> inFile) {
        if (p.domains().isEmpty()) {
            return AttrNote.noDomain();
        }
        boolean any = p.domains().stream().anyMatch(resolvable::contains);
        if (!any) {
            String first = p.domains().get(0);
            return inFile.contains(first) ? AttrNote.domainSkipped(first)
                    : AttrNote.unknownDomain(first);
        }
        RangeMapping m = OwlProjector.mapRangeOf(p, vocab);
        if (m instanceof RangeMapping.Datatype dt) {
            return AttrNote.datatype(dt.datatype());
        }
        if (m instanceof RangeMapping.Absent) {
            return AttrNote.noRange();
        }
        if (m instanceof RangeMapping.Degraded d) {
            return AttrNote.degradedToText(d.iri());
        }
        return AttrNote.unusableRange(((RangeMapping.Unusable) m).iri());
    }

    /**
     * 属性会不会被建出来，以及用什么 datatype（attr_datatype）。落库与统计共用它，
     * 免得「预览说会建」和「实际建了」两处各判一次而分叉。
     */
    static String attrDatatype(AttrNote note) {
        return switch (note.outcome()) {
            // 没写 range，或写了但类型表达不了：值仍是字面量，text 是拦不住任何东西的
            // 诚实超集
            case "datatype" -> note.detail();
            case "no_range", "degraded_to_text" -> "text";
            default -> null;
        };
    }

    /** apply 的产物：导入 id + 执行的计划。 */
    public record Applied(UUID importId, ImportPlan plan) {
    }

    /**
     * 执行计划（apply）。属性在类之后落库——它们要挂在 domain 上，而 domain 要等类
     * 先建好并解析 IRI → id（就是下面那个 id_of）。
     */
    public Applied apply(UUID kbId, UUID actor, String filename, byte[] bytes) {
        Planned planned = plan(kbId, filename, bytes);
        ImportPlan plan = planned.plan();
        OwlProjection proj = planned.proj();

        // 第一层：原文按内容寻址进 blob。**先存原文再改本体**——投影失败可以重来，
        // 原文丢了就永远回不去
        String sha = sha256Hex(bytes);
        try {
            blob.put(sha, bytes);
        } catch (Exception e) {
            throw AppException.other("could not store the ontology file", e);
        }

        Map<String, OwlClass> byIri = new HashMap<>();
        for (OwlClass c : proj.classes()) {
            byIri.put(c.iri(), c);
        }
        // 已在库里的类：IRI → id。Aligned 要靠它把「同义的那个」接上父类引用
        Map<String, UUID> existingByIri = new HashMap<>();
        for (IdKeyIri t : ontology.entityTypesWithIri(kbId)) {
            if (t.iri() != null) {
                existingByIri.put(t.iri(), t.id());
            }
        }
        int createdClasses = 0;
        int updatedClasses = 0;
        // IRI → 本体里的 id，父类解析要用
        Map<String, UUID> idOf = new HashMap<>();

        // **新建的类一次插完，不逐条来。** 逐条 execute 每条各自提交，schema.org 那种
        // 量级是五千次 fsync——实测 45 秒；同样的行数放进一条 UNNEST 语句是 536 毫秒。
        // Update 与 Aligned 留在下面逐条走：空库上是个位数，且各自要读一次现状
        List<BulkClass> newClasses = new ArrayList<>();
        for (PlannedItem item : plan.classes()) {
            if (item.disposition() != Disposition.CREATE) {
                continue;
            }
            OwlClass c = byIri.get(item.iri());
            if (c == null) {
                continue;
            }
            // key 用 item 的：对齐表判为同名不同义时它是改过的那个
            newClasses.add(new BulkClass(item.key(), c.label(), c.description(), c.iri()));
        }
        Map<String, UUID> createdIds = ontology.createEntityTypesBulk(kbId, newClasses);
        for (BulkClass c : newClasses) {
            UUID id = createdIds.get(c.key());
            if (id != null) {
                idOf.put(c.iri(), id);
                createdClasses++;
            }
        }

        for (PlannedItem item : plan.classes()) {
            OwlClass c = byIri.get(item.iri());
            if (c == null) {
                continue;
            }
            switch (item.disposition()) {
                case CREATE -> {
                    // 上面已经批量建好了
                }
                case UPDATE -> {
                    // 两种 Update：这个 IRI 上次导过（按 IRI 找得到），或者它要认领一个
                    // 同名的本地类（按 key 找，且那一行还没有 IRI）
                    UUID id = ontology.updateTypeFromImport(kbId, c.iri(), c.label(),
                            c.description());
                    if (id == null) {
                        id = ontology.adoptIriOntoKey(kbId, c.key(), c.iri());
                    }
                    if (id != null) {
                        idOf.put(c.iri(), id);
                        updatedClasses++;
                    }
                }
                // key 被占：报告过了，不动。同义：不建——但要把 IRI 指向已有那个类的
                // id，否则以它为父类的子类会解析不到，整棵树在这里断掉
                case ALIGNED -> {
                    if (item.conflictWith() != null) {
                        UUID target = existingByIri.get(item.conflictWith());
                        if (target != null) {
                            idOf.put(c.iri(), target);
                        }
                    }
                }
                // 类没有让位一说（schema:supersededBy 只标属性）
                default -> {
                }
            }
        }

        // 父类第二遍解析：第一遍时父类可能还没建出来。(子, 父) 边先攒着，一次插完。
        // **全部父类**——FOAF 的 Person 同时是 Agent 与 SpatialThing。指向没被建出来
        // 的类的那些父自然落选——少一支比整条不挂强
        List<Map.Entry<UUID, UUID>> parentEdges = new ArrayList<>();
        for (OwlClass c : proj.classes()) {
            UUID child = idOf.get(c.iri());
            if (child == null) {
                continue;
            }
            for (String iri : c.parents()) {
                UUID parent = idOf.get(iri);
                if (parent != null) {
                    parentEdges.add(Map.entry(child, parent));
                }
            }
        }
        // 批量版不查环：环是上游词汇表的问题，单条版的处置本来也只是跳过那一条
        ontology.setParentsBulk(parentEdges);

        // 类互斥同理攒一批（两方向已由投影侧展开；指向没建出来的类的自然落选）
        List<Map.Entry<UUID, UUID>> disjointEdges = new ArrayList<>();
        for (OwlClass c : proj.classes()) {
            UUID a = idOf.get(c.iri());
            if (a == null) {
                continue;
            }
            for (String other : c.disjointWith()) {
                UUID b = idOf.get(other);
                if (b != null) {
                    disjointEdges.add(Map.entry(a, b));
                }
            }
        }
        ontology.setDisjointBulk(kbId, disjointEdges);

        Map<String, OwlProperty> byPropIri = new HashMap<>();
        for (OwlProperty p : proj.properties()) {
            byPropIri.put(p.iri(), p);
        }
        // 关系。functional/inverse_functional 照词汇表的声明写下去：它们是时态引擎自动
        // 闭合事实的依据，猜错会成批造假冲突（part_of 那次 59 条）。所以不猜——预览已经
        // 把它们单独列出来让人过目
        int createdRels = 0;
        int updatedRels = 0;
        List<BulkRelation> newRels = new ArrayList<>();
        // (key, domains, ranges)：关系 id 要等批量插完才有，先按 key 记着
        record PendingLink(String key, List<UUID> domains, List<UUID> ranges) {
        }
        List<PendingLink> pendingLinks = new ArrayList<>();
        for (PlannedItem item : plan.relations()) {
            if (item.disposition() == Disposition.KEY_TAKEN
                    || item.disposition() == Disposition.SUPERSEDED) {
                continue;
            }
            OwlProperty p = byPropIri.get(item.iri());
            if (p == null) {
                continue;
            }
            // domain/range 指向没被建出来的类时只丢那一个，不丢整条关系。**库里已有的
            // 类也算数**——从前只认本文件里的类，W3C Org 的 headOf rdfs:domain foaf:Agent
            // 在 FOAF 已经装好的库里照样丢 domain（#222）
            List<UUID> domains = resolve(p.domains(), idOf, existingByIri);
            List<UUID> ranges = resolve(p.ranges(), idOf, existingByIri);
            if (item.disposition() == Disposition.UPDATE) {
                if (ontology.updateRelationFromImport(kbId, p.iri(), p.label(), p.description(),
                        domains, ranges)) {
                    updatedRels++;
                }
                continue;
            }
            newRels.add(new BulkRelation(p.key(), p.label(), p.description(), p.iri(),
                    "relation", null, p.functional(), p.inverseFunctional(), p.transitive(),
                    p.symmetric(), p.asymmetric(), p.irreflexive()));
            pendingLinks.add(new PendingLink(p.key(), domains, ranges));
        }
        Map<String, UUID> relIds = ontology.createRelationTypesBulk(kbId, newRels);
        createdRels += relIds.size();

        // 逆与父属性指的是**另一个关系类型**，id 要等全部插完才有——所以是第二遍。
        // 新建的与已存在的都收：一份本体重导时，这两条声明可能是这次才加上的
        List<Map.Entry<String, String>> invPairs = new ArrayList<>();
        List<Map.Entry<String, String>> subPairs = new ArrayList<>();
        for (PlannedItem item : plan.relations()) {
            OwlProperty p = byPropIri.get(item.iri());
            if (p == null) {
                continue;
            }
            if (p.inverseOf() != null) {
                invPairs.add(Map.entry(p.iri(), p.inverseOf()));
            }
            if (p.subPropertyOf() != null) {
                subPairs.add(Map.entry(p.iri(), p.subPropertyOf()));
            }
        }
        long[] linked = ontology.linkPropertyAxiomsBulk(kbId, invPairs, subPairs);
        List<Map.Entry<UUID, UUID>> linkD = new ArrayList<>();
        List<Map.Entry<UUID, UUID>> linkR = new ArrayList<>();
        for (PendingLink pending : pendingLinks) {
            UUID rid = relIds.get(pending.key());
            if (rid == null) {
                continue;
            }
            for (UUID d : pending.domains()) {
                linkD.add(Map.entry(rid, d));
            }
            for (UUID r : pending.ranges()) {
                linkR.add(Map.entry(rid, r));
            }
        }
        ontology.linkDomainsRangesBulk(linkD, linkR);

        // 属性：类建完、id_of 填好之后才轮到它们。**这里只执行计划，不重新判断**——
        // 两处各判一次就会分叉，分叉意味着预览说的和实际做的不是一回事
        int createdAttrs = 0;
        List<BulkRelation> newAttrs = new ArrayList<>();
        List<PendingLink> pendingAttrDomains = new ArrayList<>();
        for (PlannedItem item : plan.attributes()) {
            if (item.disposition() == Disposition.KEY_TAKEN
                    || item.disposition() == Disposition.SUPERSEDED) {
                continue;
            }
            if (item.attr() == null) {
                continue;
            }
            OwlProperty p = byPropIri.get(item.iri());
            if (p == null) {
                continue;
            }
            String dt = attrDatatype(item.attr());
            if (dt == null) {
                continue;
            }
            // 计划阶段判为可解析、落库时却没有 id 的（类被跳过或更新失败）自然落选；
            // 全落选就不建，而不是造一个挂空的属性
            List<UUID> domainIds = new ArrayList<>();
            for (String iri : p.domains()) {
                UUID id = idOf.get(iri);
                if (id != null) {
                    domainIds.add(id);
                }
            }
            if (domainIds.isEmpty()) {
                continue;
            }
            // 属性不参与时态闭合，也不参与一致性检查：那几类判定都是关于实体之间的边，
            // 而属性的宾语是字面值
            newAttrs.add(new BulkRelation(p.key(), p.label(), p.description(), p.iri(),
                    "attribute", dt, false, false, false, false, false, false));
            pendingAttrDomains.add(new PendingLink(p.key(), domainIds, List.of()));
        }
        Map<String, UUID> attrIds = ontology.createRelationTypesBulk(kbId, newAttrs);
        createdAttrs += attrIds.size();
        List<Map.Entry<UUID, UUID>> attrLinks = new ArrayList<>();
        for (PendingLink pending : pendingAttrDomains) {
            UUID aid = attrIds.get(pending.key());
            if (aid == null) {
                continue;
            }
            for (UUID d : pending.domains()) {
                attrLinks.add(Map.entry(aid, d));
            }
        }
        ontology.linkDomainsRangesBulk(attrLinks, List.of());

        ObjectNode summary = JSON.createObjectNode();
        summary.put("classes_created", createdClasses);
        summary.put("classes_updated", updatedClasses);
        summary.put("classes_key_taken", plan.classes().stream()
                .filter(c -> c.disposition() == Disposition.KEY_TAKEN).count());
        summary.put("relations_seen", plan.relations().size());
        // 连上了几条逆/父属性。**报出来**——目标 IRI 不在这个库里时会静默跳过
        //（引用外部词汇表是常态），不给数就没人知道少连了多少
        summary.put("inverse_linked", linked[0]);
        summary.put("sub_property_linked", linked[1]);
        summary.put("relations_created", createdRels);
        // 词表自己让位的那些：不建，也不算冲突
        summary.put("relations_superseded", plan.relations().stream()
                .filter(r -> r.disposition() == Disposition.SUPERSEDED).count());
        summary.put("relations_updated", updatedRels);
        summary.put("attributes_seen", plan.attributes().size());
        summary.put("attributes_created", createdAttrs);
        ObjectNode skips = summary.putObject("attributes_skipped");
        plan.attrSkips().forEach(skips::put);
        summary.put("classes_without_description", plan.classesWithoutDescription());
        summary.put("functional_relations", plan.functionalRelations());
        ArrayNode unprojectedNode = summary.putArray("unprojected");
        plan.unprojected().stream().limit(30).forEach(
                u -> unprojectedNode.addArray().add((String) u[0]).add((Integer) u[1]));
        summary.put("triples", plan.triples());
        UUID importId = ontology.recordImport(kbId, sha, filename, planned.format().wireName(),
                bytes.length, summary, actor);

        audit.recordOpt(null, kbId, actor, "ontology.imported", "kb", kbId,
                summary.toString());
        // 本体刚变过，向量都是陈的。**排任务而不是就地跑**：这一趟要嵌几千行，放在导入
        // 请求里，用户点完确认要多等六到八分钟。排不上也不要紧——索引是自愈的
        try {
            jobs.enqueue("embed_ontology",
                    JSON.createObjectNode().put("kb_id", kbId.toString()));
        } catch (Exception e) {
            log.warn("kb={} embed_ontology 没排上（索引自愈，下一个用到检索的人会补）: {}",
                    kbId, e.getMessage());
        }
        return new Applied(importId, plan);
    }

    /** domain/range 的 IRI → id：本文件新建/更新的优先，库里已有的算数。 */
    private static List<UUID> resolve(List<String> iris, Map<String, UUID> idOf,
                                      Map<String, UUID> existingByIri) {
        List<UUID> out = new ArrayList<>();
        for (String iri : iris) {
            UUID id = idOf.get(iri);
            if (id == null) {
                id = existingByIri.get(iri);
            }
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * 装完一组包之后再过一遍（relink_domains_ranges）：把 domain/range 指向<b>别的包
     * 里的类</b>的那些关系接上。单次导入认本文件里的类和库里已有的类，但包是挨个装
     * 的：装 W3C Org 时 FOAF 还没来，headOf 的 rdfs:domain foaf:Agent 就落了空；等 FOAF
     * 装好，没人回头补（#222）。只补不删，ON CONFLICT DO NOTHING——重复跑无害。
     * 属性不在这里：属性的去向在计划阶段就定了。
     *
     * @return (补上的 domain 边数, 补上的 range 边数)
     */
    public int[] relinkDomainsRanges(UUID kbId, String filename, byte[] bytes) {
        RdfFormat format = OwlProjector.detect(filename, bytes);
        OwlProjection proj;
        try {
            proj = OwlProjector.project(bytes, format);
        } catch (Exception e) {
            throw AppException.invalidDetail("bad_ontology_file",
                    "Could not parse this ontology file",
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        Map<String, UUID> classes = new HashMap<>();
        for (IdKeyIri t : ontology.entityTypesWithIri(kbId)) {
            if (t.iri() != null) {
                classes.put(t.iri(), t.id());
            }
        }
        Map<String, UUID> relations = new HashMap<>();
        for (IdKeyIri r : ontology.relationTypesWithIri(kbId)) {
            if (r.iri() != null) {
                relations.put(r.iri(), r.id());
            }
        }
        List<Map.Entry<UUID, UUID>> linkD = new ArrayList<>();
        List<Map.Entry<UUID, UUID>> linkR = new ArrayList<>();
        for (OwlProperty p : proj.properties()) {
            if (p.isDatatype()) {
                continue;
            }
            UUID rid = relations.get(p.iri());
            if (rid == null) {
                continue;
            }
            for (String d : p.domains()) {
                UUID t = classes.get(d);
                if (t != null) {
                    linkD.add(Map.entry(rid, t));
                }
            }
            for (String r : p.ranges()) {
                UUID t = classes.get(r);
                if (t != null) {
                    linkR.add(Map.entry(rid, t));
                }
            }
        }
        ontology.linkDomainsRangesBulk(linkD, linkR);
        return new int[] {linkD.size(), linkR.size()};
    }

    /** sha256 的十六进制（sha256_hex；包外测试断言 blob 内容寻址要用）。 */
    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
