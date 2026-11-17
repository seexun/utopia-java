package tech.seexun.jknow.web.knowledge.export;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.OutputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.system.StreamRDF;
import org.apache.jena.riot.system.StreamRDFWriter;
import tech.seexun.jknow.store.ExportStore.ExportAxiomViolation;
import tech.seexun.jknow.store.ExportStore.ExportClass;
import tech.seexun.jknow.store.ExportStore.ExportDerived;
import tech.seexun.jknow.store.ExportStore.ExportDocument;
import tech.seexun.jknow.store.ExportStore.ExportEntity;
import tech.seexun.jknow.store.ExportStore.ExportFact;
import tech.seexun.jknow.store.ExportStore.ExportFactConflict;
import tech.seexun.jknow.store.ExportStore.ExportRelation;
import tech.seexun.jknow.store.GraphWriteService.FactQualifier;

/**
 * 账本 → RDF 的序列化器（rdf.rs 主体逐字移植；Jena StreamRDF 对等 oxrdfio Writer）。
 *
 * <p>读的人不是另一个 Utopia，是一个手里有三元组库、要问「这个结论凭什么」的人。所以
 * 导出的内容是<b>区间与出处</b>，不只是当下的那些边——一份读起来干净自信、却没说其中
 * 一半在三月被撤回过的图，比没有导出更坏。三件事定了这份文件的形状：
 * <ol>
 *   <li>导入来的类和关系留着原 IRI（Names 已管）；</li>
 *   <li>区间挂在具体化语句上（rdf:Statement），不是挂在三元组上——RDF-star 多数消费者
 *       还读不了，命名图在 Turtle 里根本没有；</li>
 *   <li>有标准词就不自造：prov:invalidatedAtTime 说的正是我们记录轴上那件事。</li>
 * </ol>
 *
 * <p>两种格式：Turtle 走 Jena 的流式 writer（三元组即到即写，几十万条事实不在内存里拼
 * 整份文件）；JSON-LD 走 {@link JsonLdGroupedWriter}——按主语分组外写（oxrdfio
 * JsonLdProfile::Streaming 的语义对等；Jena 的 StreamRDFWriter 不提供 JSON-LD 流）。
 */
public final class RdfEmitter {

    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String PROV = "http://www.w3.org/ns/prov#";
    private static final String SCHEMA = "https://schema.org/";
    /** 没有标准词的那几样才落在这里（置信度、派生标记、模型原话）。URN 而非 http：一个
     *  词汇表 IRI 一旦发出去就不该再改——指向一个我们并不提供的地址，比指向一个不承诺
     *  解引用的 URN 更糟。 */
    private static final String UTOPIA = "urn:utopia:ns:";

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDF_FIRST = "http://www.w3.org/1999/02/22-rdf-syntax-ns#first";
    private static final String RDF_REST = "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest";
    private static final String RDF_NIL = "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil";
    private static final String RDF_STATEMENT =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement";
    private static final String RDF_SUBJECT =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#subject";
    private static final String RDF_PREDICATE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate";
    private static final String RDF_OBJECT = "http://www.w3.org/1999/02/22-rdf-syntax-ns#object";
    private static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    private static final String RDFS_COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";
    private static final String RDFS_SUBCLASS = "http://www.w3.org/2000/01/rdf-schema#subClassOf";
    private static final String RDFS_SUBPROPERTY =
            "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";
    private static final String RDFS_DOMAIN = "http://www.w3.org/2000/01/rdf-schema#domain";
    private static final String RDFS_RANGE = "http://www.w3.org/2000/01/rdf-schema#range";

    /** 前缀表（rdf.rs:164-171 常量 7 条）。Turtle 里它决定文件读起来是 schema:Organization 还是一长串尖括号。 */
    private static final String[][] PREFIXES = {
            {"rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"},
            {"rdfs", "http://www.w3.org/2000/01/rdf-schema#"},
            {"owl", OWL},
            {"xsd", "http://www.w3.org/2001/XMLSchema#"},
            {"prov", PROV},
            {"schema", SCHEMA},
            {"utopia", UTOPIA},
    };

    private final StreamRDF sink;

    private RdfEmitter(StreamRDF sink) {
        this.sink = sink;
    }

