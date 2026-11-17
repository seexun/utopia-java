package tech.seexun.jknow.reason;

import java.util.UUID;

/**
 * 一个谓词声明了哪些公理（lib.rs:36-48）。四位都为假的谓词根本不进检查。
 *
 * @param transitive       传递
 * @param symmetric        对称
 * @param asymmetric       反对称
 * @param irreflexive      非自反
 * @param functional       函数性（主语侧唯一）
 * @param inverseFunctional 反函数性（宾语侧唯一）
 * @param inverseOf        {@code p⁻¹ = q}：这个谓词的逆是哪一个。<b>跨谓词的规则从这里来</b>——
 *                        {@code A p B} 推出 {@code B q A}，而 R0 的检查也要看它（自己是自己的逆等于对称）
 * @param subPropertyOf    {@code p ⊑ q}：断言了具体的，通用的也成立。链要防成环，R0 那边查
 */
public record Axioms(boolean transitive, boolean symmetric, boolean asymmetric, boolean irreflexive,
                     boolean functional, boolean inverseFunctional, UUID inverseOf,
                     UUID subPropertyOf) {

    public static Axioms empty() {
        return new Axioms(false, false, false, false, false, false, null, null);
    }

    /**
     * 一位都没声明的谓词不必检查（lib.rs:53-55）——<b>这是性能上的事，也是语义上的事</b>：
     * 没有公理就没有判据，扫它只会白扫。对一个没装本体包的库，检查跑出来是零——
     * 那是实情，不是故障。
     */
    public boolean saysNothing() {
        return !transitive && !symmetric && !asymmetric && !irreflexive && !functional
                && !inverseFunctional && inverseOf == null && subPropertyOf == null;
    }
}
