package com.codecompass.web;

import java.util.Arrays;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.codecompass.service.ClientIdentityHolder;
import com.codecompass.service.IdentityService;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * F5 匿名身份拦截器：解析 cc_client_id Cookie（无则生成并写 30 天 HttpOnly Cookie），
 * 把 clientId 写入 {@link ClientIdentityHolder}，请求结束清除。
 */
@Component
public class ClientIdentityInterceptor implements HandlerInterceptor {

    static final String COOKIE_NAME = "cc_client_id";
    private static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30;

    private final IdentityService identityService;

    public ClientIdentityInterceptor(IdentityService identityService) {
        this.identityService = identityService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String existing = readCookie(request);
        String clientId = identityService.resolve(existing);
        if (existing == null || existing.isBlank()) {
            Cookie cookie = new Cookie(COOKIE_NAME, clientId);
            cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
            cookie.setHttpOnly(true);
            cookie.setPath("/");
            response.addCookie(cookie);
        }
        ClientIdentityHolder.set(clientId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        ClientIdentityHolder.clear();
    }

    private static String readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        return Arrays.stream(cookies)
                .filter(cookie -> COOKIE_NAME.equals(cookie.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
