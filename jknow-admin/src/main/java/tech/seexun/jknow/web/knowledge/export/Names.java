package tech.seexun.jknow.web.knowledge.export;

import java.util.UUID;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.ExportStore.ExportClass;
import tech.seexun.jknow.store.ExportStore.ExportRelation;

/**
 * 这份导出里的 IRI 怎么造（rdf.rs:56-132 Names 逐字）。
 *
 * <p>缺省是 URN：{@code urn:utopia:kb:{kb}:entity:{uuid}}。<b>稳定优先于可解引用</b>——
 * 同一个库隔一年再导一次，两份文件必须对得上；而一个自部署的实例并不知道自己对外的
 * 地址是什么。按请求的 Host 头去造，等于让身份取决于走了哪个反代。部署方知道自己发布
 * 在哪时，{@code ?base=https://…/} 换成 http IRI。
 */
public final class Names {

    private final String prefix;
    private final char sep;

    private Names(String prefix, char sep) {
        this.prefix = prefix;
        this.sep = sep;
    }

    /**
     * base 给了必须是 http(s) 且不含 IRI 里不可能出现的字符（非法字符集逐字：
     * {@code < > " { } | \ ^ ` } 与空格）——拼出来的会是一份谁也解析不了的文件。
     * 校验失败抛 422（对齐 Rust 侧 {@code AppError::Validation} 文案）。
     */
    public static Names of(UUID kbId, String base) {
        String trimmedBase = base == null ? null : base.trim();
        if (trimmedBase != null && !trimmedBase.isEmpty()) {
            if (!(trimmedBase.startsWith("http://") || trimmedBase.startsWith("https://"))) {
                throw AppException.validation("`base` must be an http(s) IRI");
            }
            for (char bad : new char[]{'<', '>', '"', '{', '}', '|', '\\', '^', '`', ' '}) {
                if (trimmedBase.indexOf(bad) >= 0) {
                    throw AppException.validation(
                            "`base` contains characters that cannot appear in an IRI");
                }
            }
            String trimmed = trimEndMatches(trimmedBase, '/');
            return new Names(trimmed + "/kb/" + kbId + "/", '/');
        }
        return new Names("urn:utopia:kb:" + kbId + ":", ':');
    }

    private static String trimEndMatches(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) {
            end--;
        }
        return s.substring(0, end);
    }

    private String mint(String kind, String id) {
        return prefix + kind + sep + id;
    }

    public String entity(UUID id) {
        return mint("entity", id.toString());
    }

    public String fact(UUID id) {
        return mint("fact", id.toString());
    }

    public String derived(UUID id) {
        return mint("derived", id.toString());
    }

    public String document(UUID id) {
        return mint("document", id.toString());
    }

    public String rule(UUID id) {
        return mint("rule", id.toString());
    }

    /** 事实争议（0062）：{@code …:conflict:{uuid}}。 */
    public String factConflict(UUID id) {
        return mint("conflict", id.toString());
    }

    /** 公理违规发现（0062）：{@code …:violation:{uuid}}。 */
    public String axiomViolation(UUID id) {
        return mint("violation", id.toString());
    }

    /**
     * 本体自己长出来的类/关系用 <b>key</b> 而不是 uuid：key 是这个库内部就在用的标识
     * （UNIQUE (kb_id, key)，抽取提示词和 API 用的都是它），文件因此读得懂。
     * 导入来的一律用原 IRI；原 IRI 非法时回落 mint。
     */
    public String cls(ExportClass c) {
        return c.iri() != null && iriValid(c.iri()) ? c.iri() : mint("class", c.key());
    }

    public String relation(ExportRelation r) {
        return r.iri() != null && iriValid(r.iri()) ? r.iri() : mint("relation", r.key());
    }

    /** Rust NamedNode::new 的合法性判定对等：Jena 的 createURI 对非法 IRI 抛异常——回落 mint。 */
    private static boolean iriValid(String iri) {
        try {
            org.apache.jena.rdf.model.ResourceFactory.createResource(iri);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
