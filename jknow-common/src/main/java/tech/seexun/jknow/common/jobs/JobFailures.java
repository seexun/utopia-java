package tech.seexun.jknow.common.jobs;

/**
 * 失败标记的认领（对齐旧仓库 utopia-core/src/error.rs:88 is_terminal / :129 is_deferred）。
 * Rust 侧 anyhow::Error::is/downcast_ref 穿透 context 链——标记挂成 context 还是根都认得；
 * Java 等价物是沿 {@link Throwable#getCause()} 链逐个 instanceof。
 */
public final class JobFailures {

    private JobFailures() {
    }

    /** 这次失败被标成不必重试了吗（链上任一层挂了 Terminal 都算）。 */
    public static boolean isTerminal(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof TerminalFailure) {
                return true;
            }
        }
        return false;
    }

    /** 挂着的 Deferred 要等多久；没挂返回 null（链上任一层）。 */
    public static DeferredFailure deferredOf(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof DeferredFailure d) {
                return d;
            }
        }
        return null;
    }

    /**
     * 落进 {@code jobs.last_error} 的文本：Rust {@code format!("{err:#}")} 是错误链
     * 逐层以 {@code ": "} 连接——Java 沿 cause 链同构拼出，保住「多层上下文一句读全」。
     */
    public static String chainText(Throwable err) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (sb.length() > 0) {
                sb.append(": ");
            }
            String msg = t.getMessage();
            sb.append(msg != null ? msg : t.getClass().getSimpleName());
            if (t.getCause() != null && t.getCause() == t) {
                break; // 自引用防御（InitCause 异常形状）
            }
        }
        return sb.toString();
    }
}
