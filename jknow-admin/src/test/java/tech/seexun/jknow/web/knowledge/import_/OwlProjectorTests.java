package tech.seexun.jknow.web.knowledge.import_;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlClass;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlProjection;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.OwlProperty;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.RdfFormat;
import tech.seexun.jknow.web.knowledge.import_.OwlProjector.RangeMapping;

/**
 * OWL/RDFS 投影的纯函数与夹具测试（ontology_rdf.rs 内联 mod tests + axioms +
 * property_axiom_tests + shape_tests + against_real_packs——断言逐字，测试名 snake_case
 * 原名保留；真包四例走 classpath packs 资源，与 Rust include_bytes 同源同字节）。
 */
class OwlProjectorTests {

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
    private static final String OWL = "http://www.w3.org/2002/07/owl#";
    private static final String RDFS = "http://www.w3.org/2000/01/rdf-schema#";
    private static final String SH = "http://www.w3.org/ns/shacl#";

    private static OwlProjection project(String ttl) {
        return OwlProjector.project(ttl.getBytes(StandardCharsets.UTF_8), RdfFormat.TURTLE);
    }

    private static Function<String, OwlClass> classesBy(OwlProjection p) {
        return key -> p.classes().stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
    }

    private static Function<String, OwlProperty> propsBy(OwlProjection p) {
        return key -> p.properties().stream().filter(x -> x.key().equals(key)).findFirst()
                .orElse(null);
    }

    @Test
    void range_maps_every_numeric_variant() {
        for (String local : List.of("decimal", "integer", "int", "long", "short", "byte",
                "nonNegativeInteger", "positiveInteger", "nonPositiveInteger", "negativeInteger",
                "unsignedLong", "unsignedInt", "unsignedShort", "unsignedByte", "double",
                "float")) {
            assertEquals(new RangeMapping.Datatype("number"),
                    OwlProjector.mapRange(List.of(XSD + local)), local);
        }
        // owl:real / owl:rational 也在 OWL 2 的 datatype map 里
        assertEquals(new RangeMapping.Datatype("number"),
                OwlProjector.mapRange(List.of(OWL + "rational")));
    }

    /** 我们的日期格式是 YYYY[-MM[-DD]]，逐级可省，所以只缺低位的 g 类型装得下。 */
    @Test
    void partial_dates_fit_only_when_the_year_is_there() {
        for (String local : List.of("date", "dateTime", "dateTimeStamp", "gYear", "gYearMonth")) {
            assertEquals(new RangeMapping.Datatype("date"),
                    OwlProjector.mapRange(List.of(XSD + local)), local);
        }
        // 缺年的进不了 date——但它们是可读字面量，降级成 text 而不是丢掉
        for (String local : List.of("gMonth", "gDay", "gMonthDay", "time")) {
            assertTrue(OwlProjector.mapRange(List.of(XSD + local)) instanceof RangeMapping.Degraded,
                    local + " 该降级成 text，不该丢");
        }
    }

    /** 分界不是「能不能精确映射」，而是「这个取值该不该进图谱」。 */
    @Test
    void a_value_we_can_store_is_kept_even_when_we_cannot_type_it() {
        // 没写 range：没有声明可丢，text 是诚实的超集
        assertEquals(new RangeMapping.Absent(), OwlProjector.mapRange(List.of()));
        // 写了但表达不了：时长是可读字面量，降级成 text 并报告
        assertTrue(OwlProjector.mapRange(List.of(XSD + "duration"))
                instanceof RangeMapping.Degraded);
        // 十六进制写出来的值：哈希、地址、标志位。它是一个值，照原样存成文本
        assertEquals(new RangeMapping.Datatype("text"),
                OwlProjector.mapRange(List.of(XSD + "hexBinary")));
        // 取值本就不该进图谱：base64 块、XML 片段与 XML 内部标识，这才是真的跳过
        for (String unusable : List.of(XSD + "base64Binary", RDF_NS + "XMLLiteral",
                XSD + "QName")) {
            assertTrue(OwlProjector.mapRange(List.of(unusable)) instanceof RangeMapping.Unusable,
                    unusable);
        }
    }

