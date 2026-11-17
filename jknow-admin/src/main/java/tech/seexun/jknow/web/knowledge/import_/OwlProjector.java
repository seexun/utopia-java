package tech.seexun.jknow.web.knowledge.import_;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDF;

/**
 * OWL / RDFS 文件的解析与<b>投影</b>（ingest/ontology_rdf.rs 1867 行逐语义，0001 的三层：
 * 本模块只做第二层——投影；第一层「原文保真进 blob」由 {@link ImportPlanner} 负责）。
 * <b>这里读不懂的东西不是错误，是「暂未投影」</b>——原文留着，将来补上消费者时重跑即可。
 *
 * <p>只解析，不推理：Jena Riot 给出三元组，我们按名单挑走能用的。不引推理机——推理机
 * （0002）读的是原文不是投影。
 *
 * <p>等价点记档：Rust 侧 oxigraph 的 Turtle/RDF-XML 解析器对相对 IRI 要 base 才能解析，
 * 统一 {@code with_base_iri("urn:utopia:import")}；blank node 只为 SHACL 三谓词
 * （sh:path/sh:class/sh:datatype）保留——属性形状多是空节点，其余空节点是匿名类表达式
 * （owl:Restriction 之类），投影不碰。
 */
public final class OwlProjector {

    private OwlProjector() {
    }

    /** 投影出来的一个类（OwlClass）。 */
    public record OwlClass(String iri, String key, String label, String description,
                           List<String> parents, List<String> disjointWith) {
    }

    /**
     * 投影出来的一个属性（OwlProperty）。对象属性 → 关系，数据属性 → 属性。
     * {@code rangesUnion}：多条 range 是并集还是交集——{@code rdfs:range} 写多条是交集
     * （「必须同时是两者」），{@code schema:rangeIncludes} 写多条是并集（「哪个都行」）。
     */
    public record OwlProperty(String iri, String key, String label, String description,
                              boolean isDatatype, boolean functional, boolean inverseFunctional,
                              boolean transitive, boolean symmetric, boolean asymmetric,
                              boolean irreflexive, String inverseOf, String subPropertyOf,
                              String supersededBy, List<String> domains, List<String> ranges,
                              boolean rangesUnion) {
    }

    /**
     * 一次解析的结果。{@code unprojected} 是<b>报告</b>不是错误——出现过但我们今天不
     * 消费的谓词 → 次数，给预览页「暂未投影」那一栏。
     */
    public record OwlProjection(List<OwlClass> classes, List<OwlProperty> properties,
                                Map<String, Integer> unprojected, int triples,
                                Map<String, String> vocabDatatypes) {

        public OwlProjection {
            // 预览/落库两端读它——不可变视图防中途被改
            classes = List.copyOf(classes);
            properties = List.copyOf(properties);
            unprojected = java.util.Collections.unmodifiableMap(
                    new TreeMap<>(unprojected));
            vocabDatatypes = java.util.Collections.unmodifiableMap(
                    new TreeMap<>(vocabDatatypes));
        }
    }

    // ---- 常量命名空间（与 Rust 同名常量一一对应） ----

    private static final String RDF_TYPE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String SH = "http://www.w3.org/ns/shacl#";

    /**
     * schema.org 自己那套 domain/range。不是标准词汇，所以写死在这里，但值得认：
     * schema.org 及派生词汇表一个 {@code rdfs:domain} 都没有，不认这两个谓词就等于把它
     * 整个类型系统当成没看见。两种 scheme 都收：同一份词汇表的新旧版本分别用 https 与
     * http 发布。
     */
    private static final String[] SCHEMA_NS = {"https://schema.org/", "http://schema.org/"};

    private static boolean isSchema(String iri, String local) {
        for (String ns : SCHEMA_NS) {
            if (iri.length() == ns.length() + local.length() && iri.startsWith(ns)
                    && iri.endsWith(local)) {
                return true;
            }
        }
        return false;
    }

    /** 属性形状上的那三条（路径、类、数据类型）：主语是空节点的三元组只为它们留下。 */
    private static boolean isShapeDetail(String predicate) {
        if (!predicate.startsWith(SH)) {
            return false;
        }
        String local = predicate.substring(SH.length());
        return local.equals("path") || local.equals("class") || local.equals("datatype");
    }

    /** 支持的输入格式（v1 只做这两个——Protégé 导出的绝大多数是它们）。 */
    public enum RdfFormat {
        TURTLE,
        RDFXML;

        /** plan 里回显的小写名（Rust {@code format!("{format:?}").to_lowercase()}）。 */
        public String wireName() {
            return name().toLowerCase();
        }
    }

