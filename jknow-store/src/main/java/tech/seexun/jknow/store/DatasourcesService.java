package tech.seexun.jknow.store;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.seexun.jknow.store.entity.DataSourceView;
import tech.seexun.jknow.store.mapper.DatasourcesMapper;
import tech.seexun.jknow.store.mapper.DatasourcesMapper.DataSourceRow;

/**
 * 数据源视图组装（datasources.rs:23-58 conn_summary / row_to_view）：
 * 连接串在库里是封印的（secrets.rs AES-256-GCM，{@code enc:v1:} 前缀），**摘要只露
 * host:port/db、凭据不外流**——「行 → 视图」的那道关口。开不了（没装钥匙/换过钥匙）就说
 * 开不了（"(sealed with another key)"），不把密文当 URL 去解析。
 *
 * <p>钥匙解析（secrets.rs:47-58 parse_key 的 Java 对等）：环境变量
 * {@code JKNOW_SECRET_KEY}（64 位十六进制或 44 位 base64，都收）；未设时与 Rust
 * 「一个进程一把钥匙」的未初始化语义一致——开封只认明文（无前缀原样返回，seals.rs:87-89）。
 */
@Service
public class DatasourcesService {

    private static final String SEALED_PREFIX = "enc:v1:";
    private static final int NONCE_LEN = 12;

    private final DatasourcesMapper datasources;
    private final SecretKeySpec key;

    public DatasourcesService(DatasourcesMapper datasources,
                              @Value("${JKNOW_SECRET_KEY:}") String secretKey) {
        this.datasources = datasources;
        this.key = parseKey(secretKey);
    }

    /** datasources.rs:163-175 mounted：本库挂载的。 */
    public List<DataSourceView> mounted(UUID kbId) {
        return datasources.selectMounted(kbId).stream().map(this::rowToView).toList();
    }

    /** datasources.rs:192-207 granted_to_workspace：授给工作区的（available 面）。 */
    public List<DataSourceView> grantedToWorkspace(UUID workspaceId) {
        return datasources.selectGrantedToWorkspace(workspaceId).stream()
                .map(this::rowToView).toList();
    }

    /** datasources.rs:61-68 list：全部署（admin 面）。 */
    public List<DataSourceView> listAll() {
        return datasources.selectAll().stream().map(this::rowToView).toList();
    }

    /** datasources.rs:209-222 grants_for_source：一个源授给了哪些工作区。 */
    public List<DatasourcesMapper.WorkspaceGrant> grantsForSource(UUID dataSourceId) {
        return datasources.selectGrantsForSource(dataSourceId);
    }

    /** datasources.rs:223-240：授权一个工作区用这个源（幂等）。 */
    public void grant(UUID dataSourceId, UUID workspaceId, UUID actor) {
        datasources.insertGrant(dataSourceId, workspaceId, actor);
    }

    /**
     * 收回授权，<b>连同该工作区里已挂上的一起卸掉</b>（datasources.rs:242-268）——只删
     * 授权行不够：mounted 读的是 kb_data_sources，留着挂载等于收回了授权而访问照旧，
     * 一个不生效的权限撤销比没有还危险。返回卸掉的挂载数。
     */
    @org.springframework.transaction.annotation.Transactional
    public long revoke(UUID dataSourceId, UUID workspaceId) {
        int unmounted = datasources.deleteMountsInWorkspace(dataSourceId, workspaceId);
        datasources.deleteGrant(dataSourceId, workspaceId);
        return unmounted;
    }

    /** datasources.rs:271-283：这个源对这个知识库是不是授权过的（挂载前必查）。 */
    public boolean isGranted(UUID kbId, UUID dataSourceId) {
        return datasources.selectGranted(kbId, dataSourceId) != null;
    }

    /** datasources.rs:142-152 mount（幂等）。 */
    public void mount(UUID kbId, UUID dataSourceId) {
        datasources.insertMount(kbId, dataSourceId);
    }

    /** datasources.rs:154-160 unmount（幂等——0 行也是 ok）。 */
    public void unmount(UUID kbId, UUID dataSourceId) {
        datasources.deleteMount(kbId, dataSourceId);
    }

    /**
     * datasources.rs:71-105 create：登记一个源。连接串<b>封印入库</b>（secrets::seal 对位：
     * 未装钥匙时按明文存——与 open 只认明文同一档的未初始化语义）。名字空白/
     * 连接串空白是 422（invalid），引擎合法取值在迁移 0020 的 CHECK 里，这里不复制一份。
     */
    public UUID create(String name, String engine, String connString, UUID createdBy) {
        if (name == null || name.trim().isEmpty()) {
            throw tech.seexun.jknow.common.AppException.invalid("ds_name_required",
                    "Data source name is required");
        }
        if (engine == null || engine.isEmpty() || connString == null
                || connString.trim().isEmpty()) {
            throw tech.seexun.jknow.common.AppException.invalid("bad_conn_string",
                    "A connection string is required");
        }
        UUID id = tech.seexun.jknow.common.uuids.Uuids.nowV7();
        datasources.insertDataSource(id, name.trim(), engine, seal(connString.trim()), createdBy);
        return id;
    }