    /** 多条 range 在 RDFS 里是交集（「必须同时是两者」），不是并集。不猜。 */
    @Test
    void several_ranges_are_an_intersection_we_refuse_to_guess() {
        RangeMapping m = OwlProjector.mapRange(List.of(XSD + "string", XSD + "integer"));
        assertTrue(m instanceof RangeMapping.Degraded d && d.iri().contains("∩"),
                "交集不猜类型，但值仍是字面量，所以按 text 落下来: " + m);
    }

    /** 按完整 IRI 匹配：自定义词汇表里叫 date 的「类」不是 xsd:date。 */
    @Test
    void a_class_that_happens_to_be_called_date_is_not_a_date() {
        assertTrue(OwlProjector.mapRange(List.of("http://acme.example/hr#date"))
                instanceof RangeMapping.Degraded);
    }

    private static final String TTL = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://acme.example/hr#> .

            ex:Employee a owl:Class ;
                rdfs:label "Employee"@en ;
                rdfs:label "员工"@zh ;
                rdfs:comment "A person on the payroll."@en ;
                rdfs:subClassOf ex:Person .
            ex:Person a owl:Class ; rdfs:label "Person" .
            ex:hasManager a owl:ObjectProperty, owl:FunctionalProperty ;
                rdfs:label "has manager" ;
                rdfs:domain ex:Employee ;
                rdfs:range ex:Person .
            ex:salary a owl:DatatypeProperty ; rdfs:domain ex:Employee .
            ex:Employee owl:disjointWith ex:Contractor .
            ex:Employee owl:equivalentClass ex:Staff .
            """;

    @Test
    void projects_classes_properties_and_reports_the_rest() {
        OwlProjection p = project(TTL);
        OwlClass emp = classesBy(p).apply("employee");
        assertNotNull(emp);
        // 多语言标签取 en 优先
        assertEquals("Employee", emp.label());
        assertEquals("A person on the payroll.", emp.description());
        assertEquals(List.of("http://acme.example/hr#Person"), emp.parents());

        OwlProperty mgr = propsBy(p).apply("has_manager");
        assertNotNull(mgr);
        assertTrue(mgr.functional() && !mgr.isDatatype());
        assertEquals(List.of("http://acme.example/hr#Person"), mgr.ranges());

        OwlProperty sal = propsBy(p).apply("salary");
        assertTrue(sal.isDatatype());

        // 消费不了的公理进报告，不是丢弃也不是报错。disjointWith 曾在这份名单上，
        // 现在被消费了（一致性检查的判定依据）——这里用一个仍然消费不了的公理守这条性质
        assertTrue(p.unprojected().containsKey("http://www.w3.org/2002/07/owl#equivalentClass"));
    }

    /**
     * schema.org 把 {@code snomed:105590001 a rdfs:Class .} 这种 equivalentClass 的对象
     * 也声明成了类：没标签、没父类、没属性、不在自家命名空间。不建，但记账；
     * 自家命名空间里同样光秃秃的类照建。
     */
    @Test
    void a_bare_class_from_another_namespace_is_reported_not_projected() {
        String ttl = """
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix ex: <http://acme.example/hr#> .
                @prefix snomed: <http://purl.bioontology.org/ontology/SNOMEDCT/> .
                ex:Employee a owl:Class ; rdfs:label "Employee" ; owl:equivalentClass snomed:105590001 .
                ex:Person a owl:Class ; rdfs:label "Person" .
                ex:Contractor a owl:Class .
                snomed:105590001 a rdfs:Class .
                """;
        OwlProjection p = project(ttl);
        List<String> keys = p.classes().stream().map(OwlClass::key).toList();
        assertTrue(keys.contains("employee") && keys.contains("person"));
        assertTrue(keys.contains("contractor"), "自家的裸声明是自己的类：" + keys);
        assertFalse(keys.stream().anyMatch(k -> k.chars().allMatch(Character::isDigit)), keys.toString());
        assertEquals(1, p.unprojected()
                .get("bare class declared outside the vocabulary's own namespace"));
    }

    @Test
    void detects_rdfxml_that_opens_with_comments() {
        // FOAF 的官方文件就长这样：几十行 <!-- --> 之后才见 <rdf:RDF>。早先版本嗅不到
        // XML 标志就退回 Turtle，结果第一行就解析失败
        byte[] head = ("<!-- This is the FOAF vocabulary, expressed using RDFS and OWL. -->\n"
                + "<!-- padding padding padding padding padding padding padding -->\n")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(RdfFormat.RDFXML, OwlProjector.detect("index.rdf", head));
        // 反过来：.owl 里放 Turtle 也常见，内容说了算
        assertEquals(RdfFormat.TURTLE,
                OwlProjector.detect("x.owl", "@prefix owl: <http://x#> .\n".getBytes()));
        // 注释开头的 Turtle 同样认得出（# 行先跳过）
        assertEquals(RdfFormat.TURTLE,
                OwlProjector.detect("x", "# a note\n\n@base <http://x> .\n".getBytes()));
    }

    @Test
    void key_derives_from_the_iri_local_name() {
        assertEquals("has_employee", OwlProjector.keyFromIri("http://x/hr#hasEmployee"));
        assertEquals("person", OwlProjector.keyFromIri("http://x/ns/Person"));
        assertEquals("http_server", OwlProjector.keyFromIri("http://x#HTTP_Server"));
    }

    /** 编号式 IRI 的 key 改从 label 来（#324）。 */
    @Test
    void an_opaque_iri_takes_its_key_from_the_label() {
        java.util.function.BiFunction<String, String, String> k = OwlProjector::keyFromIriOrLabel;
        assertEquals("process_profile",
                k.apply("http://purl.obolibrary.org/obo/BFO_0000144", "process profile"));
        assertEquals("water", k.apply("http://purl.obolibrary.org/obo/CHEBI_15377", "water"));
        assertEquals("biological_process",
                k.apply("http://purl.obolibrary.org/obo/GO_0008150", "biological process"));
        // 没有 label 就没得选，退回 IRI——总比没有强，身份仍在 IRI 上
        assertEquals("bfo_0000144",
                OwlProjector.keyFromIriOrLabel("http://purl.obolibrary.org/obo/BFO_0000144", null));
        // 空 label 同样不算数
        assertEquals("bfo_0000144",
                k.apply("http://purl.obolibrary.org/obo/BFO_0000144", "   "));
    }

    /** 判据必须窄——schema.org 里带数字的名字都有意义，一个都不能被改写。 */
    @Test
    void a_meaningful_name_keeps_its_key_even_with_digits() {
        java.util.function.BiFunction<String, String, String> k = OwlProjector::keyFromIriOrLabel;
        assertEquals("gtin12", k.apply("https://schema.org/gtin12", "GTIN-12"));
        assertEquals("sha256", k.apply("https://schema.org/sha256", "sha256"));
        assertEquals("percentile10", k.apply("https://schema.org/percentile10", "percentile 10"));
        // 数字不在末尾
        assertEquals("emissions_co2",
                k.apply("https://schema.org/emissionsCO2", "emissions CO2"));
        // 分隔符后面不是数字
        assertEquals("http_server", k.apply("http://x#HTTP_Server", "HTTP server"));
        // 数字不足三位：可能是有意义的编号（v2、Q1），不当编号处理
        assertEquals("part_12", k.apply("http://x#part_12", "part twelve"));
    }

    /** schema.org 那一套的最小复刻：数据类型自报家门，属性用 domainIncludes/rangeIncludes。 */
    private static final String SCHEMA_ISH = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix schema: <https://schema.org/> .

            schema:DataType a rdfs:Class .
            schema:Text a rdfs:Class, schema:DataType .
            schema:Number a rdfs:Class, schema:DataType .
            schema:Date a rdfs:Class, schema:DataType .
            schema:Time a rdfs:Class, schema:DataType .
            schema:URL a rdfs:Class ; rdfs:subClassOf schema:Text .
            schema:Integer a rdfs:Class ; rdfs:subClassOf schema:Number .

            schema:Organization a rdfs:Class ; rdfs:label "Organization" .
            schema:Person a rdfs:Class ; rdfs:label "Person" .
            schema:PostalAddress a rdfs:Class ; rdfs:label "PostalAddress" .

            schema:foundingDate a rdf:Property ;
                schema:domainIncludes schema:Organization ;
                schema:rangeIncludes schema:Date .
            schema:author a rdf:Property ;
                schema:domainIncludes schema:Organization ;
                schema:rangeIncludes schema:Organization, schema:Person .
            schema:address a rdf:Property ;
                schema:domainIncludes schema:Organization ;
                schema:rangeIncludes schema:PostalAddress, schema:Text .
            schema:homepage a rdf:Property ;
                schema:domainIncludes schema:Person ;
                schema:rangeIncludes schema:Text, schema:URL .
            schema:opens a rdf:Property ;
                schema:domainIncludes schema:Organization ;
                schema:rangeIncludes schema:Time .
            schema:knows a rdf:Property ;
                schema:domainIncludes schema:Person .
            """;

