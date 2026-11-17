package tech.seexun.jknow.store.mapper;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 凭据补封 mapper（对齐旧仓库 crates/utopia-store/src/sealing.rs，SQL 见 resources/mapper/）。
 * 来源表整行读回、JSON 里判键在 Java 侧做（sealing.rs:81「凭据键藏在 JSON 里，SQL 判起来反而绕」）。
 */
@Mapper
public interface SealingMapper {

    /** sealing.rs:32-39：llm_settings 四把 key 任一非封印的行。 */
    List<LlmSecretRow> selectPlainLlmSettings(@Param("only") UUID only);

    /** sealing.rs:46-49：整行回写（Service 先 seal）。 */
    int updateLlmSettings(@Param("workspaceId") UUID workspaceId,
                          @Param("chatApiKey") String chatApiKey,
                          @Param("embedApiKey") String embedApiKey,
                          @Param("ocrApiKey") String ocrApiKey,
                          @Param("transcribeApiKey") String transcribeApiKey);

    /** sealing.rs:63-65：conn_string 非封印的行（id, conn_string）。 */
    List<ConnRow> selectPlainDataSources(@Param("only") UUID only);

    /** sealing.rs:72-74。 */
    int updateDataSourceConn(@Param("id") UUID id, @Param("connString") String connString);

    /** sealing.rs:84-86：来源表整张读回（id, config, ingest_token）。 */
    List<SourceRow> selectSources(@Param("only") UUID only);

    /** sealing.rs:103-107。 */
    int updateSourceSecrets(@Param("id") UUID id, @Param("config") JsonNode config,
                            @Param("ingestToken") String ingestToken);

    /** llm_settings 明文行（四把 key 可空）。 */
    record LlmSecretRow(UUID workspaceId, String chatApiKey, String embedApiKey,
                        String ocrApiKey, String transcribeApiKey) {
    }

    /** data_sources 明文连接串行。 */
    record ConnRow(UUID id, String connString) {
    }

    /** sources 行（config JSON 原样带回，凭据键判封印在 Java 侧）。 */
    record SourceRow(UUID id, JsonNode config, String ingestToken) {
    }
}
