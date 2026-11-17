package tech.seexun.jknow.web.knowledge.import_;

/**
 * 包与包之间的同名词处置（server/pack_alignment.rs 逐字）。导入撞 key 时
 * {@link ImportPlanner} 的默认判断是 {@code KeyTaken}——跳过并报告，不自动加后缀。
 * 那条理由（「重导入认不出自己上次建的是哪个」）针对的是<b>猜</b>出来的后缀；本表给的是
 * <b>声明</b>的处置，重导入结果一样，所以不受那条约束。
 *
 * <p>只覆盖预制包。用户手动导入的词汇表不在这里——那是明确的意图，撞名该报给他看。
 */
public final class PackAlignment {

    private PackAlignment() {
    }

    /** 同名词的处置。 */
    public enum Alignment {
        /** 同义：已有的那个就是它，不必再建。跳过，但记作「已对齐」而非「冲突」。 */
        SAME_AS,
        /** 同名不同义：改用声明的 key 建出来。 */
        RENAME
    }

    /** 表的一行（包内可见——守卫测试逐条核对 key 合法性与重复）。 */
    record Entry(String incoming, String existing, Alignment alignment, String renameKey) {
    }

    /**
     * (进来的 IRI, 已占位的 IRI) → 处置。表里写的是完整 IRI——那是要与投影结果逐字符
     * 比对的东西，拼接会掩盖笔误。
     */
    private static final Entry[] TABLE = {
            // ── W3C Org × schema.org ──────────────────────────────────────────
            new Entry("http://www.w3.org/ns/org#Organization",
                    "https://schema.org/Organization", Alignment.SAME_AS, null),
            new Entry("http://www.w3.org/ns/org#identifier",
                    "https://schema.org/identifier", Alignment.SAME_AS, null),
            new Entry("http://www.w3.org/ns/org#location",
                    "https://schema.org/location", Alignment.SAME_AS, null),
            // org:Role 是「职位所承载的角色」（与 Post、Membership 配套），schema:role
            // 是创作作品里的饰演关系。同名，无关
            new Entry("http://www.w3.org/ns/org#Role",
                    "https://schema.org/Role", Alignment.RENAME, "org_role"),
            // org:member 是带任期的成员关系（经由 Membership 具体化），schema:member
            // 是泛指的从属。粒度不同，两个都要
            new Entry("http://www.w3.org/ns/org#member",
                    "https://schema.org/member", Alignment.RENAME, "org_member"),
            new Entry("http://www.w3.org/ns/org#memberOf",
                    "https://schema.org/memberOf", Alignment.RENAME, "org_member_of"),
            // ── PROV-O × schema.org ───────────────────────────────────────────
            // prov:Agent 是 Person 与 Organization 的超类，不是同义词。判成 SameAs
            // 会把「施事者」这个抽象层整个抹掉
            new Entry("http://www.w3.org/ns/prov#Agent",
                    "https://schema.org/agent", Alignment.RENAME, "prov_agent"),
            // ── FOAF × schema.org ─────────────────────────────────────────────
            new Entry("http://xmlns.com/foaf/0.1/Person",
                    "https://schema.org/Person", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/Organization",
                    "https://schema.org/Organization", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/Project",
                    "https://schema.org/Project", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/name",
                    "https://schema.org/name", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/knows",
                    "https://schema.org/knows", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/givenName",
                    "https://schema.org/givenName", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/familyName",
                    "https://schema.org/familyName", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/gender",
                    "https://schema.org/gender", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/logo",
                    "https://schema.org/logo", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/thumbnail",
                    "https://schema.org/thumbnail", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/title",
                    "https://schema.org/title", Alignment.SAME_AS, null),
            new Entry("http://xmlns.com/foaf/0.1/member",
                    "https://schema.org/member", Alignment.SAME_AS, null),
            // foaf:Agent 与 prov:Agent 同义（PROV-O 官方就是这么对齐的），
            // 但都不等于 schema:agent
            new Entry("http://xmlns.com/foaf/0.1/Agent",
                    "https://schema.org/agent", Alignment.RENAME, "foaf_agent"),
            // foaf:status 是即时通讯时代的在线状态，schema:status 是订单/动作状态
            new Entry("http://xmlns.com/foaf/0.1/status",
                    "https://schema.org/status", Alignment.RENAME, "foaf_status"),
    };

    /** 守卫测试用（key 合法性/重复/对真包存在性逐条核对）。 */
    static Entry[] tableEntries() {
        return TABLE;
    }

    /** 撞名时查这张表。两个 IRI 都不在预制包里就返回 {@code null}，走原来的 KeyTaken。 */
    public static Result lookup(String incomingIri, String existingIri) {
        for (Entry e : TABLE) {
            if (e.incoming().equals(incomingIri) && e.existing().equals(existingIri)) {
                return new Result(e.alignment(), e.renameKey());
            }
        }
        return null;
    }

    /** lookup 的返回：处置 + Rename 时的声明 key。 */
    public record Result(Alignment alignment, String renameKey) {
    }
}
