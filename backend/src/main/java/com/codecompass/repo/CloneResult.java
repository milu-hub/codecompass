package com.codecompass.repo;

/**
 * 克隆结果，对应 SCHEMA.md 的 {@code CloneResult}。
 *
 * 不变量：成功时 {@code errorMessage} 为 null；失败时 {@code localPath} 为 null ——
 * 失败绝不能返回半成品路径，否则调用方可能拿一个不完整的仓库继续走后面的解析。
 */
public record CloneResult(String localPath, boolean success, String errorMessage) {

    public static CloneResult ok(String localPath) {
        return new CloneResult(localPath, true, null);
    }

    public static CloneResult fail(String errorMessage) {
        return new CloneResult(null, false, errorMessage);
    }
}
