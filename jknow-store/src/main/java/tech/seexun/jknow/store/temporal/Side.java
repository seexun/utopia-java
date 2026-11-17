package tech.seexun.jknow.store.temporal;

/**
 * temporal.rs:51-66 {@code Uniqueness}：唯一性方向。functional = 主语侧
 * （张三同时只 reports_to 一人）；inverse functional = 宾语侧（一个项目同时只有一个
 * leads 它的人）。
 */
public enum Side {
    SUBJECT_SIDE,
    OBJECT_SIDE;

    /** temporal.rs:59-65 {@code other}：另一侧。双唯一的关系一行同时在两条时间线上。 */
    public Side other() {
        return this == SUBJECT_SIDE ? OBJECT_SIDE : SUBJECT_SIDE;
    }

    /** temporal.rs:79-88 lock_key 里 Rust {@code {:?}} 的 Debug 输出 = 变体名，逐字保持。 */
    String debugName() {
        return this == SUBJECT_SIDE ? "SubjectSide" : "ObjectSide";
    }
}
