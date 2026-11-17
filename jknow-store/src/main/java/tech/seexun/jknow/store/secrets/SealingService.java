package tech.seexun.jknow.store.secrets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.mapper.SealingMapper;

/**
 * 凭据补封（逐字对齐旧仓库 crates/utopia-store/src/sealing.rs:1-112 backfill）：
 * 升级前落库的明文凭据，在钥匙装好之后的第一次启动补成密文。
 *
 * <p>幂等，每次启动都跑：判据是「值没有 {@code enc:v1:} 前缀」。四处凭据——
 * {@code llm_settings} 四把 key、{@code data_sources.conn_string}、{@code sources.config}
 * 的凭据键、{@code sources.ingest_token}——各扫一遍。<b>加了新的存放处要同时加到这里</b>，
 * 否则那一处会一直是明文而没有任何地方报警。来源行整行读回在 Java 判键
 * （sealing.rs:81：凭据键藏在 JSON 里，SQL 判起来反而绕）。没装钥匙时什么都不做。
 */
@Service
public class SealingService {

    private static final Logger log = LoggerFactory.getLogger(SealingService.class);

    private final SealingMapper sealing;
    private final SecretsBox box;

    public SealingService(SealingMapper sealing, SecretsBox box) {
        this.sealing = sealing;
        this.box = box;
    }

    /** @return 补封的行数；没装钥匙时 0。 */
    public int backfill() {
        if (!box.isReady()) {
            return 0;
        }
        int n = sealLlmSettings(null) + sealDataSources(null) + sealSources(null);
        if (n > 0) {
            log.info("凭据补封 rows={}", n);
        }
        return n;
    }

    /** only = 只补这一个 id（测试用；启动时 null 扫全部）。 */
    public int sealLlmSettings(java.util.UUID only) {
        var rows = sealing.selectPlainLlmSettings(only);
        for (var row : rows) {
            sealing.updateLlmSettings(row.workspaceId(),
                    box.sealOpt(row.chatApiKey()),
                    box.sealOpt(row.embedApiKey()),
                    box.sealOpt(row.ocrApiKey()),
                    box.sealOpt(row.transcribeApiKey()));
        }
        return rows.size();
    }

    public int sealDataSources(java.util.UUID only) {
        var rows = sealing.selectPlainDataSources(only);
        for (var row : rows) {
            sealing.updateDataSourceConn(row.id(), box.seal(row.connString()));
        }
        return rows.size();
    }

    public int sealSources(java.util.UUID only) {
        var rows = sealing.selectSources(only);
        String[] keys = SourceSecretKeys.array();
        int n = 0;
        for (var row : rows) {
            var config = row.config();
            boolean plainKey = config != null && config.isObject() && java.util.Arrays.stream(keys)
                    .anyMatch(k -> config.has(k) && config.get(k).isTextual()
                            && !SecretsBox.isSealed(config.get(k).asText()));
            String token = row.ingestToken();
            boolean plainToken = token != null && !SecretsBox.isSealed(token);
            if (!plainKey && !plainToken) {
                continue;
            }
            box.sealJsonKeys(config, keys);
            sealing.updateSourceSecrets(row.id(), config, box.sealOpt(token));
            n++;
        }
        return n;
    }
}