    /**
     * datasources.rs:107-117 delete：删一个源。0 行 → NotFound（不存在/已删）。
     */
    public void delete(UUID id) {
        if (datasources.deleteDataSource(id) == 0) {
            throw tech.seexun.jknow.common.AppException.NOT_FOUND;
        }
    }

    /** datasources.rs:129-139 record_test：测试结果落行。 */
    public void recordTest(UUID id, boolean ok) {
        datasources.updateTestResult(id, ok);
    }

    /**
     * datasources.rs:177-190 engine_and_conn：(engine, 明文连接串)——查询执行/测试/拉
     * schema 用。凭据不出服务端；开封失败（换过钥匙）原样报错。
     */
    public EngineAndConn engineAndConn(UUID id) {
        DatasourcesMapper.EngineAndConn row = datasources.selectEngineAndConn(id);
        if (row == null) {
            throw tech.seexun.jknow.common.AppException.NOT_FOUND;
        }
        try {
            return new EngineAndConn(row.engine(), open(row.conn()));
        } catch (Exception e) {
            throw new IllegalStateException("could not open the sealed connection string", e);
        }
    }

    /** engine_and_conn 的返回形。 */
    public record EngineAndConn(String engine, String conn) {
    }

    /**
     * secrets.rs seal 的 Java 对位：装了钥匙 AES-256-GCM 封印（enc:v1: 前缀 + nonce + 密文
     * + tag）；未装钥匙原样返回（一个进程一把钥匙的未初始化语义，datasources.rs:99 的
     * secrets::seal 同走）。
     */
    private String seal(String plain) {
        if (key == null) {
            return plain;
        }
        try {
            byte[] nonce = new byte[NONCE_LEN];
            new java.security.SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
            byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[NONCE_LEN + sealed.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(sealed, 0, out, NONCE_LEN, sealed.length);
            return SEALED_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not seal the connection string", e);
        }
    }

    /** datasources.rs:39-57 row_to_view：连接串换成无凭据摘要（四条查询共用的那道关口）。 */
    private DataSourceView rowToView(DataSourceRow row) {
        String summary;
        try {
            summary = connSummary(open(row.conn()));
        } catch (Exception e) {
            summary = "(sealed with another key)";
        }
        return new DataSourceView(row.id(), row.name(), row.engine(), summary,
                row.createdAt(), row.lastTestAt(), row.lastTestOk());
    }

    /** secrets.rs:87-105 open：无前缀按明文原样返回（升级前旧行）；有前缀 AES-GCM 开封。 */
    private String open(String stored) throws Exception {
        if (stored == null || !stored.startsWith(SEALED_PREFIX)) {
            return stored;
        }
        if (key == null) {
            throw new IllegalStateException("a sealed secret was read before the secret key was loaded");
        }
        byte[] bytes = Base64.getDecoder().decode(stored.substring(SEALED_PREFIX.length()));
        if (bytes.length < NONCE_LEN) {
            throw new IllegalStateException("a sealed secret is too short");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, bytes, 0, NONCE_LEN));
        byte[] plain = cipher.doFinal(bytes, NONCE_LEN, bytes.length - NONCE_LEN);
        return new String(plain, StandardCharsets.UTF_8);
    }

    /** secrets.rs:47-58 parse_key：64 位十六进制或 base64，都收；解不出 32 字节 → null（不装钥匙）。 */
    private static SecretKeySpec parseKey(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String t = text.trim();
        try {
            byte[] bytes;
            if (t.length() == 64 && t.matches("[0-9a-fA-F]+")) {
                bytes = new byte[32];
                for (int i = 0; i < 32; i++) {
                    bytes[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
                }
            } else {
                bytes = Base64.getDecoder().decode(t);
            }
            if (bytes.length == 32) {
                return new SecretKeySpec(bytes, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // 解不出钥匙：与 Rust init 失败同路——不装钥匙，开封只认明文
        }
        return null;
    }

    /** datasources.rs:23-35 conn_summary：host[:port]path；解析不出 "(unparsed)"。 */
    static String connSummary(String conn) {
        try {
            URI u = URI.create(conn);
            String host = u.getHost() == null ? "?" : u.getHost();
            String port = u.getPort() == -1 ? "" : ":" + u.getPort();
            String path = u.getPath() == null ? "" : u.getPath();
            return host + port + path;
        } catch (Exception e) {
            return "(unparsed)";
        }
    }
}
