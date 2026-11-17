package tech.seexun.jknow.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Normalizer TDD 用例（任务 3 简报原样给出前两个，后两个按简报注释补全；先红后绿）。
 * 规范化目标：同一接口两次真实调用的响应，规范化后逐字节一致——uuid/时间戳占位、
 * 键序稳定、浮点容差、噪声字段掩码，四件事缺一不可。
 */
class NormalizerTests {

    @Test void uuid与时间戳替换为占位符() {
        assertEquals("{\"id\":\"<uuid>\"}", Normalizer.json("{\"id\":\"01a1b501-3555-77d2-a63c-a472d464151a\"}"));
        assertEquals("{\"at\":\"<ts>\"}", Normalizer.json("{\"at\":\"2026-11-10T08:30:00.123456Z\"}"));
    }

    @Test void json对象数组按键排序保稳定() {
        assertEquals(Normalizer.json("[{\"b\":1},{\"a\":2}]"), Normalizer.json("[{\"a\":2},{\"b\":1}]"));
    }

    @Test void 浮点容差() {
        /* 0.30000001 == 0.3：浮点按 6 位小数四舍五入，抹掉序列化/计算尾差 */
        assertEquals(Normalizer.json("{\"score\":0.3}"), Normalizer.json("{\"score\":0.30000001}"));
    }

    @Test void 指定字段整体掩码() {
        /* duration/token 类噪声字段整体替换为占位符（每次调用必变，无法占位化） */
        assertEquals("{\"duration\":\"<masked>\",\"token\":\"<masked>\"}",
                Normalizer.json("{\"token\":\"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.SIG\",\"duration\":1234}",
                        Set.of("token", "duration")));
    }

    /** 录制链路依赖的默认行为：单参 json() 自带默认掩码集（token/duration），黄金样本无需逐剧本配置。 */
    @Test void 默认掩码集在单参json生效() {
        assertEquals("{\"token\":\"<masked>\"}", Normalizer.json("{\"token\":\"eyJhbGciOiJIUzI1NiJ9.SIG\"}"));
    }

    /** 嵌套结构里同样生效：数组内对象、对象内数组、深层 uuid/时间戳。 */
    @Test void 嵌套结构与数组内对象同样规范化() {
        assertEquals(
                Normalizer.json("{\"list\":[{\"z\":\"01a1b501-3555-77d2-a63c-a472d464151a\"},{\"a\":\"2026-11-10T08:30:00Z\"}]}"),
                Normalizer.json("{\"list\":[{\"a\":\"2026-11-10T09:31:01.999999Z\"},{\"z\":\"ffffffff-ffff-ffff-ffff-ffffffffffff\"}]}"));
    }

    /** 任务4 补充：服务器生成的一次性标识（自增 job_id、PAT 随机前缀、来源推送密钥）也进默认掩码。 */
    @Test void 一次性标识字段默认掩码() {
        assertEquals("{\"ingest_token\":\"<masked>\",\"job_id\":\"<masked>\",\"token_prefix\":\"<masked>\"}",
                Normalizer.json("{\"job_id\":521,\"token_prefix\":\"utp_pat_55287acd\",\"ingest_token\":\"utp_abcd\"}"));
    }

    /** 任务4 补充：服务端拿 uuid 当对象键（规则版本史 labels 按类型 id 索引）——键也要占位，且碰撞去重保条目数。 */
    @Test void uuid对象键占位且碰撞去重() {
        assertEquals(
                Normalizer.json("{\"labels\":{\"01a1c555-0000-7000-8000-000000000001\":\"Firm\"}}"),
                Normalizer.json("{\"labels\":{\"ffffffff-ffff-7fff-8fff-ffffffffffff\":\"Firm\"}}"));
        assertEquals("{\"labels\":{\"<uuid>\":\"A\",\"<uuid>#2\":\"B\"}}",
                Normalizer.json("{\"labels\":{\"01a1c555-0000-7000-8000-000000000002\":\"B\",\"01a1c555-0000-7000-8000-000000000001\":\"A\"}}"));
    }
}