    /** 开写：声明前缀表（常量，不会解析失败）。 */
    public static RdfEmitter start(ExportFormat format, OutputStream out) throws IOException {
        StreamRDF stream = format == ExportFormat.TURTLE
                ? StreamRDFWriter.getWriterStream(out, Lang.TURTLE)
                : new JsonLdGroupedWriter(out);
        stream.start();
        for (String[] p : PREFIXES) {
            stream.prefix(p[0], p[1]);
        }
        return new RdfEmitter(stream);
    }

    public void finish() {
        sink.finish();
    }

    private void r(String s, String p, String o) {
        sink.triple(Triple.create(node(s), node(p), node(o)));
    }

    private void l(String s, String p, Node o) {
        sink.triple(Triple.create(node(s), node(p), o));
    }

    private void raw(String s, String p, Node o) {
        sink.triple(Triple.create(node(s), node(p), o));
    }

    private static Node node(String iri) {
        return org.apache.jena.graph.NodeFactory.createURI(iri);
    }

    private static String owl(String term) {
        return OWL + term;
    }

    private static String prov(String term) {
        return PROV + term;
    }

    private static String schema(String term) {
        return SCHEMA + term;
    }

    private static String utopia(String term) {
        return UTOPIA + term;
    }

    // =====================================================================
    // 词汇表（rdf.rs:285-323）
    // =====================================================================

    /** 一次导出里要反复查的东西：类与关系的 IRI、属性的值域。 */
    public static final class Vocabulary {

        private final Map<UUID, String> classes = new HashMap<>();
        private final Map<UUID, Relation> relations = new HashMap<>();

        private record Relation(String iri, String datatype, String unit) {
        }

        public String cls(UUID id) {
            return classes.get(id);
        }

        public String relation(UUID id) {
            Relation r = relations.get(id);
            return r == null ? null : r.iri();
        }

        /** (datatype, unit)。 */
        String[] literalShape(UUID id) {
            Relation r = relations.get(id);
            return r == null ? new String[]{null, null} : new String[]{r.datatype(), r.unit()};
        }
    }

    public static Vocabulary vocabulary(Names names, List<ExportClass> classes,
                                        List<ExportRelation> relations) {
        Vocabulary v = new Vocabulary();
        for (ExportClass c : classes) {
            v.classes.put(c.id(), names.cls(c));
        }
        for (ExportRelation r : relations) {
            v.relations.put(r.id(), new Vocabulary.Relation(names.relation(r), r.datatype(),
                    r.unit()));
        }
        return v;
    }

    // =====================================================================
    // 八族 emit（rdf.rs:325-764 逐字）
    // =====================================================================

    public void emitClass(Vocabulary vocab, ExportClass c) {
        String iri = vocab.cls(c.id());
        if (iri == null) {
            return;
        }
        r(iri, RDF_TYPE, owl("Class"));
        l(iri, RDFS_LABEL, RdfLiterals.text(c.label()));
        if (!c.description().isEmpty()) {
            l(iri, RDFS_COMMENT, RdfLiterals.text(c.description()));
        }
        for (UUID parent : c.parents()) {
            String p = vocab.cls(parent);
            if (p != null) {
                r(iri, RDFS_SUBCLASS, p);
            }
        }
        for (UUID other : c.disjoint()) {
            String o = vocab.cls(other);
            if (o != null) {
                r(iri, owl("disjointWith"), o);
            }
        }
    }

