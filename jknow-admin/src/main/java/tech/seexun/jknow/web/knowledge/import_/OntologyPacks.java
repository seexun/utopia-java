package tech.seexun.jknow.web.knowledge.import_;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 预置本体包：建库时的起点（server/ontology_packs.rs 逐语义）。
 *
 * <p>建库时<b>什么都不铺</b>：0009 删掉内置实体类、0010 与 #125 删掉种子关系、0011 把
 * {@code mapped_to} 搬去语义层之后，播种机制整个退场。所以这些包不是「补充」而是本体的
 * <b>全部来源</b>。
 *
 * <p>Rust 把原文 gzip 内嵌进二进制（README 承诺整套系统可以跑在完全离线的内网环境，
 * 运行时抓取会让这句话失效）；Java 侧对等载体是 classpath 资源
 * （{@code resources/packs/*.gz}，与 {@code resources/docs} 产品手册语料同一先例）。
 * 解压在建库时现做——低频动作，不值得为它常驻 1.7 MB。
 */
public final class OntologyPacks {

    private OntologyPacks() {
    }

    /** 一个可选的预置本体（Pack）。{@code classes}/{@code properties} 是抓取当天数过的展示数字。 */
    public record Pack(String id, String name, String summary, String filename, int classes,
                       int properties, String resource) {
    }

    public static final List<Pack> PACKS = List.of(
            new Pack("schema-org", "schema.org",
                    "People, organizations, products, events, creative works",
                    "schema-org.ttl", 1010, 1676, "packs/schema-org.ttl.gz"),
            new Pack("w3c-org", "W3C Org",
                    "Departments, posts, memberships, reporting lines",
                    "w3c-org.ttl", 13, 34, "packs/w3c-org.ttl.gz"),
            new Pack("prov-o", "PROV-O",
                    "Provenance: who produced what, when, from which source",
                    "prov-o.ttl", 49, 69, "packs/prov-o.ttl.gz"),
            new Pack("foaf", "FOAF", "People and social relations",
                    "foaf.rdf", 12, 62, "packs/foaf.rdf.gz"),
            new Pack("iof-core", "IOF Core", "Industrial manufacturing",
                    "iof-core.rdf", 294, 75, "packs/iof-core.rdf.gz"));

    public static Pack get(String id) {
        for (Pack p : PACKS) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 解压出原文。每次调用都解一遍——建库是低频动作（Rust 同款取舍）。 */
    public static byte[] bytes(Pack pack) {
        try (InputStream in = OntologyPacks.class.getClassLoader()
                .getResourceAsStream(pack.resource());
                GZIPInputStream gz = in == null ? null : new GZIPInputStream(in);
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (gz == null) {
                throw new IOException("本体包资源缺失: " + pack.resource());
            }
            gz.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("本体包 " + pack.id() + " 解压失败: " + e.getMessage(),
                    e);
        }
    }
}