    private static OwlProjection schemaIsh() {
        return project(SCHEMA_ISH);
    }

    @Test
    void schema_org_datatypes_are_not_entity_types() {
        OwlProjection p = schemaIsh();
        List<String> keys = p.classes().stream().map(OwlClass::key).toList();
        // Text / Number / Date / Time 声明的是 `a rdfs:Class, schema:DataType`，只看前半截
        // 就会建出叫 text、number 的实体类型来
        for (String gone : List.of("text", "number", "date", "time", "url", "integer",
                "data_type")) {
            assertFalse(keys.contains(gone), gone + " 不该是实体类型：" + keys);
        }
        assertTrue(keys.contains("organization") && keys.contains("person"));
    }

    @Test
    void domain_includes_feeds_the_signature() {
        OwlProjection p = schemaIsh();
        // 不认 schema:domainIncludes 的话这里是空的，签名就成了 (* → *)
        assertEquals(List.of("https://schema.org/Organization"),
                propsBy(p).apply("founding_date").domains());
    }

    @Test
    void a_union_range_of_classes_stays_a_relation() {
        OwlProjection p = schemaIsh();
        // rangeIncludes Organization, Person——并集，两个都是类
        assertFalse(propsBy(p).apply("author").isDatatype());
        // rangeIncludes PostalAddress, Text——Text 是「懒得建实体就写个字符串」。
        // 判成属性就把这条边永久丢了
        assertFalse(propsBy(p).apply("address").isDatatype());
    }