    /**
     * 扩展名是强信号，内容只在<b>明确矛盾</b>时推翻它（detect）。
     *
     * <p>曾经反过来（{@code .rdf} 探不到 XML 标志就退回 Turtle），结果 FOAF 的官方文件
     * 一开头是几十行 {@code <!--} 注释、{@code <rdf:} 在嗅探窗口之外，于是被当成 Turtle
     * 送进解析器，第一行就报「Invalid IRI code point」。
     */
    public static RdfFormat detect(String filename, byte[] bytes) {
        String lower = filename.toLowerCase();
        // Turtle 的指纹很硬：只有它以 @prefix / @base / PREFIX 开头
        boolean looksTurtle;
        {
            int head = Math.min(bytes.length, 4096);
            String s = new String(bytes, 0, head, StandardCharsets.UTF_8);
            looksTurtle = false;
            for (String line : s.split("\n", -1)) {
                String t = line.stripLeading();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                looksTurtle = t.startsWith("@prefix") || t.startsWith("@base")
                        || t.startsWith("PREFIX") || t.startsWith("BASE");
                break;
            }
        }
        if (lower.endsWith(".ttl") || lower.endsWith(".turtle") || lower.endsWith(".n3")) {
            return RdfFormat.TURTLE;
        }
        if (lower.endsWith(".rdf") || lower.endsWith(".owl") || lower.endsWith(".xml")) {
            // .owl 两种编码都常见，所以内容说了算——但只有「确实像 Turtle」才推翻
            return looksTurtle ? RdfFormat.TURTLE : RdfFormat.RDFXML;
        }
        return looksTurtle ? RdfFormat.TURTLE : RdfFormat.RDFXML;
    }

    /** 三元组的中间形态：只留我们看得懂的部分。 */
    private record ParsedTriple(String subject, String predicate, String objectIri,
                                LiteralValue objectLit, String objectNode) {
    }

    /** 字面量宾语（值, 语言标记）——语言标记统一小写（Rust to_ascii_lowercase）。 */
    private record LiteralValue(String value, String lang) {
    }

    /** 相对 IRI 需要 base 才能解析——不给的话整个文件在第一个 {@code <#>} 上全军覆没。 */
    private static final String BASE = "urn:utopia:import";

    private static List<ParsedTriple> readTriples(byte[] bytes, RdfFormat format) {
        List<ParsedTriple> out = new ArrayList<>();
        StreamRDF sink = new StreamRDF() {
            @Override
            public void start() {
            }

            @Override
            public void triple(Triple t) {
                Node subj = t.getSubject();
                String subject;
                if (subj.isURI()) {
                    subject = subj.getURI();
                } else if (subj.isBlank() && isShapeDetail(t.getPredicate().getURI())) {
                    // 属性形状多是空节点：它的路径、类、数据类型留给 SHACL 那一遍
                    subject = "_:" + subj.getBlankNodeLabel();
                } else {
                    // 空节点是匿名类表达式（owl:Restriction 之类）——投影不碰它们，
                    // 原文里留着等推理机
                    return;
                }
                String predicate = t.getPredicate().getURI();
                String objectIri = null;
                LiteralValue objectLit = null;
                String objectNode = null;
                Node obj = t.getObject();
                if (obj.isURI()) {
                    objectIri = obj.getURI();
                } else if (obj.isLiteral()) {
                    String lang = obj.getLiteralLanguage();
                    objectLit = new LiteralValue(obj.getLiteralLexicalForm(),
                            lang == null || lang.isEmpty() ? null : lang.toLowerCase());
                } else if (obj.isBlank()) {
                    objectNode = "_:" + obj.getBlankNodeLabel();
                }
                out.add(new ParsedTriple(subject, predicate, objectIri, objectLit, objectNode));
            }

            @Override
            public void quad(org.apache.jena.sparql.core.Quad q) {
                triple(q.asTriple());
            }

            @Override
            public void base(String base) {
            }

            @Override
            public void prefix(String prefix, String iri) {
            }

            @Override
            public void finish() {
            }
        };
        RDFParser.create()
                .source(new ByteArrayInputStream(bytes))
                .lang(format == RdfFormat.TURTLE ? Lang.TURTLE : Lang.RDFXML)
                .base(BASE)
                .parse(sink);
        return out;
    }

