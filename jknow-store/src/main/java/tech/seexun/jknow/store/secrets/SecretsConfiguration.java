package tech.seexun.jknow.store.secrets;

import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 封印钥匙装配（对齐旧仓库 main.rs:123-132 的启动序）：
 * <b>环境变量 JKNOW_SECRET_KEY（32B hex/base64）优先 → {data_dir}/secret.key
 * （首启生成；读不出启动失败）</b>。钥匙不进库——库泄漏 ≠ 凭据泄漏是这一层的全部意义。
 *
 * <p>data_dir 经 jknow.data-dir 配置（application.yml 映射 JKNOW_DATA_DIR），
 * dev 双打形态指向 Rust 参照实例的数据目录（共享 secret.key ⇒ 双实现互通开封）。
 * 空串按未设置处理（compose {@code ${JKNOW_SECRET_KEY:-}} 的空值形态，Rust 同判）。
 */
@Configuration
public class SecretsConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SecretsConfiguration.class);

    @Bean
    public SecretsBox secretsBox(@Value("${JKNOW_SECRET_KEY:}") String envKey,
                                 @Value("${jknow.data-dir:data}") String dataDir) {
        String trimmed = envKey == null ? "" : envKey.trim();
        if (!trimmed.isEmpty()) {
            byte[] key = SecretsBox.parseKey(trimmed);
            if (key == null) {
                throw new IllegalStateException(
                        "JKNOW_SECRET_KEY must be 32 bytes, as 64 hex characters or base64");
            }
            log.info("凭据封印钥匙取自 JKNOW_SECRET_KEY 环境变量");
            return SecretsBox.armed(key);
        }
        try {
            return SecretsBox.armed(SecretKeyFile.loadOrGenerate(Path.of(dataDir)));
        } catch (Exception e) {
            // Rust 语义：钥匙读不出启动失败（静默换新钥匙会让库里已封的行全部打不开）
            throw new IllegalStateException("secret key unavailable under data dir " + dataDir, e);
        }
    }
}
