package com.hmdp.utils;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @Auther: zzzlew
 * @Date: 2025/10/22 - 10 - 22 - 0:11
 * @Description: com.hmdp.utils
 * @version: 1.0
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (UserHolder.getUser() == null) {
            // 不存在，进行拦截，返回 401 状态码
            response.setStatus(401);
            return false;
        }
        // 有用户，放行
        return true;
    }
}
