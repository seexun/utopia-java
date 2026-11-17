package tech.seexun.jknow.store.entity;

/**
 * 工具行（governance.rs:828-834 Namesake）：库里叫这个名字的实体——它是什么、有多少
 * 事实、是不是已经并进了别的实体。
 */
public record Namesake(String name, String typeLabel, long facts, boolean merged) {
}