    public void emitRelation(Vocabulary vocab, ExportRelation rel) {
        String iri = vocab.relation(rel.id());
        if (iri == null) {
            return;
        }
        r(iri, RDF_TYPE, owl("attribute".equals(rel.kind()) ? "DatatypeProperty" : "ObjectProperty"));
        l(iri, RDFS_LABEL, RdfLiterals.text(rel.label()));
        if (!rel.description().isEmpty()) {
            l(iri, RDFS_COMMENT, RdfLiterals.text(rel.description()));
        }
        // 公理照抄，一条不落：一致性检查就是按它们跑的，读的人要能自己复算
        if (rel.functional()) {
            r(iri, RDF_TYPE, owl("FunctionalProperty"));
        }
        if (rel.inverseFunctional()) {
            r(iri, RDF_TYPE, owl("InverseFunctionalProperty"));
        }
        if (rel.isTransitive()) {
            r(iri, RDF_TYPE, owl("TransitiveProperty"));
        }
        if (rel.isSymmetric()) {
            r(iri, RDF_TYPE, owl("SymmetricProperty"));
        }
        if (rel.isAsymmetric()) {
            r(iri, RDF_TYPE, owl("AsymmetricProperty"));
        }
        if (rel.isIrreflexive()) {
            r(iri, RDF_TYPE, owl("IrreflexiveProperty"));
        }
        // 只复制已声明的边；不补反向声明或传递闭包，目标也只在本库词汇表中找
        if (rel.inverseOf() != null) {
            String target = vocab.relation(rel.inverseOf());
            if (target != null) {
                r(iri, owl("inverseOf"), target);
            }
        }
        if (rel.subPropertyOf() != null) {
            String target = vocab.relation(rel.subPropertyOf());
            if (target != null) {
                r(iri, RDFS_SUBPROPERTY, target);
            }
        }
        // 时间语义也照抄（0031）：读的人不看这一条，会把 event 读成一天的状态、
        // eternal 读成从不知何时起。状态是默认，不写
        if (!"state".equals(rel.temporal())) {
            l(iri, utopia("temporal"), RdfLiterals.text(rel.temporal()));
        }
        for (UUID d : rel.domains()) {
            String c = vocab.cls(d);
            if (c != null) {
                r(iri, RDFS_DOMAIN, c);
            }
        }
        for (UUID g : rel.ranges()) {
            String c = vocab.cls(g);
            if (c != null) {
                r(iri, RDFS_RANGE, c);
            }
        }
        if (rel.unit() != null) {
            l(iri, utopia("unit"), RdfLiterals.text(rel.unit()));
        }
    }

    public void emitEntity(Names names, Vocabulary vocab, ExportEntity e) {
        String iri = names.entity(e.id());
        l(iri, RDFS_LABEL, RdfLiterals.text(e.canonicalName()));
        // 类型可以没有（0009）：没判出来就不写，而不是补一个 owl:Thing 充数
        if (e.typeId() != null) {
            String t = vocab.cls(e.typeId());
            if (t != null) {
                r(iri, RDF_TYPE, t);
            }
        }
    }

    public void emitDocument(Names names, ExportDocument d) {
        String iri = names.document(d.id());
        r(iri, RDF_TYPE, prov("Entity"));
        l(iri, RDFS_LABEL, RdfLiterals.text(d.filename()));
        if (d.externalKey() != null) {
            l(iri, utopia("externalKey"), RdfLiterals.text(d.externalKey()));
        }
        if (d.docTime() != null) {
            l(iri, schema("datePublished"), RdfLiterals.worldTime(d.docTime(), "day"));
        }
        l(iri, prov("generatedAtTime"), RdfLiterals.dt(d.createdAt()));
        // 删掉的文档仍在文件里：它的事实还挂着它当出处，抹掉出处等于抹掉证据链
        if (d.deletedAt() != null) {
            l(iri, prov("invalidatedAtTime"), RdfLiterals.dt(d.deletedAt()));
        }
    }

