package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @Auther: zzzlew
 * @Date: 2025/10/22 - 10 - 22 - 0:17
 * @Description: com.hmdp.config
 * @version: 1.0
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Autowired
    LoginInterceptor loginInterceptor;
    @Autowired
    RefreshTokenInterceptor refreshTokenInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // token刷新拦截器
        registry.addInterceptor(refreshTokenInterceptor).addPathPatterns("/**").order(0);
        // 登录拦截器
        registry.addInterceptor(loginInterceptor).excludePathPatterns("/shop/**", "/voucher/**", "/shop-type/**",
            "/upload/**", "/blog/hot", "/user/code", "/user/login").order(1);
    }
}
