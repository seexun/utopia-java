package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** 文档查看器分块视图（models.rs:1075-1084 ChunkFull；chunks_full 1548-1560）。 */
public record ChunkFull(
        UUID id,
        int seq,
        String text,
        String origin,
        String originModel,
        JsonNode anchor) {
}
