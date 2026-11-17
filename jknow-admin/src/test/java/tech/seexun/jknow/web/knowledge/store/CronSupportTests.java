package tech.seexun.jknow.web.knowledge.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import tech.seexun.jknow.common.AppException;
import tech.seexun.jknow.store.CronSupport;

/**
 * cron 5 段校验与求值（任务 4；对齐旧仓库 sources.rs:22-38/508-526 的测试面）：
 * 空白折一/段数与越界/名字星期/下一次触发在本地时区。
 */
class CronSupportTests {

    @Test
    void validationNormalisesAndRejects() {
        assertEquals("30 9 * * *", CronSupport.validate("30 9 * * *"));
        assertEquals("0 9 * * Mon,Thu", CronSupport.validate("  0  9 * * Mon,Thu "));
        assertThrows(AppException.class, () -> CronSupport.validate("9 * * *"), "4 段");
        assertThrows(AppException.class, () -> CronSupport.validate("99 9 * * *"), "分钟越界");
        assertThrows(AppException.class, () -> CronSupport.validate("0 0 0 0 0 0"), "6 段");
        AppException bad = assertThrows(AppException.class,
                () -> CronSupport.validate("99 9 * * *"));
        assertEquals("bad_cron", bad.code().orElseThrow());
        AppException fields = assertThrows(AppException.class,
                () -> CronSupport.validate("9 * * *"));
        assertEquals("bad_cron_fields", fields.code().orElseThrow());
        assertEquals("got 4", fields.detail().orElseThrow());
    }

    @Test
    void nextAfterComputesInServerLocalZone() {
        OffsetDateTime after = OffsetDateTime.now();
        OffsetDateTime next = CronSupport.nextAfter("*/5 * * * *", after);
        assertNotNull(next);
        assertTrue(next.isAfter(after));
        assertTrue(next.minusMinutes(5).isBefore(after) || next.minusMinutes(5).equals(after),
                "5 分钟粒度的下一次就在 5 分钟内");
        // 脏行解析失败 → null（due 过滤当它不到期）
        assertNull(CronSupport.nextAfter("not a cron", after));
    }
}
