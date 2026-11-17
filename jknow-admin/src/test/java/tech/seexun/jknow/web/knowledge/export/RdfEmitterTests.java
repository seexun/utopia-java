package tech.seexun.jknow.web.knowledge.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.graph.Triple;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.system.StreamRDFBase;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.extract.ExtractLib;
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
 * rdf.rs 973-2342 的 29 个内联测试<b>原值移植</b>——测试名与期望值逐字（这是防「差不多
 * 漂移」的锚：recon §八 风险③ 指认 literal 规则最易漂）。断言不改写；辅助函数的语义
 * 与 Rust 侧一一对应：
 *
 * <ul>
 *   <li>{@link #export}：导出一遍再<b>解析回来</b>——断言字符串里有没有某一段，证明不了
 *       文件语法是否合法；</li>
 *   <li>{@link #has}/{@link #objects}：解析回的三元组按文档序收（流式解析器逐条给出），
 *       资源折 {@code <iri>}、字面量折 {@code "lex"["^^<dt>]}——与 oxrdf 的 Term::to_string
 *       同形。</li>
 * </ul>
 */
class RdfEmitterTests {

    private static final ObjectMapper M = new ObjectMapper();
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private static final String XSD = "http://www.w3.org/2001/XMLSchema#";
    private static final String RDF_OBJECT = "http://www.w3.org/1999/02/22-rdf-syntax-ns#object";
    private static final String RDF_PREDICATE =
            "http://www.w3.org/1999/02/22-rdf-syntax-ns#predicate";
    private static final String RDF_FIRST = "http://www.w3.org/1999/02/22-rdf-syntax-ns#first";
    private static final String RDF_REST = "http://www.w3.org/1999/02/22-rdf-syntax-ns#rest";
    private static final String RDF_NIL = "http://www.w3.org/1999/02/22-rdf-syntax-ns#nil";

    static UUID kb() {
        return UUID.fromString("01a06dc4-f40a-7013-b09f-1b499e2e7441");
    }

    static UUID id(byte n) {
        return idOf(n);
    }

    /** Rust Uuid::from_bytes([n; 16])：全同字节按 MSB/LSB 组装。 */
    static UUID idOf(byte n) {
        byte[] b = new byte[16];
        Arrays.fill(b, n);
        return uuidFromBytes(b);
    }

    static UUID uuidFromBytes(byte[] raw) {
        long msb = 0;
        for (int i = 0; i < 8; i++) {
            msb = (msb << 8) | (raw[i] & 0xff);
        }
        long lsb = 0;
        for (int i = 8; i < 16; i++) {
            lsb = (lsb << 8) | (raw[i] & 0xff);
        }
        return new UUID(msb, lsb);
    }

    static OffsetDateTime at(String s) {
        return OffsetDateTime.parse(s);
    }

    static ExportClass cls(byte n, String key, String iri) {
        return new ExportClass(idOf(n), key, key, "", iri, List.of(), List.of());
    }

    static ExportRelation relation(byte n, String key, String iri, String kind) {
        return new ExportRelation(idOf(n), key, key, "", iri, kind,
                "attribute".equals(kind) ? "number" : null, null, "state", true, false, false,
                false, false, false, null, null, List.of(), List.of());
    }

    static ExportFact fact(byte n) {
        return new ExportFact(idOf(n), idOf((byte) 10), idOf((byte) 2), null, idOf((byte) 11),
                null, List.of(), null, null, null, null,
                // 读出来的区间（0022）：没起点就从锚点起，这里让它与记下的时刻同一天
                at("2026-01-01T00:00:00Z"), null, at("2026-01-01T00:00:00Z"), null, 0.9f, false,
                null, List.of(), List.of(), List.of(), kb(), kb(), kb(), null, false, false,
                false, false);
    }

    /** 导出一遍再解析回来，三元组按文档序收。 */
    static List<Qd> export(ExportFormat format, Emit emit) {
        Names names = Names.of(kb(), null);
        List<ExportClass> classes = List.of(cls((byte) 1, "person", "https://schema.org/Person"),
                cls((byte) 3, "team", null));
        List<ExportRelation> relations = List.of(
                relation((byte) 2, "works_for", "https://schema.org/worksFor", "relation"),
                relation((byte) 4, "headcount", null, "attribute"));
        RdfEmitter.Vocabulary vocab = RdfEmitter.vocabulary(names, classes, relations);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            RdfEmitter sink = RdfEmitter.start(format, buf);
            for (ExportClass c : classes) {
                sink.emitClass(vocab, c);
            }
            for (ExportRelation r : relations) {
                sink.emitRelation(vocab, r);
            }
            emit.run(sink, names, vocab);
            sink.finish();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        byte[] bytes = buf.toByteArray();
        List<Triple> triples = new ArrayList<>();
        Lang lang = format == ExportFormat.TURTLE ? Lang.TURTLE : Lang.JSONLD;
        // 12 位小数秒的 dateTime 是合法 XSD（秒的小数位不限长），Jena 的 XSDdateTime
        // 只认到纳秒、构造 LiteralLabel 时就解析——解析窗口内把 dateTime 类型换成
        // 词法保真版（用完即还原，不污染同 JVM 的其它测试）
        org.apache.jena.datatypes.TypeMapper tm = org.apache.jena.datatypes.TypeMapper.getInstance();
        String dtUri = XSD + "dateTime";
        org.apache.jena.datatypes.RDFDatatype original = tm.getTypeByName(dtUri);
        RdfLiterals.LexicalDatatype lexical = RdfLiterals.LexicalDatatype.of(dtUri);
        tm.unregisterDatatype(original);
        tm.registerDatatype(lexical);
        try {
        RDFParser.create().source(new ByteArrayInputStream(bytes)).lang(lang).checking(false)
                .parse(new StreamRDFBase() {
                    @Override
                    public void triple(Triple t) {
                        triples.add(t);
                    }
                });
        } finally {
            tm.unregisterDatatype(lexical);
            tm.registerDatatype(original);
        }
        List<Qd> out = new ArrayList<>(triples.size());
        for (Triple t : triples) {
            out.add(new Qd(subjectStr(t.getSubject()), t.getPredicate().getURI(),
                    objectStr(t.getObject())));
        }
        return out;
    }

    @FunctionalInterface
    interface Emit {
        void run(RdfEmitter sink, Names names, RdfEmitter.Vocabulary vocab) throws Exception;
    }

    record Qd(String s, String p, String o) {
    }

    static String subjectStr(Node n) {
        return n.isURI() ? n.getURI() : "_:" + n.getBlankNodeLabel();
    }

    static String objectStr(Node n) {
        if (n.isURI()) {
            return "<" + n.getURI() + ">";
        }
        if (n.isBlank()) {
            return "_:" + n.getBlankNodeLabel();
        }
        String lex = escape(n.getLiteralLexicalForm());
        String dt = n.getLiteralDatatypeURI();
        if (dt == null || (XSD + "string").equals(dt)) {
            return "\"" + lex + "\"";
        }
        return "\"" + lex + "\"^^<" + dt + ">";
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static boolean has(List<Qd> quads, String s, String p, String o) {
        for (Qd q : quads) {
            if (q.s().equals(s) && q.p().equals(p) && (q.o().equals(o) || q.o().equals("<" + o + ">"))) {
                return true;
            }
        }
        return false;
    }

    static List<String> objects(List<Qd> quads, String s, String p) {
        List<String> out = new ArrayList<>();
        for (Qd q : quads) {
            if (q.s().equals(s) && q.p().equals(p)) {
                out.add(q.o());
            }
        }
        return out;
    }

    static final String STMT =
            "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:fact:05050505-0505-0505-0505-050505050505";
    static final String SUBJ =
            "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:entity:0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a";
    static final String OBJ =
            "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:entity:0b0b0b0b-0b0b-0b0b-0b0b-0b0b0b0b0b0b";
    static final String WORKS_FOR = "https://schema.org/worksFor";

    /** 与 Rust 侧断言用的字面量文本同形（literal.to_string()）。 */
    static String litStr(Node lit) {
        return objectStr(lit);
    }

    /** 同一个属性值穿过所有出口；RDF 能解析不代表 XSD 字面文本符合其类型。 */
    static void assertAttributeLiteralExports(JsonNode value, String datatype, Expectation expected) {
        ExportFact template = fact((byte) 5);
        List<FactQualifier> quals = List.of(new FactQualifier(idOf((byte) 4), null, null, value,
                null, null));
        ExportFact attribute = new ExportFact(template.id(), template.subjectId(), idOf((byte) 4),
                template.surfacePredicate(), null, value, quals, null, null, null, null,
                template.holdsFrom(), template.holdsTo(), template.recordedAt(), null, 0.9f,
                false, null, List.of(), List.of(), List.of(), kb(), null,
                kb(), null, false, false, false, false);
        ExportDerived derived = new ExportDerived(idOf((byte) 7), idOf((byte) 10), idOf((byte) 4),
                null, value, null, idOf((byte) 9), null, null, null, null,
                at("2026-02-01T00:00:00Z"), null, 0.9f, "business", null, "Attribute conclusion",
                List.of(idOf((byte) 5)), List.of(), kb(), null, kb(), null, kb(), false, false,
                false, false);
        Names names = Names.of(kb(), null);
        ExportRelation base = relation((byte) 4, "value", null, "attribute");
        ExportRelation property = new ExportRelation(base.id(), base.key(), base.label(),
                base.description(), base.iri(), base.kind(), datatype, base.unit(),
                base.temporal(), base.functional(), base.inverseFunctional(), base.isTransitive(),
                base.isSymmetric(), base.isAsymmetric(), base.isIrreflexive(), base.inverseOf(),
                base.subPropertyOf(), base.domains(), base.ranges());
        String propertyIri = names.relation(property);
        String statementIri = names.fact(attribute.id());
        String subjectIri = names.entity(attribute.subjectId());
        String derivedIri = names.derived(derived.id());
        for (ExportFormat format : ExportFormat.values()) {
            List<Qd> quads = export(format, (sink, ns, ignored) -> {
                RdfEmitter.Vocabulary vocab = RdfEmitter.vocabulary(ns, List.of(),
                        List.of(property));
                sink.emitFact(ns, vocab, attribute, at("2026-06-01T00:00:00Z"));
                sink.emitDerived(ns, vocab, derived);
            });
            for (Object[] outlet : new Object[][]{
                    {"fact", statementIri, RDF_OBJECT},
                    {"current triple", subjectIri, propertyIri},
                    {"qualifier", statementIri, propertyIri},
                    {"derived fact", derivedIri, RDF_OBJECT}}) {
                List<Qd> found = quads.stream()
                        .filter(q -> q.s().equals(outlet[1]) && q.p().equals(outlet[2])).toList();
                String context = outlet[0] + ", " + format + ", " + datatype + ", " + value;
                if (expected != null) {
                    assertEquals(1, found.size(), context);
                    String o = found.get(0).o();
                    if (expected.datatype == null) {
                        assertEquals("\"" + escape(expected.lexical) + "\"", o, context);
                    } else {
                        assertEquals(
                                "\"" + escape(expected.lexical) + "\"^^<" + expected.datatype + ">",
                                o, context);
                    }
                } else {
                    assertTrue(found.isEmpty(), "unexpected object: " + context);
                }
            }
        }
    }

    /** Some((lexical, datatype)) / None：datatype 为 null 表示简单字面量。 */
    record Expectation(String lexical, String datatype) {
        static Expectation of(String lexical) {
            return new Expectation(lexical, null);
        }
    }

    // ------------------------------------------------------------------
    // 归一化数字/日期/字面量优先级五件套（rdf.rs:1212-1454）
    // ------------------------------------------------------------------

    @Test
    void confidence_half_values_round_to_even() {
        // Rust {:.2} 是 std flt2dec 的 half-to-even（M5 任务 1 审查 I-1）：0.125→"0.12"、
        // 0.625→"0.62"；Java Formatter HALF_UP 会给 "0.13"/"0.63"——规则取平均可达精确半值。
        // （Node.toString 对常用 XSD 用前缀缩写，词法值才是断言对象）
        assertEquals("\"0.12\"^^xsd:decimal",
                tech.seexun.jknow.web.knowledge.export.RdfLiterals.confidence(0.125f).toString());
        assertEquals("\"0.62\"^^xsd:decimal",
                tech.seexun.jknow.web.knowledge.export.RdfLiterals.confidence(0.625f).toString());
    }

    @Test
    void normalized_numbers_export_as_decimal_without_exponents() {
        String[][] table = {
                {"0.0000001", "0.0000001"},
                {"1e-7", "0.0000001"},
                {"1e20", "100000000000000000000"},
                {"-1e-7", "-0.0000001"},
                {"-1e20", "-100000000000000000000"},
                {"0", "0.0"},
                {"-0.0", "-0.0"},
                {"12.375", "12.375"},
                {"-12.375", "-12.375"},
                {"1.25e-7", "0.000000125"},
        };
        for (String[] row : table) {
            JsonNode input = M.valueToTree(parseJsonNumber(row[0]));
            JsonNode normalized = ExtractLib.normalizeAttrValue("number", input);
            assertNotNull(normalized, row[0]);
            assertAttributeLiteralExports(normalized, "number",
                    new Expectation(row[1], XSD + "decimal"));
            assertAttributeLiteralExports(F.objectNode().set("value", normalized), "number",
                    new Expectation(row[1], XSD + "decimal"));
        }
        // 已存的整数不能再经过 f64：这个值超过了双精度能逐个表示整数的范围。
        assertAttributeLiteralExports(F.objectNode().set("value", F.numberNode(9007199254740993L)),
                "number", new Expectation("9007199254740993", XSD + "decimal"));
        // 最小次正规数和最大有限数：不能因固定小数位变成零，也不能溢出。
        StringBuilder subZeros = new StringBuilder();
        for (int i = 0; i < 323; i++) {
            subZeros.append('0');
        }
        JsonNode minSub = ExtractLib.normalizeAttrValue("number",
                F.numberNode(Double.longBitsToDouble(1)));
        assertNotNull(minSub);
        assertAttributeLiteralExports(minSub, "number",
                new Expectation("0." + subZeros + "5", XSD + "decimal"));
        StringBuilder maxZeros = new StringBuilder("17976931348623157");
        for (int i = 0; i < 292; i++) {
            maxZeros.append('0');
        }
        JsonNode max = ExtractLib.normalizeAttrValue("number", F.numberNode(Double.MAX_VALUE));
        assertNotNull(max);
        assertAttributeLiteralExports(max, "number",
                new Expectation(maxZeros.toString(), XSD + "decimal"));
    }

    /** "0" 这样的整数字面量解析成整数节点（serde_json json!(0) 同形），带指数的成 f64。 */
    static Number parseJsonNumber(String raw) {
        try {
            return Double.parseDouble(raw) == Math.rint(Double.parseDouble(raw))
                    && !raw.contains(".") && !raw.contains("e") && !raw.contains("E")
                    ? (Number) Long.parseLong(raw) : (Number) Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return Double.parseDouble(raw);
        }
    }

    @Test
    void normalized_dates_export_their_precision_and_original_clock() {
        String[][] table = {
                {"2024", "2024", XSD + "gYear"},
                {"2024-07", "2024-07", XSD + "gYearMonth"},
                {"2024-07-15", "2024-07-15", XSD + "date"},
                {"2024-07-15T14:32:07Z", "2024-07-15T14:32:07Z", XSD + "dateTime"},
                {"2024-07-15T14:32:07+08:00", "2024-07-15T14:32:07+08:00", XSD + "dateTime"},
                {"2024-07-15T14:32:07.382-05:30", "2024-07-15T14:32:07.382-05:30", XSD + "dateTime"},
                {"2024-07-15T14:32:07.123456789012Z", "2024-07-15T14:32:07.123456789012Z",
                        XSD + "dateTime"},
                {"2024-07-15T14Z", "2024-07-15T14:00:00Z", XSD + "dateTime"},
                {"2024-07-15T14+08:00", "2024-07-15T14:00:00+08:00", XSD + "dateTime"},
                {"2024-07-15T14:32Z", "2024-07-15T14:32:00Z", XSD + "dateTime"},
                {"2024-07-15T14:32+08:00", "2024-07-15T14:32:00+08:00", XSD + "dateTime"},
                {"2024-07-15T14:32+0800", "2024-07-15T14:32:00+08:00", XSD + "dateTime"},
                {"2024-07-15T14+08", "2024-07-15T14:00:00+08:00", XSD + "dateTime"},
                {"2024-07-15T14:32z", "2024-07-15T14:32:00Z", XSD + "dateTime"},
                {"2024-07-15T14+14", "2024-07-15T14:00:00+14:00", XSD + "dateTime"},
                {"2024-07-15T14-14", "2024-07-15T14:00:00-14:00", XSD + "dateTime"},
                {"2024-07-15 14:32+08:00", "2024-07-15T14:32:00+08:00", XSD + "dateTime"},
                {"2024-07-15T14", "2024-07-15T14:00:00", XSD + "dateTime"},
                {"2024-07-15T14:32", "2024-07-15T14:32:00", XSD + "dateTime"},
                {"2024-07-15T14:32:07.382", "2024-07-15T14:32:07.382", XSD + "dateTime"},
        };
        for (String[] row : table) {
            JsonNode normalized = ExtractLib.normalizeAttrValue("date", F.textNode(row[0]));
            assertNotNull(normalized, row[0]);
            assertAttributeLiteralExports(normalized, "date", new Expectation(row[1], row[2]));
            assertAttributeLiteralExports(F.objectNode().set("value", normalized), "date",
                    new Expectation(row[1], row[2]));
        }
    }

    @Test
    void attribute_calendar_years_are_not_cut_to_four_characters() {
        String[][] table = {
                {"-0001-07", "-0001-07", XSD + "gYearMonth"},
                {"-0001-07-15", "-0001-07-15", XSD + "date"},
                {"+10000-07", "10000-07", XSD + "gYearMonth"},
                {"+10000-07-15", "10000-07-15", XSD + "date"},
                {"+10000-07-15T14:32:07.382Z", "10000-07-15T14:32:07.382Z", XSD + "dateTime"},
        };
        for (String[] row : table) {
            JsonNode normalized = ExtractLib.normalizeAttrValue("date", F.textNode(row[0]));
            assertNotNull(normalized, row[0]);
            assertAttributeLiteralExports(normalized, "date", new Expectation(row[1], row[2]));
        }
    }

    @Test
    void typed_attributes_keep_value_precedence_prose_and_missing_objects() throws Exception {
        record Case(JsonNode value, String datatype, Expectation expected) {
        }
        List<Case> cases = List.of(
                new Case(read("{\"value\": 12.5, \"summary\": \"estimate\"}"), "number",
                        new Expectation("12.5", XSD + "decimal")),
                new Case(read("{\"value\": \"2024\", \"summary\": \"sometime in 2024\"}"), "date",
                        new Expectation("2024", XSD + "gYear")),
                new Case(read("{\"value\": 0, \"summary\": \"\"}"), "number",
                        new Expectation("0", XSD + "decimal")),
                new Case(read("{\"summary\": \"before the merge\"}"), "date",
                        new Expectation("before the merge", null)),
                new Case(read("{\"value\": null, \"summary\": \"not specified\"}"), "number",
                        new Expectation("not specified", null)),
                new Case(read("{\"class\": \"gas_well\"}"), "number",
                        new Expectation("gas_well", null)),
                new Case(read("{\"value\": \"45 days after the Trigger Date\", \"relative\": true}"),
                        "date", new Expectation("45 days after the Trigger Date", null)),
                new Case(read("{\"value\": null}"), "date", null),
                new Case(read("{\"confidence\": 0.9}"), "number", null),
                new Case(read("{\"summary\": \"\", \"class\": \"\"}"), "date", null),
                new Case(M.nullNode(), "number", null));
        for (Case c : cases) {
            assertAttributeLiteralExports(c.value(), c.datatype, c.expected());
        }
    }

    static JsonNode read(String json) throws Exception {
        return M.readTree(json);
    }

    @Test
    void unbound_literal_objects_survive_both_formats() throws Exception {
        List<JsonNode> values = List.of(
                read("{\"value\": \"待复检\"}"),
                read("{\"value\": \"quote: \\\" and slash: \\\\\"}"),
                read("{\"value\": \"\"}"),
                read("{\"value\": 0}"),
                read("{\"value\": false}"),
                read("{\"value\": null, \"summary\": \"not specified\"}"),
                M.nullNode());
        for (JsonNode value : values) {
            ExportFact template = fact((byte) 5);
            ExportFact f = new ExportFact(template.id(), template.subjectId(), null, "状态", null,
                    value, List.of(), null, null, null, null, template.holdsFrom(),
                    template.holdsTo(), template.recordedAt(), null, 0.9f, false,
                    idOf((byte) 6), List.of(idOf((byte) 20)), List.of("设备 A 待复检"),
                    List.of(), kb(), null, null, null, false, false, false, false);
            for (boolean retracted : new boolean[]{false, true}) {
                ExportFact ff = retracted
                        ? new ExportFact(f.id(), f.subjectId(), f.predicateId(),
                                f.surfacePredicate(), f.objectId(), f.objectValue(),
                                f.qualifiers(), f.validFrom(), f.validFromPrecision(),
                                f.validTo(), f.validToPrecision(), f.holdsFrom(), f.holdsTo(),
                                f.recordedAt(), at("2026-02-01T00:00:00Z"), f.confidence(),
                                f.implied(), f.supersedes(), f.documents(), f.quotes(),
                                f.quoteOrigins(), f.subjectKb(), f.objectKb(), f.predicateKb(),
                                f.supersedesKb(), f.foreignDocument(), f.foreignChunk(),
                                f.subjectMerged(), f.objectMerged())
                        : f;
                List<Set<String>> sets = new ArrayList<>();
                for (ExportFormat format : ExportFormat.values()) {
                    List<Qd> quads = export(format, (sink, names, vocab) ->
                            sink.emitFact(names, vocab, ff, at("2026-06-01T00:00:00Z")));
                    Node lit = RdfLiterals.literalValue(value, null);
                    List<String> expected = lit == null ? List.of() : List.of(litStr(lit));
                    assertEquals(expected, objects(quads, STMT, RDF_OBJECT),
                            "unbound statement lost its literal object: " + value);
                    assertTrue(objects(quads, STMT, RDF_PREDICATE).isEmpty());
                    assertFalse(quads.stream().anyMatch(q -> q.s().equals(SUBJ)));
                    Set<String> set = new HashSet<>();
                    for (Qd q : quads) {
                        set.add(q.s() + " " + q.p() + " " + q.o());
                    }
                    sets.add(set);
                }
                assertEquals(sets.get(0), sets.get(1));
            }
        }
    }

    @Test
    void bound_literal_datatypes_survive_both_formats() throws Exception {
        record Case(String datatype, JsonNode value, Node expected) {
        }
        List<Case> cases = List.of(
                new Case("number", F.numberNode(0),
                        RdfLiterals.typed("0", XSDDatatype.XSDdecimal)),
                new Case("text", F.textNode("待复检"),
                        RdfLiterals.text("待复检")),
                new Case("bool", F.booleanNode(false),
                        RdfLiterals.typed("false", XSDDatatype.XSDboolean)));
        for (Case c : cases) {
            ExportFact template = fact((byte) 5);
            ExportFact f = new ExportFact(template.id(), template.subjectId(), idOf((byte) 4),
                    null, null, F.objectNode().set("value", c.value()), List.of(), null, null,
                    null, null, template.holdsFrom(), template.holdsTo(), template.recordedAt(),
                    null, 0.9f, false, null, List.of(), List.of(), List.of(), kb(), null,
                    kb(), null, false, false, false, false);
            for (ExportFormat format : ExportFormat.values()) {
                List<Qd> quads = export(format, (sink, names, ignored) -> {
                    ExportRelation property = relation((byte) 4, "value", null, "attribute");
                    // 属性 datatype 按 case 换
                    ExportRelation withType = new ExportRelation(property.id(), property.key(),
                            property.label(), property.description(), property.iri(),
                            property.kind(), c.datatype(), property.unit(), property.temporal(),
                            property.functional(), property.inverseFunctional(),
                            property.isTransitive(), property.isSymmetric(), property.isAsymmetric(),
                            property.isIrreflexive(), property.inverseOf(), property.subPropertyOf(),
                            property.domains(), property.ranges());
                    RdfEmitter.Vocabulary vocab = RdfEmitter.vocabulary(names, List.of(),
                            List.of(withType));
                    sink.emitFact(names, vocab, f, at("2026-06-01T00:00:00Z"));
                });
                assertEquals(List.of(litStr(c.expected())), objects(quads, STMT, RDF_OBJECT));
                boolean flat = quads.stream().anyMatch(q -> q.s().equals(SUBJ)
                        && q.p().equals(namesLocal().relation(
                                new ExportRelation(idOf((byte) 4), "value", null, "attribute",
                                        null, null, null, null, null, false, false, false, false,
                                        false, false, null, null, List.of(), List.of())))
                        && q.o().equals(litStr(c.expected())));
                // Rust 断言：平铺三元组的宾语等于同一字面量（current triple 只在 holds 区间内出）
                assertTrue(quads.stream().anyMatch(q -> q.s().equals(SUBJ)
                        && q.o().equals(litStr(c.expected()))),
                        "typed literal must reach the flattened triple");
            }
        }
    }

    private static Names namesLocal() {
        return Names.of(kb(), null);
    }

    @Test
    void an_absent_object_is_not_an_empty_literal() {
        ExportFact template = fact((byte) 5);
        ExportFact f = new ExportFact(template.id(), template.subjectId(), null, null, null, null,
                List.of(), null, null, null, null, template.holdsFrom(), template.holdsTo(),
                template.recordedAt(), null, 0.9f, false, null, List.of(), List.of(), List.of(),
                kb(), null, null, null, false, false, false, false);
        for (ExportFormat format : ExportFormat.values()) {
            List<Qd> quads = export(format, (sink, names, vocab) ->
                    sink.emitFact(names, vocab, f, at("2026-06-01T00:00:00Z")));
            assertTrue(objects(quads, STMT, RDF_OBJECT).isEmpty());
        }
    }

    /**
     * #831：object_value 是 {"summary": "..."} 这种带结构对象时，老代码会把整个对象序列化
     * 成 JSON 字符串当字面量。修复后 value 优先、summary 是替补；summary/class 落 xsd:string
     * 而不套属性声明的类型；解析失败就没有 rdf:object，和 #821 同一套原则。
     */
    @Test
    void an_object_value_with_summary_or_value_does_not_emit_a_json_literal() throws Exception {
        record Case(String what, JsonNode value, List<String> expected) {
        }
        List<Case> cases = List.of(
                new Case("value wins over summary when both are present",
                        read("{\"value\": 12.5, \"summary\": \"about a dozen\"}"),
                        List.of("\"12.5\"^^<" + XSD + "decimal>")),
                new Case("value alone resolves to its scalar string",
                        read("{\"value\": \"42\"}"),
                        List.of("\"42\"^^<" + XSD + "decimal>")),
                new Case("summary alone is prose: a string, never the declared type",
                        read("{\"summary\": \"before the merge\"}"),
                        List.of("\"before the merge\"")),
                new Case("empty summary with a value resolves to the value",
                        read("{\"summary\": \"\", \"value\": \"0\"}"),
                        List.of("\"0\"^^<" + XSD + "decimal>")),
                new Case("null value and no summary → no rdf:object",
                        read("{\"value\": null}"), List.of()),
                new Case("a typing conclusion keeps its class as a string",
                        read("{\"class\": \"gas_well\"}"), List.of("\"gas_well\"")),
                new Case("object with no recognised key → no rdf:object",
                        read("{\"confidence\": 0.9}"), List.of()));
        for (Case c : cases) {
            ExportFact template = fact((byte) 5);
            ExportFact f = new ExportFact(template.id(), template.subjectId(), idOf((byte) 4),
                    null, null, c.value(), List.of(), null, null, null, null, template.holdsFrom(),
                    template.holdsTo(), template.recordedAt(), null, 0.9f, false, null,
                    List.of(), List.of(), List.of(), kb(), null, kb(), null, false, false,
                    false, false);
            for (ExportFormat format : ExportFormat.values()) {
                List<Qd> quads = export(format, (sink, names, vocab) ->
                        sink.emitFact(names, vocab, f, at("2026-06-01T00:00:00Z")));
                List<String> got = objects(quads, STMT, RDF_OBJECT);
                assertEquals(c.expected(), got, c.what() + " (format=" + format + ")");
            }
        }
    }

    @Test
    void an_imported_class_keeps_its_own_iri() {
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) -> {
        });
        String rdfType = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
        // 导入来的 schema.org 类导出去还是 schema:Person
        assertTrue(has(quads, "https://schema.org/Person", rdfType,
                "http://www.w3.org/2002/07/owl#Class"));
        // 本体自己长的用 key 铸 IRI，读得懂
        assertTrue(has(quads,
                "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:class:team", rdfType,
                "http://www.w3.org/2002/07/owl#Class"));
        // 公理照抄：一致性检查按它跑，读的人要能自己复算
        assertTrue(has(quads, WORKS_FOR, rdfType,
                "http://www.w3.org/2002/07/owl#FunctionalProperty"));
    }

    @Test
    void a_live_fact_is_both_a_statement_and_a_triple() {
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, fact((byte) 5), at("2026-06-01T00:00:00Z")));
        assertTrue(has(quads, STMT, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement"));
        assertTrue(has(quads, STMT, "http://www.w3.org/1999/02/22-rdf-syntax-ns#subject", SUBJ));
        assertTrue(has(quads, SUBJ, WORKS_FOR, OBJ),
                "仍然成立的事实要有一条平铺三元组——不看具体化的消费者靠它拿到现状");
        assertEquals(List.of("\"0.90\"^^<" + XSD + "decimal>"),
                objects(quads, STMT, "urn:utopia:ns:confidence"));
    }

    @Test
    void a_closed_or_retracted_fact_is_a_statement_only() {
        // 区间已闭合：世界轴上它已经结束
        ExportFact template = fact((byte) 5);
        ExportFact closed = new ExportFact(template.id(), template.subjectId(),
                template.predicateId(), null, template.objectId(), null, List.of(),
                at("2023-01-01T00:00:00Z"), "day", at("2024-07-01T00:00:00Z"), "day",
                at("2023-01-01T00:00:00Z"), at("2024-07-01T00:00:00Z"), template.recordedAt(),
                null, 0.9f, false, null, List.of(), List.of(), List.of(), kb(), kb(), kb(), null,
                false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, closed, at("2026-06-01T00:00:00Z")));
        assertFalse(has(quads, SUBJ, WORKS_FOR, OBJ),
                "结束了的关系不该以现在时写出去——那正是导出会骗人的地方");
        assertEquals(List.of("\"2024-07-01\"^^<" + XSD + "date>"),
                objects(quads, STMT, "https://schema.org/validThrough"));

        // 记录轴上被撤回：世界轴上它甚至还“开着”，但我们已经不这么认为了
        ExportFact retracted = new ExportFact(template.id(), template.subjectId(),
                template.predicateId(), null, template.objectId(), null, List.of(), null, null,
                null, null, template.holdsFrom(), template.holdsTo(), template.recordedAt(),
                at("2026-03-01T00:00:00Z"), 0.9f, false, null, List.of(), List.of(), List.of(),
                kb(), kb(), kb(), null, false, false, false, false);
        List<Qd> quads2 = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, retracted, at("2026-06-01T00:00:00Z")));
        assertFalse(has(quads2, SUBJ, WORKS_FOR, OBJ), "撤回的不出平铺三元组");
        assertEquals(List.of("\"2026-03-01T00:00:00Z\"^^<" + XSD + "dateTime>"),
                objects(quads2, STMT, "http://www.w3.org/ns/prov#invalidatedAtTime"));
    }

    @Test
    void record_axis_subseconds_round_trip_without_changing_world_precision() {
        for (String timestamp : new String[]{
                "2026-09-20T00:00:00Z", "2026-09-20T00:00:00.100Z", "2026-09-20T00:00:00.100001Z",
                "2026-09-20T00:00:00.100002Z", "2026-09-20T00:00:00.123456789Z"}) {
            OffsetDateTime original = at(timestamp);
            String lexical = RdfLiterals.dtText(original);
            assertEquals(XSD + "dateTime",
                    NodeFactory.createLiteralDT(lexical, RdfLiterals.LexicalDatatype.of(
                            XSD + "dateTime")).getLiteralDatatypeURI());
            assertEquals(original, OffsetDateTime.parse(lexical));
        }
        assertEquals("2026-09-20T00:00:00Z", RdfLiterals.dtText(at("2026-09-20T00:00:00Z")));
        OffsetDateTime instant = at("2026-09-20T12:34:56.123456Z");
        String[][] table = {
                {"year", "2026", XSD + "gYear"},
                {"month", "2026-09", XSD + "gYearMonth"},
                {"day", "2026-09-20", XSD + "date"},
                {"hour", "2026-09-20T12:34:56Z", XSD + "dateTime"},
                {"minute", "2026-09-20T12:34:56Z", XSD + "dateTime"},
                {"second", "2026-09-20T12:34:56Z", XSD + "dateTime"},
        };
        for (String[] row : table) {
            Node got = RdfLiterals.worldTime(instant, row[0]);
            assertEquals(NodeFactory.createLiteralDT(row[1], RdfLiterals.LexicalDatatype.of(row[2])), got);
        }
    }

    @Test
    void a_year_stays_a_year() {
        ExportFact template = fact((byte) 5);
        ExportFact coarse = new ExportFact(template.id(), template.subjectId(),
                template.predicateId(), null, template.objectId(), null, List.of(),
                at("2023-01-01T00:00:00Z"), "year", null, null, template.holdsFrom(),
                template.holdsTo(), template.recordedAt(), null, 0.9f, false, null, List.of(),
                List.of(), List.of(), kb(), kb(), kb(), null, false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, coarse, at("2026-06-01T00:00:00Z")));
        // 只量到年份就写 gYear。一律写成 xsd:date 等于替账本补上它没有过的确定性
        assertEquals(List.of("\"2023\"^^<" + XSD + "gYear>"),
                objects(quads, STMT, "https://schema.org/validFrom"));
    }

    @Test
    void an_ended_but_undated_relation_says_so() {
        ExportFact template = fact((byte) 5);
        ExportFact ended = new ExportFact(template.id(), template.subjectId(),
                template.predicateId(), null, template.objectId(), null, List.of(), null, null,
                null, "unknown", template.holdsFrom(), template.holdsFrom(), template.recordedAt(),
                null, 0.9f, false, null, List.of(), List.of(), List.of(), kb(), kb(), kb(), null,
                false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, ended, at("2026-06-01T00:00:00Z")));
        // 「结束了，但不知道哪天」不能压成「至今仍成立」
        assertTrue(has(quads, STMT, "urn:utopia:ns:endedUnknown",
                "\"true\"^^<" + XSD + "boolean>"));
        assertFalse(has(quads, SUBJ, WORKS_FOR, OBJ));
    }

    @Test
    void a_predicate_the_ontology_never_accepted_is_not_invented() {
        ExportFact template = fact((byte) 5);
        ExportFact bare = new ExportFact(template.id(), template.subjectId(), null, "acquired",
                template.objectId(), null, List.of(), null, null, null, null,
                template.holdsFrom(), template.holdsTo(), template.recordedAt(), null, 0.9f,
                false, null, List.of(), List.of(), List.of(), kb(), kb(), null, null, false,
                false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, bare, at("2026-06-01T00:00:00Z")));
        assertTrue(objects(quads, STMT, RDF_PREDICATE).isEmpty(),
                "本体没有这条关系就不铸一个谓词出来（0010）");
        assertEquals(List.of("\"acquired\""), objects(quads, STMT, "urn:utopia:ns:proposedPredicate"));
    }

    @Test
    void an_attribute_carries_its_datatype() throws Exception {
        ExportFact template = fact((byte) 5);
        ExportFact attr = new ExportFact(template.id(), template.subjectId(), idOf((byte) 4),
                null, null, read("{\"value\": 42, \"unit\": \"people\"}"), List.of(), null, null,
                null, null, template.holdsFrom(), template.holdsTo(), template.recordedAt(), null,
                0.9f, false, null, List.of(), List.of(), List.of(), kb(), null, kb(), null,
                false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, attr, at("2026-06-01T00:00:00Z")));
        assertEquals(List.of("\"42\"^^<" + XSD + "decimal>"), objects(quads, STMT, RDF_OBJECT));
    }

    /**
     * 日期属性上相对的值（#681 §4）导出成普通字符串，陈述上另有一行说它是相对的——
     * 写成 xsd:date 的字面量不合法，严格的解析器会整份拒收。
     */
    @Test
    void a_relative_deadline_is_a_string_that_says_it_is_relative() throws Exception {
        Node dated = RdfLiterals.literalValue(read("{\"value\": \"2020-06-23\"}"), "date");
        assertNotNull(dated);
        assertEquals(XSD + "date", dated.getLiteralDatatypeURI());
        Node relative = RdfLiterals.literalValue(
                read("{\"value\": \"45 days after the Trigger Date\", \"relative\": true}"), "date");
        assertNotNull(relative);
        assertEquals(XSD + "string", relative.getLiteralDatatypeURI());
        assertEquals("45 days after the Trigger Date", relative.getLiteralLexicalForm());

        ExportFact template = fact((byte) 5);
        ExportFact attr = new ExportFact(template.id(), template.subjectId(), idOf((byte) 4),
                null, null,
                read("{\"value\": \"45 days after the Trigger Date\", \"relative\": true}"),
                List.of(), null, null, null, null, template.holdsFrom(), template.holdsTo(),
                template.recordedAt(), null, 0.9f, false, null, List.of(), List.of(), List.of(),
                kb(), null, kb(), null, false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, attr, at("2026-06-01T00:00:00Z")));
        assertTrue(has(quads, STMT, "urn:utopia:ns:relativeValue",
                "\"true\"^^<" + XSD + "boolean>"));
    }

    /**
     * 业务规则的结论也要出现在导出里，而且宾语是<b>字面值</b>。这一条挡的是一次静默丢失：
     * 取数那边原本 JOIN rules，而业务规则的 rule_id 是 NULL——整条结论会被内连接挡在文件
     * 之外。活动的标签也要是规则自己的名字，「business」对读的人没有意义。
     */
    @Test
    void a_rule_conclusion_reaches_the_export_as_a_literal() throws Exception {
        ExportDerived derived = new ExportDerived(idOf((byte) 7), idOf((byte) 10), idOf((byte) 2),
                null, read("{\"class\": \"gas_well\"}"), null, idOf((byte) 9), null, null, null,
                null, at("2026-02-01T00:00:00Z"), null, 0.9f, "business", null,
                "Gas-bearing well", List.of(idOf((byte) 5)), List.of(), kb(), null, kb(), null,
                kb(), false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitDerived(names, vocab, derived));
        String stmt =
                "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:derived:07070707-0707-0707-0707-070707070707";
        // 派生标记还在——它仍旧是推出来的，不是谁断言的
        assertTrue(has(quads, stmt, "urn:utopia:ns:derived", "\"true\"^^<" + XSD + "boolean>"));
        // 宾语是字面值而不是一个实体 IRI：分类结论按键解析成类名，落成 xsd:string
        List<String> obj = objects(quads, stmt, RDF_OBJECT);
        assertEquals(1, obj.size(), "结论要有宾语");
        assertEquals("\"gas_well\"", obj.get(0),
                "分类结论的宾语该是类名本身的字符串字面量");
        // 规则资源说出自己的家族（#902）：业务规则，没有公理种类可言
        String rule = namesLocal().rule(idOf((byte) 9));
        assertTrue(has(quads, rule, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "<urn:utopia:ns:BusinessRule>"));
        assertTrue(objects(quads, rule, "urn:utopia:ns:axiomKind").isEmpty());
        assertTrue(objects(quads, rule, "urn:utopia:ns:declaredOn").isEmpty());
        // 前提照常挂着：审计顺着 prov:used 走得到那两条读数
        assertEquals(1, objects(quads, stmt, "http://www.w3.org/ns/prov#used").size());
        // 活动的标签是规则的名字
        String ruleIri =
                "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:rule:09090909-0909-0909-0909-090909090909";
        assertEquals(List.of("\"Gas-bearing well\""),
                objects(quads, ruleIri, "http://www.w3.org/2000/01/rdf-schema#label"),
                "推理活动要以规则名示人");
    }

    @Test
    void declared_property_links_are_local_explicit_and_order_independent() {
        ExportRelation root = relation((byte) 21, "root", "https://example.test/root", "relation");
        ExportRelation inverse = withRelationExtras(relation((byte) 22, "inverse", null,
                "relation"), root.id(), null);
        ExportRelation child = withRelationExtras(relation((byte) 23, "child", null, "relation"),
                null, root.id());
        ExportRelation leaf = withRelationExtras(relation((byte) 24, "leaf", null, "relation"),
                null, child.id());
        ExportRelation missing = withRelationExtras(relation((byte) 25, "unresolved", null,
                "relation"), idOf((byte) 98), idOf((byte) 99));
        List<ExportRelation> relations = new ArrayList<>(List.of(root, inverse, child, leaf, missing));
        List<Set<String>> sets = new ArrayList<>();
        for (boolean reverse : new boolean[]{false, true}) {
            if (reverse) {
                java.util.Collections.reverse(relations);
            }
            for (ExportFormat format : ExportFormat.values()) {
                List<Qd> quads = export(format, (sink, names, ignored) -> {
                    RdfEmitter.Vocabulary vocab = RdfEmitter.vocabulary(names, List.of(), relations);
                    for (ExportRelation r : relations) {
                        sink.emitRelation(vocab, r);
                    }
                });
                Names names = namesLocal();
                String owlInverseOf = "http://www.w3.org/2002/07/owl#inverseOf";
                Set<String> expected = Set.of(
                        tripleStr(names.relation(relations.get(idxOf(relations, (byte) 22))),
                                owlInverseOf, names.relation(root)),
                        tripleStr(names.relation(relations.get(idxOf(relations, (byte) 23))),
                                RDFS_SUBPROPERTY, names.relation(root)),
                        tripleStr(names.relation(relations.get(idxOf(relations, (byte) 24))),
                                RDFS_SUBPROPERTY,
                                names.relation(relations.get(idxOf(relations, (byte) 23)))));
                Set<String> links = new HashSet<>();
                for (Qd q : quads) {
                    if (q.p().equals(owlInverseOf) || q.p().equals(RDFS_SUBPROPERTY)) {
                        links.add(tripleStr(q.s(), q.p(), strip(q.o())));
                    }
                }
                assertEquals(expected, links, "only stored, local links should be emitted");
                Set<String> set = new HashSet<>();
                for (Qd q : quads) {
                    set.add(q.s() + " " + q.p() + " " + q.o());
                }
                sets.add(set);
            }
        }
        for (Set<String> set : sets.subList(1, sets.size())) {
            assertEquals(sets.get(0), set);
        }
    }

    private static final String RDFS_SUBPROPERTY =
            "http://www.w3.org/2000/01/rdf-schema#subPropertyOf";

    static int idxOf(List<ExportRelation> relations, byte n) {
        for (int i = 0; i < relations.size(); i++) {
            if (relations.get(i).id().equals(idOf(n))) {
                return i;
            }
        }
        throw new IllegalArgumentException();
    }

    static ExportRelation withRelationExtras(ExportRelation r, UUID inverseOf, UUID subPropertyOf) {
        return new ExportRelation(r.id(), r.key(), r.label(), r.description(), r.iri(), r.kind(),
                r.datatype(), r.unit(), r.temporal(), r.functional(), r.inverseFunctional(),
                r.isTransitive(), r.isSymmetric(), r.isAsymmetric(), r.isIrreflexive(), inverseOf,
                subPropertyOf, r.domains(), r.ranges());
    }

    static String tripleStr(String s, String p, String o) {
        return s + " " + p + " " + o;
    }

    static String strip(String o) {
        return o.startsWith("<") ? o.substring(1, o.length() - 1) : o;
    }

    @Test
    void a_derivation_says_it_is_one_and_names_its_premises() {
        ExportDerived derived = new ExportDerived(idOf((byte) 7), idOf((byte) 10), idOf((byte) 2),
                idOf((byte) 11), null, idOf((byte) 8), null, null, null, null, null,
                at("2026-02-01T00:00:00Z"), null, 0.8f, "transitive", idOf((byte) 2), null,
                List.of(idOf((byte) 5)), List.of(idOf((byte) 6)), kb(), kb(), kb(), kb(), null,
                false, false, false, false);
        for (ExportFormat format : ExportFormat.values()) {
            List<Qd> quads = export(format, (sink, names, vocab) ->
                    sink.emitDerived(names, vocab, derived));
            String stmt =
                    "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:derived:07070707-0707-0707-0707-070707070707";
            assertTrue(has(quads, stmt, "urn:utopia:ns:derived",
                    "\"true\"^^<" + XSD + "boolean>"));
            assertEquals(List.of("<" + STMT + ">",
                            "<" + namesLocal().derived(idOf((byte) 6)) + ">"),
                    objects(quads, stmt, "http://www.w3.org/ns/prov#used"));
            assertFalse(has(quads, SUBJ, WORKS_FOR, OBJ),
                    "推出来的边不写成平铺三元组：那会让人把引擎的结论当成文档里的话");
            // 规则资源说出自己的家族、种类和声明所在的谓词（#902）。这里声明谓词就是
            // 结论谓词（传递），指向同一个 IRI
            String rule = namesLocal().rule(idOf((byte) 8));
            assertTrue(has(quads, rule, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                    "<urn:utopia:ns:AxiomRule>"));
            assertEquals(List.of("\"transitive\""), objects(quads, rule, "urn:utopia:ns:axiomKind"));
            assertEquals(objects(quads, rule, "urn:utopia:ns:declaredOn"),
                    objects(quads, stmt, RDF_PREDICATE));
        }
    }

    @Test
    void evidence_points_back_at_the_document_and_the_sentence() {
        ExportFact template = fact((byte) 5);
        ExportFact cited = new ExportFact(template.id(), template.subjectId(),
                template.predicateId(), null, template.objectId(), null, List.of(), null, null,
                null, null, template.holdsFrom(), template.holdsTo(), template.recordedAt(), null,
                0.9f, false, null, List.of(idOf((byte) 12)),
                List.of("Lin Zhao joined Acme in 2023."), List.of(), kb(), kb(), kb(), null,
                false, false, false, false);
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, cited, at("2026-06-01T00:00:00Z")));
        assertEquals(List.of("<urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441"
                        + ":document:0c0c0c0c-0c0c-0c0c-0c0c-0c0c0c0c0c0c>"),
                objects(quads, STMT, "http://www.w3.org/ns/prov#wasDerivedFrom"));
        assertEquals(List.of("\"Lin Zhao joined Acme in 2023.\""),
                objects(quads, STMT, "urn:utopia:ns:quote"));
    }

    @Test
    void json_ld_carries_the_same_triples() {
        List<Qd> ttl = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFact(names, vocab, fact((byte) 5), at("2026-06-01T00:00:00Z")));
        List<Qd> jsonld = export(ExportFormat.JSONLD, (sink, names, vocab) ->
                sink.emitFact(names, vocab, fact((byte) 5), at("2026-06-01T00:00:00Z")));
        assertEquals(ttl.size(), jsonld.size(), "两种格式是同一张图的两种写法，三元组数目必须一样");
        assertTrue(has(jsonld, SUBJ, WORKS_FOR, OBJ));
    }

    @Test
    void a_published_deployment_can_mint_http_iris() {
        Names names = Names.of(kb(), "https://acme.example/utopia/");
        assertEquals("https://acme.example/utopia/kb/01a06dc4-f40a-7013-b09f-1b499e2e7441"
                        + "/entity/0a0a0a0a-0a0a-0a0a-0a0a-0a0a0a0a0a0a",
                names.entity(idOf((byte) 10)));
        // 非 http 的 base 拒掉：拼出来的会是一份谁也解析不了的文件
        assertThrowsValidation(() -> Names.of(kb(), "javascript:alert(1)"));
        assertThrowsValidation(() -> Names.of(kb(), "https://acme.example/a b"));
    }

    static void assertThrowsValidation(Runnable r) {
        try {
            r.run();
            throw new AssertionError("expected AppException");
        } catch (AppException expected) {
            // ok
        }
    }

    static final String CONFLICT =
            "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:conflict:07070707-0707-0707-0707-070707070707";
    static final String VIOLATION =
            "urn:utopia:kb:01a06dc4-f40a-7013-b09f-1b499e2e7441:violation:08080808-0808-0808-0808-080808080808";

    static ExportFactConflict conflict(String status, String resolution) {
        return new ExportFactConflict(idOf((byte) 7), idOf((byte) 5), idOf((byte) 6),
                "simultaneous", at("2026-03-10T00:00:00Z"), status, resolution,
                "open".equals(status) ? null : at("2026-03-20T00:00:00Z"), kb(), kb());
    }

    static ExportAxiomViolation violation(String kind, String status, String resolution,
                                          List<UUID> path) {
        return new ExportAxiomViolation(idOf((byte) 8), kind, idOf((byte) 5), idOf((byte) 6),
                path, status, resolution, at("2026-03-15T00:00:00Z"),
                "open".equals(status) ? null : at("2026-03-20T00:00:00Z"),
                "open".equals(status) ? idOf((byte) 2) : null,
                "open".equals(status) ? kind : null, kb(), kb(), kb(), false);
    }

    @Test
    void a_contest_names_both_sides_and_its_disposition() {
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitFactConflict(names, conflict("open", null)));
        assertTrue(has(quads, CONFLICT, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "<urn:utopia:ns:FactConflict>"));
        Names names = namesLocal();
        // 两席有方向：旧断言是 prior，新到的是 incoming
        assertEquals(List.of("<" + names.fact(idOf((byte) 5)) + ">"),
                objects(quads, CONFLICT, "urn:utopia:ns:priorStatement"));
        assertEquals(List.of("<" + names.fact(idOf((byte) 6)) + ">"),
                objects(quads, CONFLICT, "urn:utopia:ns:incomingStatement"));
        assertEquals(List.of("\"simultaneous\""), objects(quads, CONFLICT, "urn:utopia:ns:reason"));
        assertEquals(List.of("\"open\""), objects(quads, CONFLICT, "urn:utopia:ns:status"));
        assertTrue(objects(quads, CONFLICT, "urn:utopia:ns:resolution").isEmpty());
        assertFalse(objects(quads, CONFLICT, "http://www.w3.org/ns/prov#generatedAtTime").isEmpty());
        assertTrue(objects(quads, CONFLICT, "urn:utopia:ns:closedAt").isEmpty());

        // 人裁过的带裁决词；作废的不是裁决——closedAt 记的是作废时刻（0051），不带 resolution
        for (Object[] row : new Object[][]{{"resolved", "kept_both"}, {"withdrawn", null}}) {
            String status = (String) row[0];
            String resolution = (String) row[1];
            List<Qd> qs = export(ExportFormat.TURTLE, (sink, ns, vocab) ->
                    sink.emitFactConflict(ns, conflict(status, resolution)));
            assertEquals(List.of("\"" + status + "\""), objects(qs, CONFLICT, "urn:utopia:ns:status"));
            assertEquals(resolution == null ? List.of() : List.of("\"" + resolution + "\""),
                    objects(qs, CONFLICT, "urn:utopia:ns:resolution"));
            assertFalse(objects(qs, CONFLICT, "urn:utopia:ns:closedAt").isEmpty());
        }
    }

    @Test
    void an_open_violation_points_at_its_criterion() {
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitAxiomViolation(names, vocab, violation("asymmetry", "open", null,
                        List.of())));
        assertTrue(has(quads, VIOLATION, "http://www.w3.org/1999/02/22-rdf-syntax-ns#type",
                "<urn:utopia:ns:AxiomViolation>"));
        assertEquals(List.of("\"asymmetry\""), objects(quads, VIOLATION, "urn:utopia:ns:kind"));
        assertEquals(2, objects(quads, VIOLATION, "urn:utopia:ns:onStatement").size(),
                "违规必须指回两条事实");
        // 判据 = OWL 词 + 住在哪条关系上（0062）
        assertEquals(List.of("<http://www.w3.org/2002/07/owl#AsymmetricProperty>"),
                objects(quads, VIOLATION, "urn:utopia:ns:criterion"));
        assertEquals(List.of("<" + WORKS_FOR + ">"),
                objects(quads, VIOLATION, "urn:utopia:ns:onRelation"));
        assertFalse(objects(quads, VIOLATION, "urn:utopia:ns:detectedAt").isEmpty());
        assertTrue(objects(quads, VIOLATION, "urn:utopia:ns:closedAt").isEmpty());
    }

    @Test
    void a_signature_violation_points_at_the_whole_signature() {
        // signature 的判据是 domain+range 这一对（0062）：行不记哪一侧破的，两个词都发
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitAxiomViolation(names, vocab, violation("signature", "open", null,
                        List.of())));
        List<String> criterion = new ArrayList<>(objects(quads, VIOLATION, "urn:utopia:ns:criterion"));
        java.util.Collections.sort(criterion);
        assertEquals(List.of("<http://www.w3.org/2000/01/rdf-schema#domain>",
                "<http://www.w3.org/2000/01/rdf-schema#range>"), criterion);
        assertEquals(List.of("<" + WORKS_FOR + ">"),
                objects(quads, VIOLATION, "urn:utopia:ns:onRelation"));
    }

    @Test
    void a_closed_violation_does_not_claim_a_criterion() {
        // resolved 的行不连判据：促成它结案的本体可能早已不在（0062）
        List<Qd> quads = export(ExportFormat.TURTLE, (sink, names, vocab) ->
                sink.emitAxiomViolation(names, vocab,
                        violation("signature", "resolved", "criterion_changed", List.of())));
        assertTrue(objects(quads, VIOLATION, "urn:utopia:ns:criterion").isEmpty());
        assertTrue(objects(quads, VIOLATION, "urn:utopia:ns:onRelation").isEmpty());
        assertEquals(List.of("\"criterion_changed\""),
                objects(quads, VIOLATION, "urn:utopia:ns:resolution"));
        assertFalse(objects(quads, VIOLATION, "urn:utopia:ns:closedAt").isEmpty());
    }

    /** 顺着 rdf:List 走一遍，把成员按序收回来。 */
    static List<String> listItems(List<Qd> quads, String violationIri) {
        String head = null;
        for (Qd q : quads) {
            if (q.s().equals(violationIri) && q.p().equals("urn:utopia:ns:evidencePath")) {
                head = strip(q.o());
                break;
            }
        }
        assertNotNull(head, "环必须带着 path");
        List<String> items = new ArrayList<>();
        String node = head;
        while (!RDF_NIL.equals(node)) {
            String first = null;
            String rest = null;
            for (Qd q : quads) {
                if (q.s().equals(node) && q.p().equals(RDF_FIRST) && first == null) {
                    first = q.o();
                }
                if (q.s().equals(node) && q.p().equals(RDF_REST) && rest == null) {
                    rest = strip(q.o());
                }
            }
            assertNotNull(first, "list 节点必有 first");
            items.add(first);
            node = rest;
            assertNotNull(node, "list 节点必有 rest");
        }
        return items;
    }

    @Test
    void a_cycle_keeps_its_facts_in_order() {
        List<UUID> path = List.of(idOf((byte) 5), idOf((byte) 9), idOf((byte) 6));
        ExportAxiomViolation v = violation("cycle", "open", null, path);
        Names names = namesLocal();
        List<String> expected = path.stream().map(f -> "<" + names.fact(f) + ">").toList();
        for (ExportFormat format : ExportFormat.values()) {
            List<Qd> quads = export(format, (sink, ns, vocab) ->
                    sink.emitAxiomViolation(ns, vocab, v));
            assertEquals(expected, listItems(quads, VIOLATION), format + " 里环的顺序丢了");
        }
    }

    @Test
    void contest_and_finding_survive_both_formats() {
        List<Qd> ttl = export(ExportFormat.TURTLE, (sink, names, vocab) -> {
            sink.emitFactConflict(names, conflict("resolved", "rejected_new"));
            sink.emitAxiomViolation(names, vocab,
                    violation("asymmetry", "open", null, List.of(idOf((byte) 5), idOf((byte) 9))));
        });
        List<Qd> jsonld = export(ExportFormat.JSONLD, (sink, names, vocab) -> {
            sink.emitFactConflict(names, conflict("resolved", "rejected_new"));
            sink.emitAxiomViolation(names, vocab,
                    violation("asymmetry", "open", null, List.of(idOf((byte) 5), idOf((byte) 9))));
        });
        assertEquals(ttl.size(), jsonld.size(), "两种格式是同一张图的两种写法，三元组数目必须一样");
        // 空白节点在两种写法里各自匿名，集合同比不了——比所有非匿名的角，
        // 环那份证据的顺序交给 a_cycle_keeps_its_facts_in_order 按格式各验一遍
        Set<String> namedTtl = named(ttl);
        Set<String> namedJsonld = named(jsonld);
        assertEquals(namedTtl, namedJsonld);
    }

    static Set<String> named(List<Qd> quads) {
        Set<String> out = new LinkedHashSet<>();
        for (Qd q : quads) {
            if (!q.s().startsWith("_:") && !q.o().startsWith("_:")) {
                out.add(q.s() + " " + q.p() + " " + q.o());
            }
        }
        return out;
    }
}
