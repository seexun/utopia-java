package tech.seexun.jknow.web.knowledge.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 原始文件字节的存取接缝（逐字对齐旧仓库 crates/utopia-server/src/blob.rs 的
 * LocalBlobStore）：内容寻址——key 就是文件内容的 sha256，接口里没有「路径」概念。
 * 幂等、去重、不可变全部由「内容即地址」免费获得。
 *
 * <p><b>布局裁决（与任务简报的一处出入）</b>：Rust 实现是<b>平铺</b>
 * {@code {data_dir}/files/{sha256}}（blob.rs:25 「与历史行为逐字节一致」、state.rs:61
 * {@code data_dir.join("files")}）；任务简报写的 {@code {sha256[0:2]}/ 分桶}会与参照实例
 * 互不可见——共享同一 data_dir 的双打架构下，Java 上传的原文 Rust 读不到（反之亦然）。
 * 互通优先（m2-plan「与 Rust data/files 同目录」），故落平铺；分桶若将来需要，须两侧同步迁移。
 *
 * <p>目录解析：{@code JKNOW_BLOB_DIR} 覆盖（M1 任务 5 content 读路径的旧开关，保留兼容）
 * → 缺省 {@code {jknow.data-dir}/files}（与 Rust 共享 data_dir，同 .env 取值——
 * M2 任务 1 secret.key 已用同一目录约定）。
 */
@Component
public class BlobStore {

    private final Path dir;

    public BlobStore(@Value("${JKNOW_BLOB_DIR:}") String override,
                     @Value("${jknow.data-dir:data}") String dataDir) {
        this.dir = (override != null && !override.isBlank())
                ? Path.of(override)
                : Path.of(dataDir).resolve("files");
    }

    /** 存储根（{data_dir}/files）。 */
    public Path dir() {
        return dir;
    }

    /** 幂等写入：同指纹已存在即跳过（blob.rs:38-45）。 */
    public void put(String sha256, byte[] bytes) throws IOException {
        Files.createDirectories(dir);
        Path path = dir.resolve(sha256);
        if (!Files.exists(path)) {
            Files.write(path, bytes);
        }
    }

    /** 读原文；不存在抛 IOException（blob.rs:47-49）。 */
    public byte[] get(String sha256) throws IOException {
        return Files.readAllBytes(dir.resolve(sha256));
    }

    /** 存在性（接口完整性；回放/GC 路径的将来消费者，blob.rs:51-53）。 */
    public boolean exists(String sha256) {
        return Files.exists(dir.resolve(sha256));
    }

    /**
     * 真删（#268 下半）：只在库里确认没人再引用这份指纹之后调用。幂等——不存在也算成功
     * （blob.rs:55-61 的 NotFound→Ok）。
     */
    public void delete(String sha256) throws IOException {
        Files.deleteIfExists(dir.resolve(sha256));
    }
}
