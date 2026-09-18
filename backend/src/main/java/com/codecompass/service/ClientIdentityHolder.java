package com.codecompass.service;

/**
 * 请求级匿名身份上下文：拦截器从 Cookie 解析并写入，afterCompletion 清除。
 * ThreadLocal 使「谁在请求」对业务层透明，且单元测试可直接 set 注入身份。
 */
public final class ClientIdentityHolder {

    private static final ThreadLocal<String> CLIENT_ID = new ThreadLocal<>();

    private ClientIdentityHolder() {
    }

    public static void set(String clientId) {
        CLIENT_ID.set(clientId);
    }

    public static String get() {
        return CLIENT_ID.get();
    }

    public static void clear() {
        CLIENT_ID.remove();
    }
}
