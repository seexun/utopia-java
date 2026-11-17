package tech.seexun.jknow.store.mapper;

import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import tech.seexun.jknow.store.entity.LlmSettingsRow;

/**
 * 工作区 LLM 设置写 mapper（对齐旧仓库 crates/utopia-store/src/settings.rs:28-159，
 * SQL 见 resources/mapper/）。读路径（get）在 WorkspaceMapper.selectSettings；
 * 密钥的封印/开封在 SettingsService（入库即封印、出库即开封）。
 */
@Mapper
public interface SettingsMapper {

    /** settings.rs:42-68 upsert：api_key 传 null 表示保留旧值（前端不回传密钥）。 */
    LlmSettingsRow upsert(@Param("workspaceId") UUID workspaceId,
                          @Param("chatBaseUrl") String chatBaseUrl,
                          @Param("chatApiKey") String chatApiKey,
                          @Param("chatModel") String chatModel,
                          @Param("embedBaseUrl") String embedBaseUrl,
                          @Param("embedApiKey") String embedApiKey,
                          @Param("embedModel") String embedModel,
                          @Param("embedDim") Integer embedDim);

    /** settings.rs:73-86 set_chat_reasoning_effort：单独改；null = 清空（回端点默认）。 */
    int setChatReasoningEffort(@Param("workspaceId") UUID workspaceId,
                               @Param("effort") String effort);

    /**
     * settings.rs:95-130 upsert_ocr：换供应商（provider 异于现值）时旧密钥<b>不保留</b>
     * ——留空保留密钥是给「改地址、换后端」用的；比较和写在同一条 SQL，并发保存也带不过去。
     */
    LlmSettingsRow upsertOcr(@Param("workspaceId") UUID workspaceId,
                             @Param("provider") String provider,
                             @Param("baseUrl") String baseUrl,
                             @Param("apiKey") String apiKey,
                             @Param("backend") String backend,
                             @Param("model") String model);

    /** settings.rs:133-159 upsert_transcribe：跟版面识别服务一样单独一张卡。 */
    LlmSettingsRow upsertTranscribe(@Param("workspaceId") UUID workspaceId,
                                    @Param("baseUrl") String baseUrl,
                                    @Param("apiKey") String apiKey,
                                    @Param("model") String model);
}
