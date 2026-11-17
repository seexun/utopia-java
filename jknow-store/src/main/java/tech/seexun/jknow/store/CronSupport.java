package tech.seexun.jknow.store;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.springframework.scheduling.support.CronExpression;
import tech.seexun.jknow.common.AppException;

/**
 * 标准 5 段 cron 的校验与求值（逐字对齐旧仓库 sources.rs:22-38 validate_cron /
 * :72-81 cron_next_after 的语义）。
 *
 * <p><b>实现取舍</b>：Rust 用 cron crate 的 6 段（补秒位）解析；Java 用 Spring 的
 * {@link CronExpression}（同为 6 段，同样补 {@code "0 "} 秒位、同样服务器本地时区
 * {@code chrono::Local ↔ ZoneId.systemDefault()}）。常见表达式（数字/名字/列表/区间/步进）
 * 两边判定一致；Spring 额外收 {@code L}/{@code W}/{@code #}、cron crate 不收——这些
 * 超出「UI 可视化选择器构建，Advanced 模式才暴露原生表达式」的接受面，分歧可接受
 * （任务 4 报告记取舍）。
 */
public final class CronSupport {

    private CronSupport() {
    }

    /**
     * 校验并规范化（sources.rs:23-38 逐字语义）：空白折一、必须 5 段、逐段可解析；
     * 分钟越界/段数不对 → INVALID bad_cron_fields / bad_cron（文案逐字）。
     */
    public static String validate(String expr) {
        String normalized = String.join(" ", expr.trim().split("\\s+"));
        long fields = normalized.isEmpty() ? 0 : normalized.split(" ").length;
        if (fields != 5) {
            throw AppException.invalidDetail("bad_cron_fields",
                    "Cron expression must have 5 fields (minute hour day month weekday)",
                    "got " + fields);
        }
        try {
            // cron crate：Schedule::from_str(&format!("0 {normalized}"))——同样补秒位
            CronExpression.parse("0 " + normalized);
        } catch (IllegalArgumentException e) {
            throw AppException.invalidDetail("bad_cron", "Invalid cron expression", e.getMessage());
        }
        return normalized;
    }

    /**
     * cron 的下一次触发时刻（sources.rs:72-81 逐字语义）：服务器本地时区；
     * 解析失败（脏行）返回 null——due 过滤当它不到期，下一轮修复前不再打扰日志。
     */
    public static OffsetDateTime nextAfter(String expr, OffsetDateTime after) {
        CronExpression schedule;
        try {
            schedule = CronExpression.parse("0 " + expr);
        } catch (IllegalArgumentException e) {
            return null;
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDateTime next = schedule.next(after.atZoneSameInstant(zone).toLocalDateTime());
        return next == null ? null : next.atZone(zone).toOffsetDateTime().withOffsetSameInstant(
                after.getOffset());
    }
}
