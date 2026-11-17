package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * 事实的证据行（对齐 Rust utopia-core models::EvidenceView，models.rs:1004-1026 serde 形状：
 * proposed_predicate/quote/chunk_id/document_id/filename/seq/doc_version/stale/
 * document_deleted/origin/origin_model/anchor——键序由规范器排序，字段集一致即可）。
 */
public record EvidenceRow(
        String proposedPredicate,
        String quote,
        UUID chunkId,
        UUID documentId,
        String filename,
        int seq,
        int docVersion,
        boolean stale,
        boolean documentDeleted,
        String origin,
        String originModel,
        JsonNode anchor) {
}