    /** 一条事实：具体化语句（必出）+ 现行三元组（只在现在仍持有且仍有效时出）。 */
    public void emitFact(Names names, Vocabulary vocab, ExportFact f, OffsetDateTime now) {
        String stmt = names.fact(f.id());
        String subject = names.entity(f.subjectId());
        String predicate = f.predicateId() == null ? null : vocab.relation(f.predicateId());
        Node object;
        if (f.objectId() != null) {
            object = node(names.entity(f.objectId()));
        } else if (f.objectValue() != null) {
            // An unbound statement still has an object; only its datatype is unknown.
            // #821：解析不出来就别写，避免 rdf:object="" 这种空字面量把审计工具
            // 误导成「事实无对象」；老代码的 {"summary":…} 整对象序列化漏洞是 #831
            String datatype = f.predicateId() == null ? null : vocab.literalShape(f.predicateId())[0];
            object = RdfLiterals.literalValue(f.objectValue(), datatype);
        } else {
            object = null;
        }

        r(stmt, RDF_TYPE, RDF_STATEMENT);
        r(stmt, RDF_SUBJECT, subject);
        if (predicate != null) {
            r(stmt, RDF_PREDICATE, predicate);
        } else if (f.surfacePredicate() != null) {
            // 本体没接住这条关系（0010）：**不造一个谓词**。原话作为字面值留在这里，
            // 读的人看得见「系统听见的是这个词，而词汇表里没有它」
            l(stmt, utopia("proposedPredicate"), RdfLiterals.text(f.surfacePredicate()));
        }
        if (object != null) {
            raw(stmt, RDF_OBJECT, object);
        }
        // 只相对一件事给出的值（「触发日后 45 天」，#681 §4）：字面量是原文，这一行说它不是日期
        if (f.objectValue() != null && RdfLiterals.isRelative(f.objectValue())) {
            l(stmt, utopia("relativeValue"), RdfLiterals.flag(true));
        }
        emitValidity(stmt, f.validFrom(), f.validFromPrecision(), f.validTo(), f.validToPrecision());
        l(stmt, prov("generatedAtTime"), RdfLiterals.dt(f.recordedAt()));
        if (f.invalidatedAt() != null) {
            l(stmt, prov("invalidatedAtTime"), RdfLiterals.dt(f.invalidatedAt()));
        }
        l(stmt, utopia("confidence"), RdfLiterals.confidence(f.confidence()));
        // 规则算出来的（0044 决定 3 第五片）：不是文档直接陈述的，审计的人要看得见这一层
        if (f.implied()) {
            l(stmt, utopia("implied"), RdfLiterals.flag(true));
        }
        if (f.supersedes() != null) {
            r(stmt, utopia("supersedes"), names.fact(f.supersedes()));
        }
        for (UUID doc : f.documents()) {
            r(stmt, prov("wasDerivedFrom"), names.document(doc));
        }
        for (String quote : f.quotes()) {
            l(stmt, utopia("quote"), RdfLiterals.text(quote));
        }
        // 引文从哪来（0040）：审计的人要不问我们就分得清一句原文和一段看图描述（0020）
        for (String origin : f.quoteOrigins()) {
            l(stmt, utopia("evidenceOrigin"), RdfLiterals.text(origin));
        }
        // 边上的属性（0037）：陈述节点上各多一行，谓词是属性的 IRI，字面量按它的 datatype
        for (FactQualifier q : f.qualifiers()) {
            String p = vocab.relation(q.qualifierTypeId());
            if (p == null) {
                continue;
            }
            if (q.value() != null) {
                String datatype = vocab.literalShape(q.qualifierTypeId())[0];
                // #821 + #831：解析不出来就别写这条边上的属性，别塞个空字面量
                Node lit = RdfLiterals.literalValue(q.value(), datatype);
                if (lit != null) {
                    l(stmt, p, lit);
                }
            } else if (q.entityId() != null) {
                r(stmt, p, names.entity(q.entityId()));
            }
        }

        // 现行三元组：**仍被持有，且现在仍成立**。区间已闭合或已撤回的不写这一条，
        // 否则一个忽略具体化的消费者会读到「张三现在还管着那个项目」。「现在成立」按
        // 读出来的区间判（0022）：没起点的从最早证据起，结束了不知哪天的到说出它的
        // 那份文档为止。规则在 world_axis，这里只看投影出来的两端
        boolean held = f.invalidatedAt() == null;
        boolean holdsNow = f.holdsFrom() != null && !f.holdsFrom().isAfter(now)
                && (f.holdsTo() == null || f.holdsTo().isAfter(now));
        if (held && holdsNow && predicate != null && object != null) {
            sink.triple(Triple.create(node(subject), node(predicate), object));
        }
    }

