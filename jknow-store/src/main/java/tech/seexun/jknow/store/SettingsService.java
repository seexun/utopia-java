package tech.seexun.jknow.store;

import java.util.UUID;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.LlmSettingsRow;
import tech.seexun.jknow.store.mapper.SettingsMapper;
import tech.seexun.jknow.store.mapper.WorkspaceMapper;
import tech.seexun.jknow.store.secrets.SecretsBox;

/**
 * 工作区 LLM 设置读写服务（逐字对齐旧仓库 crates/utopia-store/src/settings.rs:1-159）：
 * <b>入库即封印、出库即开封</b>——四把 api_key 在库里是 AES-GCM 密文，任何返回
 * {@link LlmSettingsRow} 的路径都从 {@link #opened} 过（settings.rs:8-15）。
 * api_key 传 null 表示保留旧值（SQL 的 COALESCE）；空串在路由层已折成 null。
 */
@Service
public class SettingsService {

    private final SettingsMapper settings;
    private final WorkspaceMapper workspaces;
    private final SecretsBox box;

    public SettingsService(SettingsMapper settings, WorkspaceMapper workspaces, SecretsBox box) {
        this.settings = settings;
        this.workspaces = workspaces;
        this.box = box;
    }

    /** 出库即开封（settings.rs:8-15 opened）：封印串解回明文；解不开按内部错误冒出。 */
    private LlmSettingsRow opened(LlmSettingsRow row) {
        if (row == null) {
            return null;
        }
        return new LlmSettingsRow(
                row.workspaceId(),
                row.chatBaseUrl(),
                box.openOpt(row.chatApiKey()),
                row.chatModel(),
                row.embedBaseUrl(),
                box.openOpt(row.embedApiKey()),
                row.embedModel(),
                row.embedDim(),
                row.updatedAt(),
                row.ocrBaseUrl(),
                box.openOpt(row.ocrApiKey()),
                row.ocrBackend(),
                row.ocrProvider(),
                row.ocrModel(),
                row.transcribeBaseUrl(),
                box.openOpt(row.transcribeApiKey()),
                row.transcribeModel(),
                row.chatReasoningEffort());
    }

    /** settings.rs:17-24 get：无行 null。 */
    public LlmSettingsRow get(UUID workspaceId) {
        return opened(workspaces.selectSettings(workspaceId));
    }

    /** settings.rs:28-69 upsert：密钥先封印，null 仍是 null（保留旧值）。 */
    public LlmSettingsRow upsert(UUID workspaceId, String chatBaseUrl, String chatApiKey,
                                 String chatModel, String embedBaseUrl, String embedApiKey,
                                 String embedModel, Integer embedDim) {
        return opened(settings.upsert(workspaceId, chatBaseUrl, box.sealOpt(chatApiKey), chatModel,
                embedBaseUrl, box.sealOpt(embedApiKey), embedModel, embedDim));
    }

    /**
     * settings.rs:73-86 set_chat_reasoning_effort：单独改（跟着对话模型那张卡走，
     * upsert 的整体替换不认识它）；null = 清空（回到端点默认）。
     */
    public void setChatReasoningEffort(UUID workspaceId, String effort) {
        settings.setChatReasoningEffort(workspaceId, effort);
    }

    /** settings.rs:95-130 upsert_ocr（换供应商不保留旧密钥，见 mapper 注释）。 */
    public LlmSettingsRow upsertOcr(UUID workspaceId, String provider, String baseUrl,
                                    String apiKey, String backend, String model) {
        return opened(settings.upsertOcr(workspaceId, provider, baseUrl, box.sealOpt(apiKey),
                backend, model));
    }

    /** settings.rs:133-159 upsert_transcribe。 */
    public LlmSettingsRow upsertTranscribe(UUID workspaceId, String baseUrl, String apiKey,
                                           String model) {
        return opened(settings.upsertTranscribe(workspaceId, baseUrl, box.sealOpt(apiKey), model));
    }
}
