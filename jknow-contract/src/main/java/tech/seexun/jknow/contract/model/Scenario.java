package tech.seexun.jknow.contract.model;

import java.util.List;
import java.util.Map;

/**
 * 一个 YAML 剧本：setup（可选前置）+ vars（静态变量）+ steps（顺序执行的调用链）。
 * 变量命名空间是扁平的：setup 展开为 {@code register.email/register.password/register.display_name}，
 * vars 原样进入，capture 以声明名进入（如 {@code kb_id}），步骤里用 {@code ${名字}} 引用。
 *
 * <p>setup 两类前置（M0 任务4 扩展，均可省略但 setup 映射出现时至少其一）：
 * <ul>
 * <li>{@code register}——主账号：注册拿会话（409 已注册转登录），是剧本的会话来源；</li>
 * <li>{@code ensure}——附加账号列表：只保证存在（注册或 409 转登录验证口令），<b>不切换会话</b>。
 * 供「第二用户」剧本用：403 越权、成员角色等需要第二个身份；在步骤里直接注册第二用户会因
 * 重录时 409 而失稳，ensure 与 setup 一样不进黄金样本。</li>
 * </ul>
 * 只有 ensure 没有 register 时剧本以<b>无会话</b>状态开跑——前几步天然就是 401 无 cookie 用例，
 * 中途再以 login 步骤捕获 set-cookie 建立会话。
 *
 * <p>M1 双打裁定（m1-recon §5）：setup 块整体视为 {@code target: rust}——账号注册/登录是
 * 写路径，归 Rust（两服务共享同一 PostgreSQL）；步骤级定向见 {@link Step#target()}。
 *
 * <p>P3 golden 复活批：setup 块升级为 {@link Setup}（mode 缺省 register，向后兼容）——
 * {@code mode: ruoyi_login} 时以若依 {@code POST /login} 捕 token 建 Bearer 会话
 * （utopia 注册流在 jknow 已随 auth 域退役）。
 */
public record Scenario(String name, String desc, Setup setup,
                       List<RegisterSetup> ensure, Map<String, String> vars, List<Step> steps) {
}
