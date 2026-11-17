package tech.seexun.jknow.web.knowledge.ingest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * BlobStore 文件系统语义（blob.rs LocalBlobStore 逐字）：内容寻址、幂等 put、
 * 缺失 delete 算成功、目录={data_dir}/files 平铺（与 Rust 参照实例同一布局——互通
 * 裁决见类注释）。
 */
class BlobStoreTests {

    @TempDir
    Path dataDir;

    private static final String SHA = "ab".repeat(32);

    @Test
    void putIsIdempotentAndGetRoundTrips() throws IOException {
        BlobStore blobs = new BlobStore("", dataDir.toString());
        blobs.put(SHA, new byte[] {1, 2, 3});
        blobs.put(SHA, new byte[] {9});
        assertArrayEquals(new byte[] {1, 2, 3}, blobs.get(SHA), "同指纹已存在即跳过（不覆盖）");
        assertTrue(Files.exists(dataDir.resolve("files").resolve(SHA)), "平铺 {data_dir}/files/{sha}");
    }

    @Test
    void deleteTreatsMissingAsSuccess() throws IOException {
        BlobStore blobs = new BlobStore("", dataDir.toString());
        blobs.delete("ff".repeat(32));
        blobs.put(SHA, new byte[] {1});
        blobs.delete(SHA);
        assertFalse(blobs.exists(SHA));
        blobs.delete(SHA);
    }

    @Test
    void overrideBeatsDataDir() throws IOException {
        Path override = Files.createDirectory(dataDir.resolve("elsewhere"));
        BlobStore blobs = new BlobStore(override.toString(), dataDir.toString());
        blobs.put(SHA, new byte[] {7});
        assertTrue(Files.exists(override.resolve(SHA)), "JKNOW_BLOB_DIR 覆盖生效（M1 兼容）");
    }
}
