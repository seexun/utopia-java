package tech.seexun.jknow.web.knowledge.auth;

/**
 * 旧 utopia SessionFilter 的<b>属性名契约</b>（P1 任务 7 身份桥接）：
 * 过滤逻辑（utopia_token 凭据、users 行校验、401 收口）已随 auth 域舍弃——
 * 鉴权由若依 JwtAuthenticationTokenFilter 持有，身份/审计上下文由
 * {@link ClientContextBridgeFilter} 每请求装配（LoginUser → UserRow / ClientContext）。
 * 业务侧约 30 处 {@code request.getAttribute(USER_ATTR/CLIENT_ATTR)} 引用零改动，
 * 故保留本类仅承载两个属性名常量（不再是 Filter）。
 */
public final class SessionFilter {

    /** 用户身份属性（值 UserRow：id=sys_user.utopia_uuid，email=user_name，display_name=nick_name）。 */
    public static final String USER_ATTR = "utopia.authUser";

    /** 审计上下文属性（值 ClientContext：ip + user-agent，匿名请求也装配）。 */
    public static final String CLIENT_ATTR = "utopia.clientContext";

    private SessionFilter() {
    }
}
