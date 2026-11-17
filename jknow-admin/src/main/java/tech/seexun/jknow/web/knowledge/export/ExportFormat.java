package tech.seexun.jknow.web.knowledge.export;

/**
 * 两种导出格式（rdf.rs:205-242 Format 逐字）：同一套三元组，只是落笔不同。
 * JSON-LD 走按主语分组的流式写法（对齐 oxrdfio JsonLdProfile::Streaming——
 * 导出是本系统里唯一一处「一次输出可能比库还大」的地方）。
 */
public enum ExportFormat {

    TURTLE,
    JSONLD;

    /** format 参数解析：缺省 turtle；认不得返回 null（路由侧 422）。 */
    public static ExportFormat parse(String raw) {
        String s = (raw == null ? "turtle" : raw).trim().toLowerCase();
        return switch (s) {
            case "turtle", "ttl" -> TURTLE;
            case "jsonld", "json-ld", "json" -> JSONLD;
            default -> null;
        };
    }

    public String contentType() {
        return this == TURTLE ? "text/turtle; charset=utf-8" : "application/ld+json";
    }

    public String extension() {
        return this == TURTLE ? "ttl" : "jsonld";
    }
}