    /**
     * 派生事实（0002）。<b>不写现行三元组</b>：它是推出来的，不是谁断言的；一个忽略
     * 具体化的消费者不该把引擎的结论当成文档里的话。
     */
    public void emitDerived(Names names, Vocabulary vocab, ExportDerived d) {
        String stmt = names.derived(d.id());
        // 推理活动的身份：公理规则有自己的 id，业务规则用它的。两者都要有一个稳定的
        // IRI，否则审计读到一条结论却指不出「凭什么」
        String rule;
        if (d.ruleId() != null) {
            rule = names.rule(d.ruleId());
        } else if (d.attributeRuleId() != null) {
            rule = names.rule(d.attributeRuleId());
        } else {
            // 库里的 CHECK 保证不会两个都空；真到了这一步宁可跳过整条
            return;
        }
        r(stmt, RDF_TYPE, RDF_STATEMENT);
        r(stmt, RDF_SUBJECT, names.entity(d.subjectId()));
        String predicate = vocab.relation(d.predicateId());
        if (predicate != null) {
            r(stmt, RDF_PREDICATE, predicate);
        }
        // 宾语两条通道，与断言事实同一套：实体走 IRI，字面值结论走字面量（0021）
        if (d.objectId() != null) {
            r(stmt, RDF_OBJECT, names.entity(d.objectId()));
        } else if (d.objectValue() != null) {
            String datatype = vocab.literalShape(d.predicateId())[0];
            // #821 + #831：解析不出来就别写这条宾语
            Node lit = RdfLiterals.literalValue(d.objectValue(), datatype);
            if (lit != null) {
                l(stmt, RDF_OBJECT, lit);
            }
        }
        l(stmt, utopia("derived"), RdfLiterals.flag(true));
        emitValidity(stmt, d.validFrom(), d.validFromPrecision(), d.validTo(), d.validToPrecision());
        l(stmt, prov("generatedAtTime"), RdfLiterals.dt(d.derivedAt()));
        if (d.invalidatedAt() != null) {
            l(stmt, prov("invalidatedAtTime"), RdfLiterals.dt(d.invalidatedAt()));
        }
        l(stmt, utopia("confidence"), RdfLiterals.confidence(d.confidence()));
        r(stmt, prov("wasGeneratedBy"), rule);
        r(rule, RDF_TYPE, prov("Activity"));
        // 规则的家族与身份（0020 的 2026-09-25 revision，#902）：读的人不再从标签里猜它
        // 来自哪张表。公理规则再写出种类（闭合枚举）和声明所在的谓词——inverse 与
        // sub_property 时它不是结论的谓词，从导出的 owl:inverseOf / rdfs:subPropertyOf
        // 反推是有歧义的。业务规则的条件与表达式不导出：规则原地更新，这个 IRI 担保
        // 不了旧结论当时依据的定义
        if (d.ruleId() != null) {
            r(rule, RDF_TYPE, utopia("AxiomRule"));
            l(rule, utopia("axiomKind"), RdfLiterals.text(d.rule()));
            if (d.rulePredicate() != null) {
                String p = vocab.relation(d.rulePredicate());
                if (p != null) {
                    r(rule, utopia("declaredOn"), p);
                }
            }
        } else {
            r(rule, RDF_TYPE, utopia("BusinessRule"));
        }
        // 标签用规则自己的名字（业务规则），公理退回它的种类名——审计读到的是
        // 「Gas-bearing well」而不是「business」
        l(rule, RDFS_LABEL, RdfLiterals.text(d.ruleName() != null ? d.ruleName() : d.rule()));
        // 前提。审计顺着 prov:used 往下走一步就到断言，再走一步就到句子
        for (UUID premise : d.premises()) {
            r(stmt, prov("used"), names.fact(premise));
        }
        for (UUID premise : d.premisesDerived()) {
            r(stmt, prov("used"), names.derived(premise));
        }
    }

    /**
     * 一条事实争议（0062）：同一槽位上的两条断言互不相让。status 照原样给：open 是
     * 「还在争」；resolved + resolution 是人的裁决；withdrawn 不是裁决——一边作废了题
     * 就没了，closedAt 记的是作废时刻（0051）。
     */
    public void emitFactConflict(Names names, ExportFactConflict c) {
        String iri = names.factConflict(c.id());
        r(iri, RDF_TYPE, utopia("FactConflict"));
        // 两席分清方向：压着的断言与新到的那条不是对称的（0062）
        r(iri, utopia("priorStatement"), names.fact(c.oldFactId()));
        r(iri, utopia("incomingStatement"), names.fact(c.newFactId()));
        l(iri, utopia("reason"), RdfLiterals.text(c.reason()));
        l(iri, utopia("status"), RdfLiterals.text(c.status()));
        if (c.resolution() != null) {
            l(iri, utopia("resolution"), RdfLiterals.text(c.resolution()));
        }
        l(iri, prov("generatedAtTime"), RdfLiterals.dt(c.createdAt()));
        if (c.resolvedAt() != null) {
            l(iri, utopia("closedAt"), RdfLiterals.dt(c.resolvedAt()));
        }
    }