    @Test
    void a_union_range_of_datatypes_becomes_an_attribute() {
        OwlProjection p = schemaIsh();
        OwlProperty fd = propsBy(p).apply("founding_date");
        assertTrue(fd.isDatatype());
        assertEquals(new RangeMapping.Datatype("date"),
                OwlProjector.mapRangeOf(fd, p.vocabDatatypes()));
        // Text ∪ URL：URL ⊂ Text，两个都解到 text，不用降级也不用报告
        OwlProperty hp = propsBy(p).apply("homepage");
        assertTrue(hp.isDatatype());
        assertEquals(new RangeMapping.Datatype("text"),
                OwlProjector.mapRangeOf(hp, p.vocabDatatypes()));
    }

    @Test
    void a_union_is_not_an_intersection() {
        OwlProjection p = schemaIsh();
        // 整件事最容易错的一步：两个 range 倒进同一个列表之后，不看 ranges_union 就会
        // 当成 rdfs:range 的交集语义
        assertTrue(propsBy(p).apply("author").rangesUnion());
        assertEquals(2, propsBy(p).apply("author").ranges().size());
        // 而 rdfs:range 写两条仍然是交集
        assertTrue(OwlProjector.mapRange(List.of(XSD + "string", XSD + "integer"))
                instanceof RangeMapping.Degraded d && d.iri().contains("∩"));
    }

    @Test
    void an_unnamed_datatype_degrades_and_is_reported() {
        OwlProjection p = schemaIsh();
        // schema:Time 只有时刻没有日期，跟 xsd:time 一个待遇：是数据类型（所以走属性
        // 通道），但叫不上名 → 按 text 建**并报告**
        OwlProperty o = propsBy(p).apply("opens");
        assertTrue(o.isDatatype());
        assertTrue(OwlProjector.mapRangeOf(o, p.vocabDatatypes())
                instanceof RangeMapping.Degraded d && d.iri().endsWith("Time"));
    }

    @Test
    void a_bare_rdf_property_without_range_is_still_a_relation() {
        OwlProjection p = schemaIsh();
        // 没有 range 就没有判据，兜底仍是关系——宾语是 IRI 的远多于字面值
        OwlProperty k = propsBy(p).apply("knows");
        assertFalse(k.isDatatype());
        assertTrue(k.ranges().isEmpty());
    }

