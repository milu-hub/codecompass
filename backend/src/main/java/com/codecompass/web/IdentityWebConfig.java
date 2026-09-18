package com.codecompass.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册匿名身份拦截器（只拦 /api/**）。 */
@Configuration
public class IdentityWebConfig implements WebMvcConfigurer {

    private final ClientIdentityInterceptor interceptor;

    public IdentityWebConfig(ClientIdentityInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/**");
    }
}