    /**
     * 一条公理违规发现（0062）。这是<b>可变的当前状态</b>，不是历史：open 的意思是已
     * 对账于当前本体——所以判据只连在 open 行上，resolved 的行不假装知道自己是按哪一
     * 版本体结案的。
     */
    public void emitAxiomViolation(Names names, Vocabulary vocab, ExportAxiomViolation v) {
        String iri = names.axiomViolation(v.id());
        r(iri, RDF_TYPE, utopia("AxiomViolation"));
        l(iri, utopia("kind"), RdfLiterals.text(v.kind()));
        // self_loop/signature 的左右是同一事实——RDF 集合语义下重发无害，但 Turtle 文件里
        // 会是一条重复三元组
        List<UUID> endpoints = v.leftFact().equals(v.rightFact())
                ? List.of(v.leftFact())
                : List.of(v.leftFact(), v.rightFact());
        for (UUID fact : endpoints) {
            r(iri, utopia("onStatement"), names.fact(fact));
        }
        // 环是有序的证据链（0054）：拍扁成一组无序的 facts，「A→B→C→A」就读不回来了。
        // rdf:List 是 Turtle/JSON-LD 都认的保序结构
        if (!v.path().isEmpty()) {
            Node head = emitList(v.path().stream().map(f -> node(names.fact(f))).toList());
            raw(iri, utopia("evidencePath"), head);
        }
        l(iri, utopia("status"), RdfLiterals.text(v.status()));
        if (v.resolution() != null) {
            l(iri, utopia("resolution"), RdfLiterals.text(v.resolution()));
        }
        l(iri, utopia("detectedAt"), RdfLiterals.dt(v.detectedAt()));
        if (v.decidedAt() != null) {
            l(iri, utopia("closedAt"), RdfLiterals.dt(v.decidedAt()));
        }
        // 判据（0062 §4）：open = 已对账于当前本体，必然指得回一条关系与一个 OWL/rdfs
        // 词。signature 的判据是一对签名，两个词都发（行不记哪一侧破的）。resolved 的
        // 行不发——它的判据可能早已不在本体里
        if ("open".equals(v.status())) {
            for (String term : v.criterionTerms() != null ? v.criterionTerms() : new String[0]) {
                r(iri, utopia("criterion"), criterionNode(term));
            }
            if (v.criterionPredicate() != null) {
                String rel = vocab.relation(v.criterionPredicate());
                if (rel != null) {
                    r(iri, utopia("onRelation"), rel);
                }
            }
        }
    }

    /** utopia:criterion 的词项：criterion_terms 只产 owl:/rdfs: 两种（export 侧已拒导）。 */
    private static String criterionNode(String term) {
        if (term.startsWith("owl:")) {
            return owl(term.substring(4));
        }
        return switch (term) {
            case "rdfs:domain" -> RDFS_DOMAIN;
            case "rdfs:range" -> RDFS_RANGE;
            default -> throw new IllegalStateException("criterion_terms 只产 owl:/rdfs: 词: " + term);
        };
    }

    /**
     * 一串有序资源写成 rdf:List，返回表头节点（空表返回 rdf:nil）。从尾往前搭：每个
     * 空白节点落地时就知道自己的 rest。
     */
    private Node emitList(List<Node> items) {
        Node head = node(RDF_NIL);
        for (int i = items.size() - 1; i >= 0; i--) {
            Node bnode = org.apache.jena.graph.NodeFactory.createBlankNode();
            sink.triple(Triple.create(bnode, node(RDF_FIRST), items.get(i)));
            sink.triple(Triple.create(bnode, node(RDF_REST), head));
            head = bnode;
        }
        return head;
    }

    private void emitValidity(String stmt, OffsetDateTime from, String fromPrecision,
                              OffsetDateTime to, String toPrecision) {
        if (from != null) {
            l(stmt, schema("validFrom"), RdfLiterals.worldTime(from, fromPrecision));
            String p = RdfLiterals.subDay(fromPrecision);
            if (p != null) {
                l(stmt, utopia("validFromPrecision"), RdfLiterals.text(p));
            }
        }
        if (to != null) {
            l(stmt, schema("validThrough"), RdfLiterals.worldTime(to, toPrecision));
            String p = RdfLiterals.subDay(toPrecision);
            if (p != null) {
                l(stmt, utopia("validThroughPrecision"), RdfLiterals.text(p));
            }
        } else if ("unknown".equals(toPrecision)) {
            // 「结束了，但不知道哪天」——账本专门为它留了一个状态，导出不能把它压成
            // 「至今仍成立」（那正是 valid_to 一列承载两个意思时的老毛病）
            l(stmt, utopia("endedUnknown"), RdfLiterals.flag(true));
        }
    }