    @Test
    void schema_predicates_are_consumed_not_reported_as_unprojected() {
        OwlProjection p = schemaIsh();
        // 认了就不该再出现在「暂未投影」里，否则预览页会说
        // 「还有 2312 条 domainIncludes 没消费」，而其实消费了
        for (String consumed : List.of("domainIncludes", "rangeIncludes")) {
            final String c = consumed;
            assertFalse(p.unprojected().keySet().stream().anyMatch(k -> k.endsWith(c)),
                    consumed + " 应已消费：" + p.unprojected());
        }
    }

    /** 一份文件里合了两个词汇表，主词汇表 IRI 用 https、被引用的用 http。 */
    private static final String TWO_VOCABS = """
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix home: <https://home.example/> .
            @prefix cited: <http://cited.example/> .

            home:Location a rdfs:Class ; rdfs:label "Location" .
            home:Country a rdfs:Class ; rdfs:label "Country" .
            home:Person a rdfs:Class ; rdfs:label "Person" .
            home:Organization a rdfs:Class ; rdfs:label "Organization" .
            cited:Location a rdfs:Class ; rdfs:label "Location (cited)" .

            home:worksAt a rdf:Property ; rdfs:label "worksAt" .
            home:knows a rdf:Property ; rdfs:label "knows" .
            cited:worksAt a rdf:Property ; rdfs:label "worksAt (cited)" .
            """;

    @Test
    void the_files_own_vocabulary_wins_a_key_collision() {
        OwlProjection p = project(TWO_VOCABS);
        // 撞车由调用方先到先得地裁决，所以顺序就是裁决。主词汇表声明得最多，它必须排
        // 在前面——否则 http:// < https:// 这条字典序会让被引用的词汇表赢走 location
        OwlClass firstLocation = p.classes().stream().filter(c -> c.key().equals("location"))
                .findFirst().orElseThrow();
        assertTrue(firstLocation.iri().startsWith("https://home.example/"),
                "输给了 " + firstLocation.iri());
        OwlProperty firstWorks = p.properties().stream()
                .filter(x -> x.key().equals("works_at")).findFirst().orElseThrow();
        assertTrue(firstWorks.iri().startsWith("https://home.example/"));
    }

    // ---- mod axioms：属性公理与类互斥要投影出来——0002 R0 的判定依据 ----

    private static final String AX = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix ex: <http://acme.example/ax#> .

            ex:Person a owl:Class ; owl:disjointWith ex:Organization .
            ex:Organization a owl:Class .
            ex:Document a owl:Class .

