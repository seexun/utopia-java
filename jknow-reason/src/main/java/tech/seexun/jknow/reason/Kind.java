package tech.seexun.jknow.reason;

/**
 * 查出来的违规种类（lib.rs:76-102）。前四类由纯逻辑引擎算出；后两类是库里的东西，
 * 列在这里是为了与其它四类走同一条落库、清陈、裁决的路。
 */
public enum Kind {
    /** {@code A p A}，而 p 声明了 irreflexive。 */
    SELF_LOOP("self_loop"),
    /** {@code A p B} 且 {@code B p A}，而 p 声明了 asymmetric。 */
    ASYMMETRY("asymmetry"),
    /** {@code A p B p … p A}，而 p 声明了 transitive——闭包里推出 {@code A p A}。 */
    CYCLE("cycle"),
    /** 同一主语与谓词在同一段时间里指向两个宾语，而 p 声明了 functional。 */
    FUNCTIONAL("functional"),
    /**
     * 同一宾语在同一段时间里被两个主语指，而 p 声明了 inverse_functional。
     *
     * <p>与 {@link #FUNCTIONAL} 分开记（#634）：从前两个方向共用一个种类，一个项目同时有两位
     * 负责人被写成「该只有一个值，却有两个」，人照着去主语那一侧找，找的是错的一端。
     */
    INVERSE_FUNCTIONAL("inverse_functional"),
    /**
     * {@code A p B}，而 A 不在 p 声明的 domain 里、或 B 不在 range 里（#190 / #196）。
     *
     * <p><b>这一类不由本模块算出</b>：它要看实体的类型与 domain / range 的闭包，那是
     * 库里的东西，store 侧 reasoning 的 signature_breaks 用 SQL 量。列在这里
     * 是为了与其它四类走同一条落库、清陈、裁决的路——left 与 right 同一条事实，
     * 与自反那类同款。
     */
    SIGNATURE("signature"),
    /**
     * 一条派生撞上了一条断言（0017）：推出来的 {@code A p B} 与账本里的某条断言在 p 的
     * 公理上不能并存。派生不落地，这一行把它摆到人面前。{@code left} 是被撞的断言，
     * {@code right} 是派生的最后一条前提，{@code path} 是全部前提；推出来的三元组本身在
     * {@code axiom_violations.detail} 里——它没有落库，没有 id 可指。
     */
    DERIVED_CONTRADICTION("derived_contradiction");

    private final String asStr;

    Kind(String asStr) {
        this.asStr = asStr;
    }

    /** lib.rs:105-115 as_str：直接进 SQL 也直接做落库行键的字面量。 */
    public String asString() {
        return asStr;
    }
}
