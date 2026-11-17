package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 公理违规卡片（models.rs:1251-1281 AxiomViolation serde 形状）。SQL 侧（reasoning.rs:795-855）
 * 取 ViolationRow（含 jsonb path / left_open / same_name_peers 等线索列），服务层再算
 * hint（0017 §2：stale/duplicate/unsure 一次只给一条）并展开 path 文本——对应 Rust
 * open_violations 尾部的 map。
 */
public record AxiomViolation(
        UUID id,
        String kind,
        String predicate,
        UUID leftFact,
        String leftText,
        UUID rightFact,
        String rightText,
        int pathLen,
        OffsetDateTime detectedAt,
        JsonNode detail,
        String hint,
        List<ViolationFact> path) {

    /** 环上的一条事实：id 与三元组文本（#202：撤事实要说撤哪条）。 */
    public record ViolationFact(UUID id, String text) {
    }
}