            ex:partOf   a owl:ObjectProperty, owl:TransitiveProperty, owl:AsymmetricProperty .
            ex:aliasOf  a owl:ObjectProperty, owl:SymmetricProperty .
            ex:reportsTo a owl:ObjectProperty, owl:IrreflexiveProperty .
            ex:plain    a owl:ObjectProperty .
            """;

    private static OwlProjection ax() {
        return project(AX);
    }

    @Test
    void property_axioms_survive_the_projection() {
        OwlProjection p = ax();
        Function<String, OwlProperty> by = propsBy(p);

        OwlProperty partOf = by.apply("part_of");
        assertTrue(partOf.transitive(), "TransitiveProperty 要落到属性上");
        assertTrue(partOf.asymmetric(), "AsymmetricProperty 同上");
        assertFalse(partOf.symmetric());

        assertTrue(by.apply("alias_of").symmetric(), "对称属性双向出现是正确的，不是矛盾");
        assertTrue(by.apply("reports_to").irreflexive(), "非自反：自己汇报给自己是矛盾");

        // 没声明的一律为假——默认不是「未知」而是「没这条公理」
        OwlProperty plain = by.apply("plain");
        assertFalse(plain.transitive() || plain.symmetric());
        assertFalse(plain.asymmetric() || plain.irreflexive());
    }

    /** 两个方向都要有：词表通常只写一遍，只按书写方向存就取决于从哪头问。 */
    @Test
    void disjointness_is_recorded_from_both_ends() {
        OwlProjection p = ax();
        java.util.function.Function<String, List<String>> d = key -> classesBy(p)
                .apply(key).disjointWith();
        assertEquals(List.of("http://acme.example/ax#Organization"), d.apply("person"));
        assertEquals(List.of("http://acme.example/ax#Person"), d.apply("organization"));
        assertTrue(d.apply("document").isEmpty(), "没声明互斥的类不该凭空多出来");
    }

    // ---- mod property_axiom_tests ----

    /** schema:supersededBy 要被读出来：让位的属性带着它让给了谁。 */
    @Test
    void a_superseded_property_names_its_successor() {
        String ttl = """
                @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix schema: <https://schema.org/> .
                schema:Organization a rdfs:Class ; rdfs:label "Organization" .
                schema:Person a rdfs:Class ; rdfs:label "Person" .
                schema:employee a rdf:Property ; rdfs:label "employee" ;
                    schema:domainIncludes schema:Organization ; schema:rangeIncludes schema:Person .
                schema:employees a rdf:Property ; rdfs:label "employees" ;
                    schema:domainIncludes schema:Organization ; schema:rangeIncludes schema:Person ;
                    schema:supersededBy schema:employee .
                """;
        OwlProjection p = project(ttl);
        assertEquals("https://schema.org/employee",
                propsBy(p).apply("employees").supersededBy());
        assertNull(propsBy(p).apply("employee").supersededBy());
        assertFalse(p.unprojected().keySet().stream().anyMatch(k -> k.contains("supersededBy")),
                "认了就不该再算进「暂未投影」");
    }

    /** owl:inverseOf 与 rdfs:subPropertyOf 要被读出来（tests/inverse_and_sub.ttl 同源）。 */
    @Test
    void the_two_property_relations_survive_projection() {
        String ttl = """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
                @prefix ex: <http://example.org/> .
                ex:worksAt a owl:ObjectProperty ; rdfs:label "works at" .
                ex:employs a owl:ObjectProperty ; rdfs:label "employs" ; owl:inverseOf ex:worksAt .
                ex:ceoOf a owl:ObjectProperty ; rdfs:label "CEO of" ; rdfs:subPropertyOf ex:worksAt .
                """;
        OwlProjection p = project(ttl);
        // **逆要按写的方向收**——补反向是读取公理时的事
        assertEquals("http://example.org/worksAt", propsBy(p).apply("employs").inverseOf());
        assertEquals("http://example.org/worksAt", propsBy(p).apply("ceo_of").subPropertyOf());
        // 反方向没写，就该是空的：本体写了什么与我们推了什么要分得开
        assertNull(propsBy(p).apply("works_at").inverseOf(), "没写的方向不该在导入时被补上");
        assertFalse(p.unprojected().keySet().stream().anyMatch(k -> k.contains("inverseOf")),
                "**认了就不该再算进「暂未投影」**");
    }

    // ---- mod shape_tests（#923 SHACL 形状给的 domain/range） ----

    private static final String SHAPES = """
            @prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix sh: <http://www.w3.org/ns/shacl#> .
            @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
            @prefix ex: <https://ex.example/> .

            ex:Account a owl:Class, sh:NodeShape ;
                sh:property [ sh:path ex:owner ; sh:class ex:Identity ; sh:maxCount 1 ] ,
                            [ sh:path ex:accountType ; sh:datatype xsd:string ] ;
                sh:targetClass ex:Account .
            ex:Identity a owl:Class, sh:NodeShape ;
                sh:property [ sh:path ex:givenName ; sh:datatype xsd:string ] .
            ex:Device a owl:Class .
            ex:DeviceShape a sh:NodeShape ;
                sh:targetClass ex:Device ;
                sh:property ex:serialShape .
            ex:serialShape sh:path ex:serial ; sh:datatype xsd:string .
            ex:Organization a owl:Class .
            ex:Tool a owl:Class, sh:NodeShape ;
                sh:targetClass ex:Tool ;
                sh:property [ sh:path ex:owner ; sh:class ex:Organization ] ,
                            [ sh:path ex:version ; sh:datatype xsd:string ] ,
                            [ sh:path [ sh:inversePath ex:uses ] ; sh:class ex:Identity ] ,
                            [ sh:path ex:vendor ; sh:or ( [ sh:class ex:Identity ] [ sh:class ex:Organization ] ) ] ,
                            [ sh:path ex:undeclared ; sh:datatype xsd:string ] .
            ex:Usage a sh:NodeShape ;
                sh:targetSubjectsOf ex:uses ;
                sh:property [ sh:path ex:uses ; sh:class ex:Tool ] .

