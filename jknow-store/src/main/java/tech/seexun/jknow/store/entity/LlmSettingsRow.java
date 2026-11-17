package tech.seexun.jknow.store.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * llm_settings 表行（models.rs:450-489 LlmSettings 的 FromRow 列集，SELECT *）。
 *
 * <p>**凭据封印纪律（a_viewer_never_sees_a_credential 同语义）**：四把 api_key 在库里是
 * AES-256-GCM 封印形态（utopia-core/src/secrets.rs:5-17，{@code enc:v1:} 前缀）。本行原样携带
 * 封印串，**任何读面都不得解密**——settings-get 只把「列非空」折成 has_*_key 布尔回显
 * （settings_routes.rs:28/32/37/40：seal 不产生空串，封印列非空 ⟺ 解密后非空）。
 *
 * <p>四把钥匙加 {@code @JsonIgnore}（对齐 Rust 侧 {@code #[serde(skip_serializing)]} 的类型级防线，
 * models.rs:453/457/464/474）：行对象一旦被 valueToTree/直接序列化，密文不带出去。威胁有限
 * （现有读面手工折布尔回显，不序列化本行），此为层级拉平的顺手防线（走查1）。
 */
public record LlmSettingsRow(
        UUID workspaceId,
        String chatBaseUrl,
        @JsonIgnore String chatApiKey,
        String chatModel,
        String embedBaseUrl,
        @JsonIgnore String embedApiKey,
        String embedModel,
        Integer embedDim,
        OffsetDateTime updatedAt,
        String ocrBaseUrl,
        @JsonIgnore String ocrApiKey,
        String ocrBackend,
        String ocrProvider,
        String ocrModel,
        String transcribeBaseUrl,
        @JsonIgnore String transcribeApiKey,
        String transcribeModel,
        String chatReasoningEffort) {

    /** models.rs:491-493 chat_ready。 */
    public boolean chatReady() {
        return chatBaseUrl != null && chatModel != null;
    }

    /** models.rs:487-489 embed_ready（管线 embedding 步「配了才做」的判定）。 */
    public boolean embedReady() {
        return embedBaseUrl != null && embedModel != null;
    }

    /**
     * models.rs:490-503 ocr_ready：MinerU 有地址就够；方舟还要模型名和密钥（收费的模型接口）。
     * 判定在<b>开封后</b>的行上做（settings_routes.rs:178 saved.ocr_ready()）。
     */
    public boolean ocrReady() {
        if (ocrBaseUrl == null) {
            return false;
        }
        // 行上 provider 经 upsert_ocr 的 COALESCE($2,'mineru') 恒非空；防御 null 按 mineru
        String provider = ocrProvider == null ? "mineru" : ocrProvider;
        return switch (provider) {
            case "mineru" -> true;
            case "ark" -> ocrModel != null && !ocrModel.trim().isEmpty()
                    && ocrApiKey != null && !ocrApiKey.isEmpty();
            default -> false;
        };
    }

    /** models.rs:504-506 transcribe_ready。 */
    public boolean transcribeReady() {
        return transcribeBaseUrl != null && transcribeModel != null;
    }
}
