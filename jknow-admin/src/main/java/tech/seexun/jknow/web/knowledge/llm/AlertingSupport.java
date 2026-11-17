package tech.seexun.jknow.web.knowledge.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.common.uuids.Uuids;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.store.entity.Job;
import tech.seexun.jknow.store.mapper.AlertsMapper;

/**
 * 任务失败的告警面（alerting.rs:145-204 的任务 8 子集——挂账自 m2-report「hopeless/
 * observe_job_failure 钩子无落点」；日清 purge 与库级告警族仍属后续里程碑）。
 *
 * <p><b>只有欠费是没救的</b>（#195）：限流会自己恢复，端点不可达可能是重启中的服务
 * ——只有余额不会在七分钟里自己长回来。判据与告警分类同源（都是
 * {@link ChatClient} 的类型判定，不是文本匹配），「报成 error 的那一类」与「不再重试的
 * 那一类」永远是同一类，不会分叉。
 */
@Component
public class AlertingSupport {

    private static final Logger log = LoggerFactory.getLogger(AlertingSupport.class);

    /** alerts.rs kind::LLM_OUT_OF_CREDIT：账号付不起请求——欠费或套餐配额用尽。 */
    public static final String LLM_OUT_OF_CREDIT = "llm.out_of_credit";
    /** alerts.rs kind::LLM_RATE_LIMITED：退避重试用尽后仍过不去。 */
    public static final String LLM_RATE_LIMITED = "llm.rate_limited";
    /** alerts.rs kind::LLM_UNREACHABLE：端点没给出可用的回答。 */
    public static final String LLM_UNREACHABLE = "llm.unreachable";

    private final AlertsMapper alerts;

    public AlertingSupport(AlertsMapper alerts) {
        this.alerts = alerts;
    }

    /** alerting.rs:145-153 hopeless：这次失败还值不值得重试——只有 out_of_credit 不值得。 */
    public static boolean hopeless(Throwable err) {
        return ChatClient.outOfCredit(err) != null;
    }

    /**
     * main.rs:199-204 的 Terminal 标记对等：调用方在 hopeless 的错误上包这一层，
     * {@link #observeJobFailure} 据此「这一次就是最后一次」，现在就报。
     */
    public static final class TerminalException extends RuntimeException {
        public TerminalException(Throwable cause) {
            super(cause);
        }
    }

    /** Rust is_terminal：异常链上有 Terminal 标记。 */
    public static boolean isTerminal(Throwable err) {
        for (Throwable c = err; c != null; c = c.getCause()) {
            if (c instanceof TerminalException) {
                return true;
            }
        }
        return false;
    }

    /**
     * alerting.rs:176-204 observe_job_failure：任务失败该报哪一类告警，报不报。
     * 三类该做的事完全不同——欠费要人去充值，限流要降并发或升配额，端点不可达要查
     * 网络。混成一条会把人指向错的地方，比不报还费时间。
     */
    public void observeJobFailure(Job job, Throwable err) {
        // 标成不必重试的这一次就是最后一次，所以现在就报（#195）。漏掉这一句的后果是
        // 失败得更快、却反而没人被告知——比不改还糟
        if (job.attempts() < job.maxAttempts() && !isTerminal(err)) {
            return;
        }
        String[] kind = alertFor(err);
        if (kind == null) {
            return;
        }
        try {
            var detail = JsonNodeFactory.instance.objectNode();
            detail.put("job", job.kind());
            detail.put("error", String.valueOf(err.getMessage() != null ? err.getMessage()
                    : err.toString()));
            // 系统级：哪个库的任务撞上的不重要，端点与配额是整个部署共用的
            alerts.insertAlert(Uuids.nowV7(), null, kind[1], kind[0], "admin", "system", null,
                    detail);
        } catch (Exception e) {
            log.warn("上报告警失败 kind={}: {}", kind[0], e.getMessage());
        }
    }

    /**
     * alerting.rs:155-173 alert_for：分类纯函数。顺序是「更该先说的放前面」——欠费排在
     * 限流之前，两者的状态码会重叠（OpenAI 的余额耗尽也回 429），而分类在 LlmError 里
     * 已经做完，这里的顺序只是把「更该先说的那件事」放前面。
     */
    static String[] alertFor(Throwable err) {
        if (ChatClient.outOfCredit(err) != null) {
            // error：在有人充值之前抽取一直是停的
            return new String[]{LLM_OUT_OF_CREDIT, "error"};
        }
        if (ChatClient.rateLimited(err) != null) {
            // warning 不是 error：配额会自己恢复，端点挂了不会
            return new String[]{LLM_RATE_LIMITED, "warning"};
        }
        if (ChatClient.isUnreachable(err)) {
            return new String[]{LLM_UNREACHABLE, "error"};
        }
        return null;
    }
}
