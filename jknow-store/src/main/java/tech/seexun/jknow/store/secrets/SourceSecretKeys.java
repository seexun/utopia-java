package tech.seexun.jknow.store.secrets;

import java.util.List;

/**
 * 来源配置里的凭据键表（对齐旧仓库 crates/utopia-core/src/models.rs:229-236
 * SOURCE_SECRET_KEYS 逐字）：来源 config 封印（seal_json_keys）、启动补封（backfill）、
 * 列表读面剔除（{@code config - $2::text[]}）三处共用同一张表——加了新键要同时改这里。
 */
public final class SourceSecretKeys {

    public static final List<String> KEYS = List.of(
            "auth_header",
            "token",
            "password",
            "secret_access_key",
            "account_key",
            "service_account_key");

    private SourceSecretKeys() {
    }

    public static String[] array() {
        return KEYS.toArray(new String[0]);
    }
}
