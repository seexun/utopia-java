package tech.seexun.jknow.reason;

import java.util.UUID;

/**
 * 一条参与检查的事实：谁、什么关系、指向谁（lib.rs:26-32）。
 *
 * @param fact      事实 id
 * @param predicate 谓词 id
 * @param subject   主语实体 id
 * @param object    宾语实体 id
 */
public record Edge(UUID fact, UUID predicate, UUID subject, UUID object) {
}
