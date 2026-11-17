package tech.seexun.jknow.framework.security.handle;

import java.io.IOException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import com.alibaba.fastjson2.JSON;
import tech.seexun.jknow.common.constant.Constants;
import tech.seexun.jknow.common.core.domain.AjaxResult;
import tech.seexun.jknow.common.core.domain.model.LoginUser;
import tech.seexun.jknow.common.utils.MessageUtils;
import tech.seexun.jknow.common.utils.ServletUtils;
import tech.seexun.jknow.common.utils.StringUtils;
import tech.seexun.jknow.framework.manager.AsyncManager;
import tech.seexun.jknow.framework.manager.factory.AsyncFactory;
import tech.seexun.jknow.framework.web.service.TokenService;

/**
 * 自定义退出处理类 返回成功
 * 
 * @author jknow
 */
@Configuration
public class LogoutSuccessHandlerImpl implements LogoutSuccessHandler
{
    @Autowired
    private TokenService tokenService;

    /**
     * 退出处理
     * 
     * @return
     */
    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException
    {
        LoginUser loginUser = tokenService.getLoginUser(request);
        if (StringUtils.isNotNull(loginUser))
        {
            String userName = loginUser.getUsername();
            // 删除用户缓存记录
            tokenService.delLoginUser(loginUser.getToken());
            // 记录用户退出日志
            AsyncManager.me().execute(AsyncFactory.recordLogininfor(userName, Constants.LOGOUT, MessageUtils.message("user.logout.success")));
        }
        ServletUtils.renderString(response, JSON.toJSONString(AjaxResult.success(MessageUtils.message("user.logout.success"))));
    }
}
