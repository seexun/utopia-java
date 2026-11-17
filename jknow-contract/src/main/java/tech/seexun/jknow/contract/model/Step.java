package tech.seexun.jknow.contract.model;

import java.util.Locale;
import java.util.Map;

/**
 * 剧本的一步：一条 HTTP 调用 + 可选捕获 + 可选期望（+ 任务4 扩展的三类可选附件）。
 *
 * <pre>{@code
 * - id: login
 *   call: 'POST /api/v1/auth/login { "email": "${register.email}", "password": "${register.password}" }'
 *   capture: { cookie: set-cookie, kb_id: body.id }
 *   expect: { status: 200 }
 * - id: push
 *   call: POST /api/v1/sources/${sid}/ingest
 *   headers: { Authorization: 'Bearer ${push_token}' }
 * - id: upload
 *   call: POST /api/v1/kbs/${kb_id}/documents
 *   upload: { filename: note.txt, content: 'Alice works at Acme.', mime: text/plain }
 *   pause_ms: 500
 *   target: rust          # M1 定向：rust|java|both（缺省 both），双打模式下该步打哪个服务
 * }</pre>
 *
 * <p>call 语法：{@code METHOD 路径[?查询] [JSON对象体]}。方法大写；路径里的 {@code ${var}}
 * 会被变量替换；体只支持 JSON 对象（首个 {@code {} 起）。YAML 里带 JSON 体的 call 行
 * 必须用单引号包裹（plain 标量不能含「冒号+空格」），无体行可裸写。
 * capture 两种源：{@code cookie: set-cookie}（会话 cookie，后续步骤自动携带）；
 * {@code 名字: body.路径}（从响应体按点路径取值存变量，捕获读的是未规范化原文，拿到的是真实 id/token；
 * 路径段为数字时按数组下标取，如 {@code body.created.0.id}）。
 * expect.status 不符即失败——Recorder 遇失败中止该剧本（黄金样本必须是绿的）。
 *
 * <p>三类可选附件（任务4 扩展，均先做 ${var} 替换）：
 * <ul>
 * <li>{@code headers}——附加请求头。Bearer 令牌类端点（/kbs/{id}/mcp、/sources/{sid}/ingest）
 * 不认 cookie，只能走这里；值模板里的变量照常替换。</li>
 * <li>{@code upload}——multipart 文件上传（引擎唯一的非 JSON 请求体）。字段
 * {@code field}（缺省 files）/ {@code filename} / {@code content}（原样 UTF-8 文本）/ {@code mime}
 * （缺省 text/plain）。call 行此时只写方法与路径。</li>
 * <li>{@code pause_ms}——发请求前客户端等待毫秒数。给后台任务（摄取/抽取）留出落库时间，
 * 让后续列表步骤的响应形状稳定（否则 upload 后立刻 list，status 在 indexing/ready 之间抖动）。</li>
 * </ul>
 *
 * <p>M1 定向（{@code target: rust|java|both}，缺省 both，详见 {@link Target}）：仅双打模式生效，
 * 决定该步对哪个服务发请求；expect 校验与 capture 取自有侧（rust/java 步骤）或 Rust 侧（both 步骤，
 * 写路径权威）。黄金 meta 记录<b>实际路由侧</b>（both 步记 both；单侧步记其实际落侧——
 * Recorder {@code --writes java} 重定向后记 java），Diff 据此跳过单侧样本。
 *
 * <p>M3 任务 1 扩展：步骤二选一——{@code call}（HTTP 调用）或 {@code snapshot}
 * （按 kb_id 直连共享 PG dump 账本终态，见 {@link SnapshotSpec}）。快照步不发生 HTTP：
 * 文件归属跟随写路径侧（默认 golden-rust；{@code --writes java} 时随之记 golden-java），
 * 黄金文件名带 {@code .snapshot.json} 后缀、meta.kind=snapshot、meta.target 恒 both
 * （两侧各录各的一份），Diff 对快照样本恒做双侧深度比对（缺失一侧即差异，不按单侧跳过）。
 *
 * <p>M3 任务 11 扩展（心脏剧本的装置与裁决通道）：非 call 步骤增至五种——{@code snapshot}
 * 之外新增 {@code sql}（直连共享 PG 执行装置 SQL，见 {@link SqlSpec}）、{@code wait}
 * （静默等待该 kb 的后台 job，见 {@link WaitSpec}）、{@code store}（按写路径侧执行
 * store 层操作，见 {@link StoreSpec}）。五种互斥、且都不得与 {@code target} 并用
 * （定向是 HTTP 概念；这些步骤或不发请求，或跟随写路径侧——写了也不生效，装载器拒收）。
 */
public record Step(String id, String call, Map<String, String> capture, Integer expectStatus,
                   Map<String, String> headers, Map<String, String> upload, Long pauseMs, Target target,
                   SnapshotSpec snapshot, SqlSpec sql, WaitSpec waitSpec, StoreSpec store) {

    /** M0 兼容构造：未声明 target（= both）与快照载荷。 */
    public Step(String id, String call, Map<String, String> capture, Integer expectStatus,
                Map<String, String> headers, Map<String, String> upload, Long pauseMs) {
        this(id, call, capture, expectStatus, headers, upload, pauseMs, Target.BOTH, null, null, null, null);
    }

    /** M1 兼容构造：声明定向、无快照载荷。 */
    public Step(String id, String call, Map<String, String> capture, Integer expectStatus,
                Map<String, String> headers, Map<String, String> upload, Long pauseMs, Target target) {
        this(id, call, capture, expectStatus, headers, upload, pauseMs, target, null, null, null, null);
    }

    /** M3 任务 1 兼容构造：快照载荷、无任务 11 三类载荷。 */
    public Step(String id, String call, Map<String, String> capture, Integer expectStatus,
                Map<String, String> headers, Map<String, String> upload, Long pauseMs, Target target,
                SnapshotSpec snapshot) {
        this(id, call, capture, expectStatus, headers, upload, pauseMs, target, snapshot, null, null, null);
    }

    /** 声明定向（null 视为 both）。 */
    public Target targetOrDefault() {
        return target == null ? Target.BOTH : target;
    }

    /**
     * 解析 call 行（先解析后替换：变量值里的花括号不会破坏语法拆分）。
     * JSON 体以「与路径隔开一个空格的独立 {」起——路径里的 {@code ${var}} 也带花括号，
     * 不能按「首个 {」切（任务 4 修正：路径变量在任务 3 剧本里未出现过，拆分 bug 潜伏至今）。
     */
    public ParsedCall parsedCall() {
        String s = call.trim();
        int space = s.indexOf(' ');
        if (space <= 0) {
            throw new IllegalArgumentException("步骤 " + id + " 的 call 缺方法或路径: " + call);
        }
        String method = s.substring(0, space).toUpperCase(Locale.ROOT);
        String rest = s.substring(space + 1).trim();
        int brace = -1;
        if (rest.startsWith("{")) {
            brace = 0;
        } else {
            int spaced = rest.indexOf(" {");
            if (spaced >= 0) {
                brace = spaced + 1;
            }
        }
        if (brace >= 0) {
            return new ParsedCall(method, rest.substring(0, brace).trim(), rest.substring(brace));
        }
        return new ParsedCall(method, rest, null);
    }

    /** call 行的三段拆分。 */
    public record ParsedCall(String method, String path, String body) {
    }
}