    // =====================================================================
    // JSON-LD 分组 writer
    // =====================================================================

    /**
     * JSON-LD 的按主语分组外写（oxrdfio JsonLdProfile::Streaming 的语义对等）：主语切换
     * 即落笔上一个主语的组，组内谓词按到达序聚合。导出的 emit 顺序按主语连续，分组即流
     * 式；万一某主语重新出现（rdf:List 的空白节点链不会，防御起见）再开一个节点对象——
     * JSON-LD 允许 @graph 外的顶层数组里同 @id 多次出现，解析侧合并，三元组集不变。
     * Jena 的 StreamRDFWriter 不提供 JSON-LD 流（RiotException: No serialization），故自写。
     */
    static final class JsonLdGroupedWriter implements StreamRDF {

        private final JsonGenerator gen;
        private String currentSubject;
        private final List<Triple> group = new ArrayList<>();

        JsonLdGroupedWriter(OutputStream out) throws IOException {
            this.gen = new JsonFactory().createGenerator(
                    new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8));
        }

        @Override
        public void start() {
            try {
                gen.writeStartArray();
            } catch (IOException e) {
                throw newUnchecked(e);
            }
        }

        @Override
        public void triple(Triple triple) {
            String s = triple.getSubject().toString();
            if (currentSubject == null) {
                currentSubject = s;
            } else if (!s.equals(currentSubject)) {
                flushGroup();
                currentSubject = s;
            }
            group.add(triple);
        }

        private void flushGroup() {
            if (group.isEmpty()) {
                return;
            }
            try {
                gen.writeStartObject();
                gen.writeStringField("@id", nodeId(group.get(0).getSubject()));
                // 谓词按首次出现序聚合（与到达序一致的确定性输出）
                Map<String, List<Node>> byPredicate = new LinkedHashMap<>();
                for (Triple t : group) {
                    byPredicate.computeIfAbsent(t.getPredicate().getURI(), k -> new ArrayList<>())
                            .add(t.getObject());
                }
                for (Map.Entry<String, List<Node>> e : byPredicate.entrySet()) {
                    gen.writeArrayFieldStart(e.getKey());
                    for (Node o : e.getValue()) {
                        writeTerm(gen, o);
                    }
                    gen.writeEndArray();
                }
                gen.writeEndObject();
            } catch (IOException e) {
                throw newUnchecked(e);
            }
            group.clear();
        }

        @Override
        public void finish() {
            try {
                flushGroup();
                gen.writeEndArray();
                gen.flush();
            } catch (IOException e) {
                throw newUnchecked(e);
            }
        }

        private static void writeTerm(JsonGenerator gen, Node o) throws IOException {
            if (o.isLiteral()) {
                String dt = o.getLiteralDatatypeURI();
                if (dt == null || "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString".equals(dt)) {
                    gen.writeString(o.getLiteralLexicalForm());
                } else {
                    gen.writeStartObject();
                    gen.writeStringField("@value", o.getLiteralLexicalForm());
                    gen.writeStringField("@type", dt);
                    gen.writeEndObject();
                }
            } else {
                gen.writeStartObject();
                gen.writeStringField("@id", nodeId(o));
                gen.writeEndObject();
            }
        }

        /** 节点引用的 id 值：IRI 原样；空白节点 "_:label"。 */
        private static String nodeId(Node n) {
            return n.isURI() ? n.getURI() : "_:" + n.getBlankNodeLabel();
        }

        private static RuntimeException newUnchecked(IOException e) {
            return new RuntimeException(e);
        }

        // ---- StreamRDF 其余回调：默认图/前缀/四元组在本导出形态不出现 ----

        @Override
        public void quad(org.apache.jena.sparql.core.Quad quad) {
            triple(quad.asTriple());
        }

        @Override
        public void base(String base) {
        }

        @Override
        public void prefix(String prefix, String iri) {
        }
    }
}
