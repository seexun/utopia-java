package tech.seexun.jknow.common.jobs;

/**
 * 任务失败的领域标记：这次不会因为重试而变好（对齐旧仓库 utopia-core/src/error.rs:71 Terminal）。
 * 挂上它的失败由 {@code JobsService.markFailed} 认领——第一次就落到 {@code failed}，
 * 不再走 30s×attempts² 退避（#195：余额耗尽的任务把三次退避走完只是把运维该看见的
 * 「失败」推迟七分钟）。与 {@link DeferredFailure} 同挂时本标记赢（mark_failed 先问 terminal）。
 */
public class TerminalFailure extends RuntimeException {

    public TerminalFailure() {
        super("will not recover by retrying");
    }

    /** 带业务说明的挂法（Rust 侧 {@code e.context(Terminal)}：说明在前、标记在链上）。 */
    public TerminalFailure(String context, Throwable cause) {
        super(context, cause == null ? new TerminalFailure() : cause);
    }
}
