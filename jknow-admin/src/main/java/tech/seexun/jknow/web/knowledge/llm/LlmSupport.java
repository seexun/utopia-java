package tech.seexun.jknow.web.knowledge.llm;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tech.seexun.jknow.llm.ChatClient;
import tech.seexun.jknow.llm.EmbedClient;
import tech.seexun.jknow.llm.ChatClient.ChatMessage;
import tech.seexun.jknow.llm.ChatClient.LlmError;
import tech.seexun.jknow.llm.ChatClient.Reply;
import tech.seexun.jknow.store.entity.LlmSettingsRow;

/**
 * 从工作区设置构造 ChatClient、按模型的并发闸门，以及<b>限流退避的 chat 调用</b>
 * （llm_util.rs + extraction.rs:18-91 逐语义）。
 *
 * <p><b>限流与其他失败的区别是它会自己好</b>，所以从前那句「跳过该分块」用在它身上就是把
 * 一分钟的等待换成永久的数据缺口——实测一次 1884 块的灌入里 55/60 篇文档整篇失败，而
 * 端点一直是好的。
 */
@Component
public class LlmSupport {

    private static final Logger log = LoggerFactory.getLogger(LlmSupport.class);

    /** extraction.rs:19 RATE_LIMIT_TRIES：限流最多退避重试 5 次。只对会自愈的类别生效。 */
    static final int RATE_LIMIT_TRIES = 5;

    /**
     * extraction.rs:22 RATE_LIMIT_CAP：单次退避的上限（60s）。总等待封顶在两分钟出头，
     * 一个配额永远打满的账号会干脆地失败，而不是把 worker 槽占死。
     */
    static final Duration RATE_LIMIT_CAP = Duration.ofSeconds(60);

    private final JdbcTemplate jdbc;
    private final Map<String, Semaphore> gates = new ConcurrentHashMap<>();
    private final Map<String, Integer> gateLimits = new ConcurrentHashMap<>();

    public LlmSupport(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** llm_util.rs:13-23 chat_client：chat_ready 才建；带 reasoning_effort（空则不带）。 */
    public static ChatClient chatClient(LlmSettingsRow s) {
        if (s == null || !s.chatReady()) {
            return null;
        }
        return new ChatClient(s.chatBaseUrl(), s.chatApiKey(), s.chatModel(),
                s.chatReasoningEffort());
    }

    /** llm_util.rs:71-80 embed_client：embed_ready 才建（Java 侧嵌入走 EmbedClient）。 */
    public static EmbedClient embedClient(LlmSettingsRow s) {
        if (s == null || !s.embedReady()) {
            return null;
        }
        return new EmbedClient(s.embedBaseUrl(), s.embedApiKey(), s.embedModel());
    }

    /**
     * llm_util.rs:97-129 acquire：后台任务调模型前取一张许可，持有到调用结束。限额读不到
     * （表还没建、库暂时不可达）时<b>放行</b>：并发限制是保护措施，不该因为读不到配置把
     * 整条流水线卡死。改完即时生效——换一把新的信号量，旧的在飞许可自然跑完。
     */
    public Semaphore acquirePermit(String baseUrl, String model) {
        Integer limit;
        try {
            limit = jdbc.query(
                    "SELECT max_concurrent FROM model_concurrency WHERE base_url = ? AND model = ?",
                    rs -> rs.next() ? rs.getInt(1) : null, baseUrl, model);
            if (limit == null) {
                limit = jdbc.query(
                        "SELECT default_model_concurrency FROM deployment_settings LIMIT 1",
                        rs -> rs.next() ? rs.getInt(1) : 10);
            }
        } catch (Exception e) {
            return null; // 读不到：放行
        }
        int n = Math.max(1, limit);
        String key = baseUrl + "|" + model;
        Semaphore gate = gates.compute(key, (k, old) -> {
            if (old == null || gateLimits.get(k) != n) {
                gateLimits.put(k, n);
                return new Semaphore(n);
            }
            return old;
        });
        try {
            gate.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return gate;
    }

    /** llm_util.rs:131-135 acquire_chat 的便捷形式（拿到的是许可；返回给调用方释放）。 */
    public Semaphore acquireChat(LlmSettingsRow s) {
        if (s.chatBaseUrl() == null || s.chatModel() == null) {
            return null;
        }
        return acquirePermit(s.chatBaseUrl(), s.chatModel());
    }

    /**
     * extraction.rs:28-35 jitter：退避的抖动——取半区间（base/2 到 base）而不是全区间：
     * 退避仍然单调增长，只是错开。不引随机源，纳秒时钟就够散。
     */
    static Duration jitter(Duration base) {
        long nanos = System.nanoTime() & 0xFFFFFFFFL;
        long half = base.toMillis() / 2;
        return base.dividedBy(2).plus(Duration.ofMillis(half == 0 ? 0 : nanos % half));
    }

    /**
     * 抽取的 chat 调用（extraction.rs:49-91 chat_retrying_rate_limits_at 逐语义）：
     * 温度由调用方定（开放抽取要 0）。许可只包住调用本身（退避期间不占许可）；<b>走流式</b>
     * ——思考期就是字节，读超时于是只杀真正卡住的请求。
     *
     * <p>会自愈的那几类（限流/端点不可用/流中断）退避重试：基值 2s 指数翻倍、CAP 60s、抖动
     * 半区间；其余照原样抛出。<b>out_of_credit 不在重试之列</b>——欠费等到天亮也还是欠费，
     * 调用方（extraction）据此终止整篇（#1095）。
     */
    public Reply chatRetryingRateLimits(LlmSettingsRow settings, ChatClient client,
                                        List<ChatMessage> messages, Double temperature)
            throws IOException, InterruptedException {
        Duration backoff = Duration.ofSeconds(2);
        Exception last = null;
        for (int attempt = 1; attempt <= RATE_LIMIT_TRIES; attempt++) {
            Exception failure;
            Semaphore permit = acquireChat(settings);
            try {
                return client.chatAtStreaming(messages, temperature);
            } catch (Exception e) {
                failure = e;
            } finally {
                // 许可只包住调用本身（extraction.rs:58-67）：出了这个块就还回去——
                // 退避睡觉的 60 秒里不占着，一个在等的分块不该挡住本来可以通过的另一个
                // （审查 I-4：从前 finally 排在 catch 的 sleep 之后，睡多久占多久）
                if (permit != null) {
                    permit.release();
                }
            }
            last = failure;
            LlmError hit = ChatClient.transientHit(failure);
            if (hit == null) {
                throw failure instanceof IOException io ? io : new IOException(failure);
            }
            if (attempt == RATE_LIMIT_TRIES) {
                throw new IOException(hit.kind() + " after " + RATE_LIMIT_TRIES
                        + " attempts: " + hit.getMessage(), hit);
            }
            Duration delay = hit.retryAfter() != null ? hit.retryAfter() : backoff;
            if (delay.compareTo(RATE_LIMIT_CAP) > 0) {
                delay = RATE_LIMIT_CAP;
            }
            delay = jitter(delay);
            log.warn("端点这次没答成（{}，第 {} 次），退避 {}ms 后重试", hit.kind(), attempt,
                    delay.toMillis());
            Thread.sleep(delay.toMillis());
            backoff = backoff.multipliedBy(2);
            if (backoff.compareTo(RATE_LIMIT_CAP) > 0) {
                backoff = RATE_LIMIT_CAP;
            }
        }
        throw last instanceof IOException io ? io : new IOException(last);
    }
}
