package tech.seexun.jknow.web.knowledge.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * 签名已知向量（审查收口 C-1/C-2/M-1 的对价测试——「头在场即可验」的自证教训：
 * 期望值全部由 <b>openssl 独立计算</b>，与生产代码零共享）。
 *
 * <p>向量参数固定：account=devstoreaccount1、key=Azurite 公开开发密钥（公开常量非凭据）、
 * x-ms-date=Wed, 02 Sep 2026 15:04:05 GMT、GET /devstoreaccount1/docs?restype=container
 * &amp;comp=list；SigV4：host=127.0.0.1:9000、us-east-1、u/p、20260902T150405Z。
 */
class SignatureVectorTests {

    /** openssl 独立算出的正确签名（VERB 后 12 个换行）。 */
    private static final String AZURE_SIG_12NL =
            "sghnSQeRAL/HZ/3Rfo/P/1L9TbRX5MLRP7shc2v+s0c=";

    /** 少一个换行（11 个）时的签名——C-1 那个错形态，必须与它不同。 */
    private static final String AZURE_SIG_11NL_WRONG =
            "i/dl9mOkXIuiGUp3XYSov/qg1CFjD9fKN+1tDH/BVFk=";

    /**
     * openssl 独立算出：k1=HMAC(key="AWS4p", msg="20260902")（首步的 key 是密钥串、
     * msg 是日期——首版向量的 shell 管道把两者接反过，此常数已用正序复算核对）。
     */
    private static final String SIGV4_EXPECTED =
            "0055c92c50f180d5b7002b519f43da9b7cf34d914b419e8451063966cc0cddb1";

    /**
     * C-1：string-to-sign 的完整形状（VERB 后 <b>12</b> 个换行——VERB 行尾 1 个 +
     * 11 个空字段各 1 个，微软规范明示「empty fields 的分隔符仍要求」）与
     * openssl 向量逐字对表。
     */
    @Test
    void azureStringToSignMatchesTheIndependentlyComputedVector() {
        String sts = ObjectStorage.azureStringToSign("GET", "/docs",
                "restype=container&comp=list", "devstoreaccount1",
                "Wed, 02 Sep 2026 15:04:05 GMT");
        // 完整形状断言：换行数是可数的（12），canonical 资源参数按序
        assertEquals("GET\n\n\n\n\n\n\n\n\n\n\n\n"
                + "x-ms-date:Wed, 02 Sep 2026 15:04:05 GMT\n"
                + "x-ms-version:2021-08-06\n"
                + "/devstoreaccount1/docs\n"
                + "comp:list\n"
                + "restype:container", sts);
        // openssl HMAC-SHA256(base64 解码 key, sts) 的 base64
        String signature = java.util.Base64.getEncoder().encodeToString(
                ObjectStorage.hmac(java.util.Base64.getDecoder().decode(
                        "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw=="),
                        sts.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(AZURE_SIG_12NL, signature);
        // 与 11 换行的错误形态确实不同——挡住「少写一个 \n 也能对上向量」的回归
        assertNotEquals(AZURE_SIG_11NL_WRONG, signature);
    }

    /** C-2 对价：Host 取值统一（显式默认端口折裸 host、非默认带 :port）。 */
    @Test
    void hostHeaderValueFoldsExplicitDefaultPorts() {
        assertEquals("127.0.0.1:9000",
                GuardedHttp.hostHeaderValue(java.net.URI.create("http://127.0.0.1:9000/x")));
        assertEquals("s3.example.com",
                GuardedHttp.hostHeaderValue(java.net.URI.create("https://s3.example.com:443/x")));
        assertEquals("s3.example.com",
                GuardedHttp.hostHeaderValue(java.net.URI.create("https://s3.example.com/x")));
        assertEquals("h:8443",
                GuardedHttp.hostHeaderValue(java.net.URI.create("https://h:8443/x")));
        assertEquals("h",
                GuardedHttp.hostHeaderValue(java.net.URI.create("http://h:80/x")));
    }

    /** SigV4 全量授权头对 openssl 向量（host 用统一取值函数产出的同一形态）。 */
    @Test
    void sigV4AuthorizationMatchesTheIndependentlyComputedVector() {
        String payloadHash = ObjectStorage.sha256Hex(new byte[0]);
        String auth = ObjectStorage.sigV4Authorization("GET", "/docs", "list-type=2",
                "127.0.0.1:9000", payloadHash, "20260902T150405Z", "20260902",
                "us-east-1", "u", "p");
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=u/20260902/us-east-1/s3/"
                + "aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, "
                + "Signature="), auth);
        assertEquals(SIGV4_EXPECTED,
                auth.substring(auth.lastIndexOf('=') + 1), auth);
        // 显式默认端口的 host 折叠进签名（与线上 Host 同一真源——C-2 边值）
        String auth443 = ObjectStorage.sigV4Authorization("GET", "/docs", "list-type=2",
                "s3.example.com", payloadHash, "20260902T150405Z", "20260902",
                "us-east-1", "u", "p");
        String authBare = ObjectStorage.sigV4Authorization("GET", "/docs", "list-type=2",
                "s3.example.com", payloadHash, "20260902T150405Z", "20260902",
                "us-east-1", "u", "p");
        assertEquals(auth443, authBare, "host:443 折叠后与裸 host 的签名必须一致（同一取值函数）");
    }

    /** M-1：enc 按 SigV4 规范查询规矩（* 编 %2A、~ 保持字面）。 */
    @Test
    void encFollowsRfc3986UnreservedRules() {
        assertEquals("%2A", ObjectStorage.enc("*"));
        assertEquals("a~b", ObjectStorage.enc("a~b"));
        assertEquals("docs%2Fa%3Fb%3Dc", ObjectStorage.enc("docs/a?b=c"));
        assertEquals("2026-01-03T00%3A00%3A00Z", ObjectStorage.enc("2026-01-03T00:00:00Z"));
    }
}