    /**
     * 解析并投影（project）。语言标记优先 {@code @en}/{@code @zh}，其次无标记，最后随便一个。
     */
    public static OwlProjection project(byte[] bytes, RdfFormat format) {
        List<ParsedTriple> triples = readTriples(bytes, format);
        Map<String, Integer> unprojected = new TreeMap<>();
        // 为属性形状留下的空节点三元组不算：这个数一直只数主语是 IRI 的那些
        int tripleCount = 0;
        for (ParsedTriple t : triples) {
            if (!t.subject().startsWith("_:")) {
                tripleCount++;
            }
        }

        // 先分类：谁是类、谁是对象属性、谁是数据属性、谁带函数性标记
        TreeSet<String> classes = new TreeSet<>();
        TreeSet<String> objProps = new TreeSet<>();
        TreeSet<String> dataProps = new TreeSet<>();
        TreeSet<String> functional = new TreeSet<>();
        TreeSet<String> inverseFunctional = new TreeSet<>();
        TreeSet<String> transitive = new TreeSet<>();
        TreeSet<String> symmetric = new TreeSet<>();
        TreeSet<String> asymmetric = new TreeSet<>();
        TreeSet<String> irreflexive = new TreeSet<>();
        TreeSet<String> plainProps = new TreeSet<>();
        TreeSet<String> datatypeRoots = new TreeSet<>();
        TreeSet<String> nodeShapes = new TreeSet<>();
        // 属性之间的三条关系（不是类型声明，所以单独收；都是「多写几条只留第一条」）
        Map<String, String> inverseOf = new TreeMap<>();
        Map<String, String> subPropertyOf = new TreeMap<>();
        Map<String, String> supersededBy = new TreeMap<>();
        for (ParsedTriple t : triples) {
            if (!t.predicate().equals(RDF_TYPE) || t.objectIri() == null) {
                continue;
            }
            String o = t.objectIri();
            String subj = t.subject();
            if (o.equals(OWL + "Class") || o.equals(RDFS + "Class")) {
                classes.add(subj);
            } else if (o.equals(OWL + "ObjectProperty")) {
                objProps.add(subj);
            } else if (o.equals(OWL + "DatatypeProperty")) {
                dataProps.add(subj);
            } else if (o.equals(RDF_NS + "Property")) {
                // rdf:Property **没说**是对象还是数据。跟显式 owl:ObjectProperty 分开存：
                // 那个是「说了」，不该被 range 改判；这个按 range 定通道，range 也没有才
                // 兜底当关系
                plainProps.add(subj);
            } else if (o.equals(OWL + "FunctionalProperty")) {
                functional.add(subj);
            } else if (o.equals(OWL + "InverseFunctionalProperty")) {
                inverseFunctional.add(subj);
            } else if (o.equals(OWL + "TransitiveProperty")) {
                // 属性公理：R0 的一致性检查靠它们才谈得上判定。五个随包词表只覆盖了
                // 其中一半，但这条线的终点是把企业自己的本体导进来——按 OWL 收全
                transitive.add(subj);
            } else if (o.equals(OWL + "SymmetricProperty")) {
                symmetric.add(subj);
            } else if (o.equals(OWL + "AsymmetricProperty")) {
                asymmetric.add(subj);
            } else if (o.equals(OWL + "IrreflexiveProperty")) {
                irreflexive.add(subj);
            } else if (isSchema(o, "DataType")) {
                // 词汇表自报的数据类型。只收显式声明的那几个根，子类等父类图收完再传。
                // **标记类自己也收**：不收就会剩下一个叫 data_type 的实体类型
                datatypeRoots.add(subj);
                datatypeRoots.add(o);
            } else if (o.equals(SH + "NodeShape")) {
                nodeShapes.add(subj);
            }
        }

        // 再收集标签、注释、父类、domain/range
        Map<String, List<LiteralValue>> labels = new TreeMap<>();
        Map<String, List<LiteralValue>> comments = new TreeMap<>();
        Map<String, List<String>> parents = new TreeMap<>();
        Map<String, List<String>> disjoint = new TreeMap<>();
        Map<String, List<String>> domains = new TreeMap<>();
        Map<String, List<String>> ranges = new TreeMap<>();
        // rangeIncludes 用过的属性，它的 range 按并集读
        Set<String> unionRanged = new TreeSet<>();
        for (ParsedTriple t : triples) {
            String p = t.predicate();
            String subj = t.subject();
            if (p.equals(RDFS + "label")) {
                if (t.objectLit() != null) {
                    labels.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectLit());
                }
            } else if (p.equals(RDFS + "comment")) {
                if (t.objectLit() != null) {
                    comments.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectLit());
                }
            } else if (p.equals(OWL + "disjointWith")) {
                // **两个方向都记。** owl:disjointWith 是对称的，而词表通常只写一遍
                //（W3C Org 的 Role/Membership/Site/ChangeEvent 四者两两互斥，只写了六行）
                if (t.objectIri() != null) {
                    String o = t.objectIri();
                    disjoint.computeIfAbsent(subj, k -> new ArrayList<>()).add(o);
                    disjoint.computeIfAbsent(o, k -> new ArrayList<>()).add(subj);
                }
            } else if (p.equals(OWL + "inverseOf")) {
                // **只按写的方向收。** 补反向是读取公理时做的（reasoning::axioms）——
                // 在这里补会让「本体自己写了什么」和「我们推出来的」分不开，
                // R0 的 inverse_not_mutual 检查恰恰要区分这两者
                if (t.objectIri() != null) {
                    inverseOf.putIfAbsent(subj, t.objectIri());
                }
            } else if (isSchema(p, "supersededBy")) {
                // 取代关系只在 schema.org 词表里出现；多写几条只留第一条
                if (t.objectIri() != null) {
                    supersededBy.putIfAbsent(subj, t.objectIri());
                }
            } else if (p.equals(RDFS + "subPropertyOf")) {
                if (t.objectIri() != null) {
                    subPropertyOf.putIfAbsent(subj, t.objectIri());
                }
            } else if (p.equals(RDFS + "subClassOf")) {
                if (t.objectIri() != null) {
                    parents.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectIri());
                }
            } else if (p.equals(RDFS + "domain")) {
                if (t.objectIri() != null) {
                    domains.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectIri());
                }
            } else if (p.equals(RDFS + "range")) {
                if (t.objectIri() != null) {
                    ranges.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectIri());
                }
            } else if (isSchema(p, "domainIncludes")) {
                // domainIncludes 是并集（「可以用在这些类型上」），而我们的 domain 列表
                // 本来就是并集语义（签名 person|organization），直接进
                if (t.objectIri() != null) {
                    domains.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectIri());
                }
            } else if (isSchema(p, "rangeIncludes")) {
                if (t.objectIri() != null) {
                    ranges.computeIfAbsent(subj, k -> new ArrayList<>()).add(t.objectIri());
                    unionRanged.add(subj);
                }
            } else if (!known(p)) {
                // 报告而不是丢弃：预览页要能说清「这个文件里还有什么我们没消费」
                unprojected.merge(p, 1, Integer::sum);
            }
        }

        // SHACL 形状补上属性自己没写的 domain / range（#923）。**只填空白**：显式的
        // rdfs:domain / rdfs:range（和 schema.org 那两个）说了的，形状不改
        Map<String, TreeSet<String>> shapeDomains = new TreeMap<>();
        Map<String, TreeSet<String>> shapeRanges = new TreeMap<>();
        shapeSignatures(triples, classes, nodeShapes, shapeDomains, shapeRanges);
        for (Map.Entry<String, TreeSet<String>> e : shapeDomains.entrySet()) {
            domains.putIfAbsent(e.getKey(), new ArrayList<>(e.getValue()));
        }
        for (Map.Entry<String, TreeSet<String>> e : shapeRanges.entrySet()) {
            if (!ranges.containsKey(e.getKey())) {
                // 几个类上各自约束成不同的类型，是「哪个都行」，与 rangeIncludes 同理
                if (e.getValue().size() > 1) {
                    unionRanged.add(e.getKey());
                }
                ranges.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }

        // 数据类型闭包：根是显式 `a schema:DataType` 的那几个，子类沿 subClassOf 往下传
        //（Integer ⊂ Number、URL ⊂ Text）。**哪些 IRI 是数据类型由文件自己说**，
        // 写死的只有「这个数据类型算我们四种里的哪一种」——那一半是命名约定
        Set<String> datatypeClasses = new TreeSet<>(datatypeRoots);
        while (true) {
            List<String> grown = new ArrayList<>();
            for (Map.Entry<String, List<String>> e : parents.entrySet()) {
                if (!datatypeClasses.contains(e.getKey())
                        && e.getValue().stream().anyMatch(datatypeClasses::contains)) {
                    grown.add(e.getKey());
                }
            }
            if (grown.isEmpty()) {
                break;
            }
            datatypeClasses.addAll(grown);
        }
        Map<String, String> vocabDatatypes = new TreeMap<>();
        for (String iri : datatypeClasses) {
            String dt = nameDatatype(iri, parents, datatypeClasses);
            if (dt != null) {
                vocabDatatypes.put(iri, dt);
            }
        }

        // 裸声明判据的 home 只数类与显式声明的属性（Rust 同款三链，**不含 plain_props**：
        // rdf:Property 的「没说」不该给命名空间的归属权投票）
        String home = homeNamespace(java.util.stream.Stream.concat(classes.stream(),
                java.util.stream.Stream.concat(objProps.stream(),
                        dataProps.stream())).iterator());
        if (home == null) {
            home = "";
        }
        List<OwlClass> outClasses = new ArrayList<>();
        for (String iri : classes) {
            // **数据类型不是实体类型。** schema:Text 声明的是
            // `a rdfs:Class, schema:DataType`，只看前半截就会建出叫 text、number 的实体类
            if (datatypeClasses.contains(iri)) {
                continue;
            }
            // **光秃秃的外来声明不是这份词表的类**（#286）：schema.org 的 dump 里写着
            // `snomed:105590001 a rdfs:Class .`——没有标签、没有父类、没有属性指向它，
            // 命名空间也不是 schema.org 的。不建、记进「未投影」；本命名空间的裸声明照建
            boolean bare = !labels.containsKey(iri) && !comments.containsKey(iri)
                    && parents.getOrDefault(iri, List.of()).isEmpty()
                    && domains.values().stream().noneMatch(ds -> ds.contains(iri))
                    && ranges.values().stream().noneMatch(rs -> rs.contains(iri));
            if (bare && !namespaceOf(iri).equals(home)) {
                unprojected.merge("bare class declared outside the vocabulary's own namespace",
                        1, Integer::sum);
                continue;
            }
            // 先取真正的 label：编号式 IRI 的 key 要从它派生（#324）。词汇表没给 label
            // 时退回局部名，那也正是 key 的来源，行为不变
            String declaredLabel = pickLang(labels.get(iri));
            List<String> d = new ArrayList<>(disjoint.getOrDefault(iri, List.of()));
            // 去重：两个方向都收之后，词表若两边都写过就会重复
            java.util.Collections.sort(d);
            dedupInPlace(d);
            outClasses.add(new OwlClass(iri,
                    keyFromIriOrLabel(iri, declaredLabel),
                    declaredLabel != null ? declaredLabel : localName(iri),
                    orEmpty(pickLang(comments.get(iri))),
                    new ArrayList<>(parents.getOrDefault(iri, List.of())),
                    d));
        }

        // **并集去重，不是两个集合首尾相接**：词汇表常把同一个属性既声明为 rdf:Property
        // 又声明为 owl:DatatypeProperty（FOAF 的 name、age、nick… 都这样）
        TreeSet<String> allProps = new TreeSet<>();
        allProps.addAll(objProps);
        allProps.addAll(dataProps);
        allProps.addAll(plainProps);
        List<OwlProperty> outProps = new ArrayList<>();
        for (String iri : allProps) {
            List<String> rs = ranges.getOrDefault(iri, List.of());
            // 走属性通道还是关系通道。显式声明说了算；没说的看 range：**全是数据类型
            // 才算属性**。并集里只要有一个类就走关系——富的那一侧可回退，穷的回不去
            boolean isDatatype;
            if (dataProps.contains(iri)) {
                isDatatype = true;
            } else if (objProps.contains(iri)) {
                isDatatype = false;
            } else {
                isDatatype = !rs.isEmpty() && rs.stream().allMatch(datatypeClasses::contains);
            }
            String declaredLabel = pickLang(labels.get(iri));
            outProps.add(new OwlProperty(iri,
                    keyFromIriOrLabel(iri, declaredLabel),
                    declaredLabel != null ? declaredLabel
                            : localName(iri).replace("_", " "),
                    orEmpty(pickLang(comments.get(iri))),
                    isDatatype,
                    functional.contains(iri),
                    inverseFunctional.contains(iri),
                    transitive.contains(iri),
                    symmetric.contains(iri),
                    asymmetric.contains(iri),
                    irreflexive.contains(iri),
                    inverseOf.get(iri),
                    subPropertyOf.get(iri),
                    supersededBy.get(iri),
                    new ArrayList<>(domains.getOrDefault(iri, List.of())),
                    new ArrayList<>(rs),
                    unionRanged.contains(iri)));
        }

        // order_by_home_namespace：把这份文件自己的词汇表排到前面（撞 key 时调用方
        // 先到先得，字典序会让 http:// 前缀的被引用词表压过 https:// 的主词表）
        String projHome = homeNamespace(java.util.stream.Stream.concat(
                outClasses.stream().map(OwlClass::iri),
                outProps.stream().map(OwlProperty::iri)).iterator());
        if (projHome != null) {
            // 稳定排序：只把主词汇表提到前面，其余保持原有的字典序（Rust 的 key 是
            // namespace != home——false 排前，即主词汇表在前）
            outClasses.sort(java.util.Comparator.comparing(
                    c -> !namespaceOf(c.iri()).equals(projHome)));
            outProps.sort(java.util.Comparator.comparing(
                    p -> !namespaceOf(p.iri()).equals(projHome)));
        }
        return new OwlProjection(outClasses, outProps, unprojected, tripleCount, vocabDatatypes);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static void dedupInPlace(List<String> list) {
        for (int i = 1; i < list.size(); ) {
            if (list.get(i).equals(list.get(i - 1))) {
                list.remove(i);
            } else {
                i++;
            }
        }
    }

    /** 谓词消费白名单（known()）——白名单外进 unprojected 计数。 */
    private static boolean known(String p) {
        return p.equals(RDF_TYPE)
                || p.equals(RDFS + "label")
                || p.equals(RDFS + "comment")
                || p.equals(RDFS + "subClassOf")
                || p.equals(RDFS + "subPropertyOf")
                || p.equals(RDFS + "domain")
                || p.equals(RDFS + "range")
                || isSchema(p, "domainIncludes")
                || isSchema(p, "rangeIncludes")
                || p.equals(OWL + "disjointWith")
                || p.equals(OWL + "inverseOf")
                || isSchema(p, "supersededBy")
                // 形状那一遍读的五条（shape_signatures）
                || (p.startsWith(SH) && Arrays.asList(
                        "targetClass", "property", "path", "class", "datatype")
                        .contains(p.substring(SH.length())));
    }

    /**
     * SHACL 形状说的 domain 与 range（#923）。
     *
     * <p>{@code 节点形状 sh:property 属性形状}：属性形状的 {@code sh:path} 是哪个属性，
     * 节点形状作用的类就是它的 domain，属性形状上的 {@code sh:class}/{@code sh:datatype}
     * 就是它的 range。同一个属性约束在几个类上，domain 是这几个类的并集——与
     * domainIncludes 同一个读法。
     *
     * <p>不读的几种各有理由：targetSubjectsOf 说的是「用了某个属性的节点」不是类；路径
     * 只认一个 IRI（逆向、序列路径是空节点）；sh:or 里的备选类型、sh:node 指向的形状是
     * 另一层结构，原文里留着。
     */
    private static void shapeSignatures(List<ParsedTriple> triples, Set<String> classes,
                                        Set<String> nodeShapes,
                                        Map<String, TreeSet<String>> outDomains,
                                        Map<String, TreeSet<String>> outRanges) {
        Map<String, TreeSet<String>> targets = new TreeMap<>();
        for (String shape : nodeShapes) {
            if (classes.contains(shape)) {
                // 同时是类的节点形状，按 SHACL 的隐式类目标作用于自己
                targets.computeIfAbsent(shape, k -> new TreeSet<>()).add(shape);
            }
        }
        Map<String, List<String>> members = new TreeMap<>();
        Map<String, String> paths = new TreeMap<>();
        Map<String, List<String>> values = new TreeMap<>();
        for (ParsedTriple t : triples) {
            if (!t.predicate().startsWith(SH)) {
                continue;
            }
            String local = t.predicate().substring(SH.length());
            switch (local) {
                case "targetClass" -> {
                    if (t.objectIri() != null) {
                        targets.computeIfAbsent(t.subject(), k -> new TreeSet<>())
                                .add(t.objectIri());
                    }
                }
                case "property" -> {
                    String member = t.objectIri() != null ? t.objectIri() : t.objectNode();
                    if (member != null) {
                        members.computeIfAbsent(t.subject(), k -> new ArrayList<>()).add(member);
                    }
                }
                case "path" -> {
                    if (t.objectIri() != null) {
                        paths.putIfAbsent(t.subject(), t.objectIri());
                    }
                }
                case "class", "datatype" -> {
                    if (t.objectIri() != null) {
                        values.computeIfAbsent(t.subject(), k -> new ArrayList<>())
                                .add(t.objectIri());
                    }
                }
                default -> {
                }
            }
        }
        for (Map.Entry<String, List<String>> e : members.entrySet()) {
            TreeSet<String> on = targets.get(e.getKey());
            if (on == null) {
                continue;
            }
            for (String member : e.getValue()) {
                String property = paths.get(member);
                if (property == null) {
                    continue;
                }
                outDomains.computeIfAbsent(property, k -> new TreeSet<>()).addAll(on);
                List<String> vs = values.get(member);
                if (vs != null) {
                    outRanges.computeIfAbsent(property, k -> new TreeSet<>()).addAll(vs);
                }
            }
        }
    }

    /**
     * 一批 IRI 里声明得最多的命名空间，即「这份文件的主人」（home_namespace）。
     * 同数时取字典序最小（Rust 的 fold 沿 BTreeMap 升序、只在严格更大时换人——
     * 升序遍历 + {@code >} 同义）。
     */
    static String homeNamespace(java.util.Iterator<String> iris) {
        Map<String, Integer> counts = new TreeMap<>();
        while (iris.hasNext()) {
            counts.merge(namespaceOf(iris.next()), 1, Integer::sum);
        }
        String best = null;
        int bestCount = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > bestCount) {
                best = e.getKey();
                bestCount = e.getValue();
            }
        }
        return best;
    }

    /** IRI 去掉局部名剩下的那段（含结尾的 {@code #} 或 {@code /}）。 */
    static String namespaceOf(String iri) {
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i < 0 ? iri : iri.substring(0, i + 1);
    }

    /**
     * 数据类型 IRI → 我们的四种。自身叫得上名就用自身，否则沿 subClassOf 上溯
     * （Integer → Number、URL ⊂ Text）。叫不上名的返回 {@code null}——由
     * {@link #resolveRange} 走 {@code Degraded}，按 text 建<b>并报告</b>。
     */
    private static String nameDatatype(String iri, Map<String, List<String>> parents,
                                       Set<String> datatypes) {
        Set<String> seen = new TreeSet<>();
        List<String> stack = new ArrayList<>(List.of(iri));
        while (!stack.isEmpty()) {
            String cur = stack.remove(stack.size() - 1);
            if (!seen.add(cur)) {
                continue;
            }
            String dt = wellKnownDatatype(cur);
            if (dt != null) {
                return dt;
            }
            // 只沿数据类型往上走：数据类型的父类可能是普通的 rdfs:Class，
            // 越过去就走进整个类层次了
            for (String p : parents.getOrDefault(cur, List.of())) {
                if (datatypes.contains(p)) {
                    stack.add(p);
                }
            }
        }
        return null;
    }

    /**
     * 写死的那一半：数据类型的<b>名字</b>对应我们哪一种（well_known_datatype）。
     * 从 RDF 里推不出来——按局部名匹配在这里是安全的：能走到这儿的 IRI 是这份文件
     * 自己声明成数据类型的。
     */
    private static String wellKnownDatatype(String iri) {
        if (isSchema(iri, "Text")) {
            return "text";
        }
        if (isSchema(iri, "Number")) {
            return "number";
        }
        if (isSchema(iri, "Date") || isSchema(iri, "DateTime")) {
            return "date";
        }
        if (isSchema(iri, "Boolean")) {
            return "bool";
        }
        return null;
    }

    /** 多语言标签里挑一个：优先 en / zh，其次无语言标记，最后第一个（pick_lang）。 */
    private static String pickLang(List<LiteralValue> vals) {
        if (vals == null) {
            return null;
        }
        for (String want : new String[] {"en", "zh"}) {
            for (LiteralValue v : vals) {
                if (v.lang() != null && v.lang().startsWith(want)) {
                    return v.value();
                }
            }
        }
        for (LiteralValue v : vals) {
            if (v.lang() == null) {
                return v.value();
            }
        }
        return vals.isEmpty() ? null : vals.get(0).value();
    }

    /** IRI 的局部名：最后一个 {@code #} 或 {@code /} 之后的部分。 */
    public static String localName(String iri) {
        int i = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return i < 0 ? iri : iri.substring(i + 1);
    }

    /**
     * 局部名是不是<b>纯编号</b>（is_opaque_local_name）——{@code BFO_0000144}、
     * {@code CHEBI_15377} 这种。OBO 系的词汇表把标识放在 IRI 里、把意思放在
     * {@code rdfs:label} 里（#324）。判据卡得窄：<b>字母前缀 + 一个分隔符 + 至少三位
     * 数字</b>——schema.org 里带数字却有意义的名字都不满足。
     */
    static boolean isOpaqueLocalName(String local) {
        int pos = Math.max(local.lastIndexOf('_'), local.lastIndexOf('-'));
        if (pos < 0) {
            return false;
        }
        String prefix = local.substring(0, pos);
        String digits = local.substring(pos + 1);
        return !prefix.isEmpty() && prefix.chars().allMatch(c -> c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z')
                && digits.length() >= 3 && digits.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    /** 派生 key：局部名是纯编号且词汇表给了 label 时用 label，否则用局部名（#324）。 */
    public static String keyFromIriOrLabel(String iri, String label) {
        if (label != null && isOpaqueLocalName(localName(iri)) && !label.trim().isEmpty()) {
            return toKey(label);
        }
        return keyFromIri(iri);
    }

    /** 从 IRI 派生 key。IRI 是身份，key 是给模型读的标签：只允许 [a-z0-9_]、最长 40。 */
    public static String keyFromIri(String iri) {
        return toKey(localName(iri));
    }

    /**
     * 一段文本 → key 的字符规则：驼峰断词，非字母数字并成一个下划线，截断 40
     * （按 Rust chars().take(40) 的码点口径）。
     */
    static String toKey(String local) {
        StringBuilder out = new StringBuilder(local.length() + 4);
        boolean prevLower = false;
        for (int i = 0; i < local.length(); i++) {
            char c = local.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (prevLower && out.length() > 0) {
                    out.append('_');
                }
                out.append(Character.toLowerCase(c));
                prevLower = false;
            } else if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                // Rust is_ascii_alphanumeric（ASCII 判定——CJK 等非 ASCII 字母落 else 分支）
                out.append(c);
                prevLower = true;
            } else if (out.length() > 0
                    && (out.charAt(out.length() - 1) != '_')) {
                out.append('_');
                prevLower = false;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        String s = out.toString();
        return s.codePoints().limit(40)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
    }

    // ---- range 映射（RangeMapping 四档） ----

    /** {@code rdfs:range} 映射到我们的四种 datatype 的结果。三路，不是两路。 */
    public sealed interface RangeMapping permits RangeMapping.Datatype, RangeMapping.Absent, RangeMapping.Degraded, RangeMapping.Unusable {

        /** 能映射到 text / number / date / bool。 */
        record Datatype(String datatype) implements RangeMapping {
        }

        /** 压根没写 range：text 是诚实的超集，所以建、并在预览里列出。 */
        record Absent() implements RangeMapping {
        }

        /** 写了 range，值是短的可读字面量，但四种类型表达不了（time/duration）——按 text 建并报告。 */
        record Degraded(String iri) implements RangeMapping {
        }

        /** 写了 range，而抽取器永远不可能从散文里读出这个值（二进制块、XML 片段）——不建。 */
        record Unusable(String iri) implements RangeMapping {
        }
    }

    /** 数据属性的 {@code rdfs:range} → datatype（map_range）。多条 range一律 Degraded。 */
    public static RangeMapping mapRange(List<String> ranges) {
        return resolveRange(ranges, false, Map.of());
    }

    /**
     * 按属性<b>自己的</b> range 语义来解（map_range_of）。{@code rdfs:range} 写多条是
     * 交集，{@code schema:rangeIncludes} 写多条是并集，两者都落在同一个 ranges 里——
     * 不看 {@code rangesUnion} 这一位就会把 {@code author rangeIncludes Organization,
     * Person} 读成「必须既是组织又是人」。
     */
    public static RangeMapping mapRangeOf(OwlProperty p, Map<String, String> vocab) {
        return resolveRange(p.ranges(), p.rangesUnion(), vocab);
    }

    private static RangeMapping resolveRange(List<String> ranges, boolean union,
                                             Map<String, String> vocab) {
        // 词汇表自报的数据类型先查（schema:Text → text），查不到再走 xsd/owl 那套标准的
        java.util.function.Function<String, String> named =
                iri -> vocab.containsKey(iri) ? vocab.get(iri) : datatypeOf(iri);
        if (ranges.isEmpty()) {
            return new RangeMapping.Absent();
        }
        if (ranges.size() == 1) {
            String one = ranges.get(0);
            String dt = named.apply(one);
            if (dt != null) {
                return new RangeMapping.Datatype(dt);
            }
            if (unusable(one)) {
                return new RangeMapping.Unusable(one);
            }
            return new RangeMapping.Degraded(one);
        }
        if (union) {
            // 并集：全指向同一种就是那一种（Text ∪ URL 都是 text）。不一致则降级成 text
            // 并报告——text 是任意并集的诚实上界
            List<String> dts = ranges.stream().map(named).toList();
            String first = dts.get(0);
            if (first != null && dts.stream().allMatch(d -> first.equals(d))) {
                return new RangeMapping.Datatype(first);
            }
            if (ranges.stream().allMatch(OwlProjector::unusable)) {
                return new RangeMapping.Unusable(String.join(" ∪ ", ranges));
            }
            return new RangeMapping.Degraded(String.join(" ∪ ", ranges));
        }
        // rdfs:range 写多条是交集语义，不猜类型——但值仍是字面量，所以按 text 建
        return new RangeMapping.Degraded(String.join(" ∩ ", ranges));
    }

    /**
     * 抽取器不可能从散文里读出来的那些（unusable）：二进制块与 XML 内部管道。判据不是
     * 「这个值该不该存」而是<b>会不会有值</b>。hexBinary 不在此列：写出来的是十六进制
     * 数字，散文里带的是哈希、地址、标志位这类值，抽成事实是对的。
     */
    static boolean unusable(String iri) {
        if (iri.startsWith(XSD)) {
            return Arrays.asList("base64Binary", "QName", "NOTATION", "ID", "IDREF", "IDREFS",
                    "ENTITY", "ENTITIES").contains(iri.substring(XSD.length()));
        }
        if (iri.startsWith(RDF_NS)) {
            return iri.substring(RDF_NS.length()).equals("XMLLiteral");
        }
        return false;
    }

    /** datatype_of：按完整 IRI 匹配——自定义词汇表完全可能有个叫 date 的类。 */
    static String datatypeOf(String iri) {
        if (iri.startsWith(XSD)) {
            return switch (iri.substring(XSD.length())) {
                // 有界与无符号变体全部收进 number：区别在取值范围，不在语义
                case "decimal", "integer", "int", "long", "short", "byte",
                        "nonNegativeInteger", "positiveInteger", "nonPositiveInteger",
                        "negativeInteger", "unsignedLong", "unsignedInt", "unsignedShort",
                        "unsignedByte", "double", "float" -> "number";
                // 我们的日期格式本就是 YYYY[-MM[-DD]]，逐级可省，gYear/gYearMonth 装得下
                case "date", "dateTime", "dateTimeStamp", "gYear", "gYearMonth" -> "date";
                case "boolean" -> "bool";
                // hexBinary 的写法就是十六进制数字：哈希、地址、标志位，照原样存成文本
                case "string", "normalizedString", "token", "language", "Name", "NCName",
                        "NMTOKEN", "anyURI", "hexBinary" -> "text";
                // time / gMonth / gDay / gMonthDay 缺年、duration 系列是时长——
                // 由 unusable() 分流：它们是可读字面量，降级成 text
                default -> null;
            };
        }
        if (iri.startsWith(RDF_NS)) {
            String local = iri.substring(RDF_NS.length());
            return local.equals("PlainLiteral") || local.equals("langString") ? "text" : null;
        }
        if (iri.startsWith(RDFS)) {
            return iri.substring(RDFS.length()).equals("Literal") ? "text" : null;
        }
        if (iri.startsWith(OWL)) {
            String local = iri.substring(OWL.length());
            return local.equals("real") || local.equals("rational") ? "number" : null;
        }
        return null;
    }
}
