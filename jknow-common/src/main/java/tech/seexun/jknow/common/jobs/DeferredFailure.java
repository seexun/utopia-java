package tech.seexun.jknow.common.jobs;

import java.time.Duration;

/**
 * 任务失败的领域标记：这次不是错了，是<b>等一下再试</b>（对齐旧仓库
 * utopia-core/src/error.rs:108-125 Deferred，#526）。{@code JobsService.markFailed}
 * 认这个标记——把任务挂回 {@code queued}、{@code run_at} 推到 {@code retry_in} 之后、
 * 把 {@code attempts} 退回去，不烧重试预算。与默认退避的关键区别：默认 30s×attempts²
 * 按「第几次失败」递增，而 {@code Deferred} 与次数无关——同一等待条件两次挂回，
 * 两次得到同一个 {@code run_at} 偏移。
 *
 * <p>等待有期限（jobs.rs:402 DEFER_WINDOW_SECS=3600s）：从第一次挂回算起超过一小时
 * 还在等就按普通失败退避、烧预算，免得一个永远补不齐的依赖让任务永远排着。
 * 与 {@link TerminalFailure} 同挂时 Terminal 赢。
 */
public class DeferredFailure extends RuntimeException {

    private final Duration retryIn;

    public DeferredFailure(Duration retryIn) {
        // 落进 last_error 要让人直接读懂，所以写明秒数（error.rs:118-124 Display 原文格式）
        super("deferred; retry in " + retryIn);
        this.retryIn = retryIn;
    }

    /** 带业务说明的挂法（Rust {@code e.context(Deferred::new(...))}：说明在外、标记在链上）。 */
    public DeferredFailure(String context, Duration retryIn) {
        super(context, new DeferredFailure(retryIn));
        this.retryIn = retryIn;
    }

    /** 下一次重试等多久（秒粒度语义由 JobsService.deferredRetrySecs 收口）。 */
    public Duration retryIn() {
        return retryIn;
    }
}
