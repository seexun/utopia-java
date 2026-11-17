package tech.seexun.jknow.store.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.NameView;

/**
 * 名字域 SQL（names.rs 逐字搬迁）：known_as 属性取/建、实体名字栏（两形态）、
 * 别名撞车排队候选 + 裁决 upsert。每条 XML 内注明 file:line。
 */
public interface NamesMapper {

    /** names.rs:28-31 取 known_as 属性 id（没有返回 null）。 */
    UUID selectKnownAs(@Param("kbId") UUID kbId, @Param("key") String key);

    /** names.rs:37-45 建 known_as（builtin=TRUE，建库时不铺，第一次要时建）。 */
    int insertKnownAs(@Param("id") UUID id, @Param("kbId") UUID kbId, @Param("key") String key,
                      @Param("description") String description);

    /** names.rs:150-172 for_entity（现行形态）。 */
    List<NameView> selectNamesForEntity(@Param("kbId") UUID kbId,
                                        @Param("entityId") UUID entityId);

    /** names.rs:150-172 for_entity 的 as_of 回放形态（owner_at/facts_held_at 各带 $3）。 */
    List<NameView> selectNamesForEntityAsOf(@Param("kbId") UUID kbId,
                                            @Param("entityId") UUID entityId,
                                            @Param("asOf") OffsetDateTime asOf);

    /** names.rs:193-208 pair_shared_name 的候选（LIMIT 4）。 */
    List<UUID> selectSharedNameOthers(@Param("kbId") UUID kbId,
                                      @Param("entityId") UUID entityId,
                                      @Param("name") String name,
                                      @Param("knownAs") String knownAs);

    /**
     * 别名撞车排队的裁决 upsert 不在这里——resolution.rs:1393-1420 create_review 已随
     * 消解全族统一落 ResolutionMapper.insertResolutionReview（M3 任务 4），pairSharedName
     * 直取那一条，两处口径不分叉。
     */
}
