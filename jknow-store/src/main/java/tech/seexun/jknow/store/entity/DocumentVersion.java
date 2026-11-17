package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;

/** 摄入台账的一个保留版本（documents.rs:549-555 DocumentVersion；versions 561-569）。 */
public record DocumentVersion(
        int version,
        String sha256,
        long sizeBytes,
        OffsetDateTime ingestedAt) {
}
