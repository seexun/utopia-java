package tech.seexun.jknow.store.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 账户层「我的知识库」行信息（models.rs:408-417 MyKbInfo；成员行可空——open 库凭部署身份进入）。 */
public record MyKbInfo(
        UUID kbId,
        String memberRole,
        OffsetDateTime joinedAt,
        String addedByName,
        long docCount,
        long memberCount) {
}