            ex:owner a owl:ObjectProperty .
            ex:uses a owl:ObjectProperty .
            ex:vendor a owl:ObjectProperty .
            ex:accountType a owl:DatatypeProperty .
            ex:givenName a owl:DatatypeProperty .
            ex:serial a owl:DatatypeProperty .
            ex:version a owl:DatatypeProperty ; rdfs:domain ex:Device ; rdfs:range xsd:integer .
            """;

    private static OwlProperty byIri(OwlProjection p, String local) {
        String iri = "https://ex.example/" + local;
        return p.properties().stream().filter(x -> x.iri().equals(iri)).findFirst().orElseThrow();
    }

    private static List<String> ex(String... locals) {
        return java.util.Arrays.stream(locals).map(l -> "https://ex.example/" + l).toList();
    }

    @Test
    void a_shape_gives_a_property_its_domain_and_range() {
        OwlProjection p = project(SHAPES);
        // 类自己就是形状、显式 targetClass：一个对象属性、一个数据属性
        OwlProperty t = byIri(p, "accountType");
        assertEquals(ex("Account"), t.domains());
        assertEquals(List.of(XSD + "string"), t.ranges());
        assertFalse(t.rangesUnion());
        // 隐式类目标：类加 sh:NodeShape，没写 targetClass
        assertEquals(ex("Identity"), byIri(p, "givenName").domains());
        // 有名字的节点形状引用有名字的属性形状
        OwlProperty s = byIri(p, "serial");
        assertEquals(ex("Device"), s.domains());
        assertEquals(List.of(XSD + "string"), s.ranges());
        // 约束在两个类上：domain 两个都要，range 是「哪个都行」
        OwlProperty o = byIri(p, "owner");
        assertEquals(ex("Account", "Tool"), o.domains());
        assertEquals(ex("Identity", "Organization"), o.ranges());
        assertTrue(o.rangesUnion(), "different classes on different shapes are alternatives");
    }

    @Test
    void a_declared_domain_and_range_win_over_a_shape() {
        OwlProjection p = project(SHAPES);
        OwlProperty v = byIri(p, "version");
        assertEquals(ex("Device"), v.domains(), "not the shape's Tool");
        assertEquals(List.of(XSD + "integer"), v.ranges(), "not the shape's string");
    }

    @Test
    void a_shape_reads_no_other_target_path_or_alternative() {
        OwlProjection p = project(SHAPES);
        // 逆向路径说的不是 uses 自己的 domain；targetSubjectsOf 不是一个类
        OwlProperty u = byIri(p, "uses");
        assertTrue(u.domains().isEmpty() && u.ranges().isEmpty(), u.toString());
        // sh:or 里的备选类型不读，路径本身照读
        OwlProperty v = byIri(p, "vendor");
        assertEquals(ex("Tool"), v.domains());
        assertTrue(v.ranges().isEmpty(), v.toString());
        // 形状点到的属性要文件里声明过，才建出来
        assertFalse(p.properties().stream().anyMatch(x -> x.iri().endsWith("undeclared")));
    }

    @Test
    void shape_predicates_are_consumed_not_reported_as_unprojected() {
        OwlProjection p = project(SHAPES);
        for (String consumed : List.of("targetClass", "property", "path", "class", "datatype")) {
            assertFalse(p.unprojected().containsKey(SH + consumed),
                    "sh:" + consumed + " is read: " + p.unprojected());
        }
        // 不读的照旧报告
        assertTrue(p.unprojected().containsKey(SH + "targetSubjectsOf"), p.unprojected().toString());
        // 三元组数照旧只数主语是 IRI 的：为属性形状留下的空节点三元组不算
        // （Rust 原断言用 oxttl 重数一遍；Java 侧以 Jena 重数同一份口径）
        long iriSubjects = countIriSubjectTriples(SHAPES.getBytes(StandardCharsets.UTF_8));
        assertEquals(iriSubjects, p.triples());
    }

    /** 用 Jena 按同一判定重数主语是 IRI 的三元组（对齐 Rust 测试里的 oxttl 重数）。 */
    private static long countIriSubjectTriples(byte[] bytes) {
        long[] count = {0};
        org.apache.jena.riot.system.StreamRDF sink = new org.apache.jena.riot.system.StreamRDF() {
            @Override
            public void start() {
            }

            @Override
            public void triple(org.apache.jena.graph.Triple t) {
                if (t.getSubject().isURI()) {
                    count[0]++;
                }
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
        org.apache.jena.riot.RDFParser.create()
                .source(new java.io.ByteArrayInputStream(bytes))
                .lang(org.apache.jena.riot.Lang.TURTLE)
                .base("urn:utopia:import")
                .parse(sink);
        return count[0];
    }

    // ---- against_real_packs：拿真包验一遍（与 Rust include_bytes 同源同字节） ----

    private static byte[] load(String name) {
        try (InputStream in = OwlProjectorTests.class.getClassLoader()
                .getResourceAsStream("packs/" + name);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            assertNotNull(in, "包文件在: " + name);
            new java.util.zip.GZIPInputStream(in).transferTo(out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * W3C Org 把 Organization/Role/Membership/Site/ChangeEvent 五者两两互斥，官方文件
     * 里每对只写一次。五个类每个都该看到四个对端——只按书写方向存的话，先被写到的
     * 那几个会缺对端，而缺哪个取决于文件里的行序。
     */
    @Test
    void w3c_org_declares_four_mutually_disjoint_classes() {
        OwlProjection p = OwlProjector.project(load("w3c-org.ttl.gz"), RdfFormat.TURTLE);
        for (String key : List.of("organization", "role", "membership", "site", "change_event")) {
            OwlClass c = p.classes().stream().filter(x -> x.key().equals(key)).findFirst()
                    .orElseThrow(() -> new AssertionError(key + " 该被投影出来"));
            assertEquals(4, c.disjointWith().size(),
                    key + " 该与另外四个互斥，实得 " + c.disjointWith());
        }
    }

    /**
     * IOF Core 引用 BFO，而 BFO 的 IRI 是编号（#324）。这里钉真包里那一个：它的
     * rdfs:label 写着 "process profile"；同时钉住反面——schema.org 那些带数字却有意义
     * 的 key 一个都不能被改写。
     */
    @Test
    void an_opaque_iri_in_a_real_pack_reads_from_its_label() {
        OwlProjection p = OwlProjector.project(load("iof-core.rdf.gz"), RdfFormat.RDFXML);
        OwlClass c = p.classes().stream().filter(x -> "process profile".equals(x.label()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "IOF Core 里那个 BFO 类该被投影出来"));
        assertEquals("process_profile", c.key(), "编号式 IRI 该改用 label 派生 key");
        assertTrue(p.classes().stream().noneMatch(x -> x.key().startsWith("bfo_0")),
                "不该再有编号形态的 key");

        OwlProjection s = OwlProjector.project(load("schema-org.ttl.gz"), RdfFormat.TURTLE);
        for (String key : List.of("gtin12", "gtin13", "sha256", "emissions_co2")) {
            assertTrue(s.properties().stream().anyMatch(x -> x.key().equals(key)),
                    key + " 是有意义的名字，不该被当成编号改写");
        }
    }

    /** IOF Core 声明了一批传递属性（before/after/occursDuring…）——R0 环检测唯一有
     * 真实依据的地方。 */
    @Test
    void iof_core_declares_transitive_properties() {
        OwlProjection p = OwlProjector.project(load("iof-core.rdf.gz"), RdfFormat.RDFXML);
        long n = p.properties().stream().filter(OwlProperty::transitive).count();
        assertTrue(n >= 8, "IOF 该有一批传递属性，实得 " + n);
    }

    /** FOAF 的 Person ⊥ Organization——最常撞的那一对。 */
    @Test
    void foaf_says_a_person_is_not_an_organization() {
        OwlProjection p = OwlProjector.project(load("foaf.rdf.gz"), RdfFormat.RDFXML);
        OwlClass person = p.classes().stream().filter(c -> c.key().equals("person")).findFirst()
                .orElseThrow();
        assertTrue(person.disjointWith().stream().anyMatch(d -> d.endsWith("Organization")),
                "foaf:Person 该与 Organization 互斥，实得 " + person.disjointWith());
    }
}
