package tech.seexun.jknow.store.secrets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 数据目录下的封印钥匙：有就读，没有就生成一把写进去（对齐旧仓库
 * crates/utopia-server/src/main.rs:686-712 secret_key_file）。
 *
 * <p>读出来解析不了就报错停下——静默生成一把新的会让库里已封的凭据全部打不开。
 * 写盘 0600（unix；Rust 同为 {@code #[cfg(unix)]}，Windows 无此属性概念）。
 */
public final class SecretKeyFile {

    private static final Logger log = LoggerFactory.getLogger(SecretKeyFile.class);

    private SecretKeyFile() {
    }

    /**
     * @param dataDir 数据目录（{data_dir}/secret.key）
     * @return 32 字节钥匙
     * @throws IOException 读不出/写不进
     */
    public static byte[] loadOrGenerate(Path dataDir) throws IOException {
        Path path = dataDir.resolve("secret.key");
        if (Files.exists(path)) {
            String text = Files.readString(path);
            byte[] key = SecretsBox.parseKey(text);
            if (key == null) {
                throw new IOException(path + " does not hold a 32-byte key "
                        + "(64 hex characters or base64)");
            }
            return key;
        }
        Files.createDirectories(dataDir);
        byte[] key = SecretsBox.generateKey();
        Files.writeString(path, SecretsBox.keyToHex(key));
        try {
            Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // Windows 文件系统无 POSIX 属性（Rust 侧 #[cfg(unix)] 同一条件跳过）
        }
        log.info("生成了凭据封印钥匙: {}（备份数据目录时一起带走；UTOPIA_SECRET_KEY 可覆盖）", path);
        return key;
    }
}
