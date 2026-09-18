package com.codecompass.repo;

/**
 * 克隆结果，对应 SCHEMA.md 的 {@code CloneResult}。
 *
 * <p>不变量：成功时 {@code errorMessage} 为 null；失败时 {@code localPath} 为 null ——
 * 失败绝不能返回半成品路径，否则调用方可能拿一个不完整的仓库继续走后面的解析。
 *
 * <p>{@code commitSha} 是 T11 缓存 key 的身份锚（仓库版本）。取不到时为 null，
 * 下游（T11 缓存）自行降级为「不缓存」，这里不把克隆判失败。
 */
public record CloneResult(String localPath, boolean success, String errorMessage, String commitSha) {

    public static CloneResult ok(String localPath, String commitSha) {
        return new CloneResult(localPath, true, null, commitSha);
    }

    /** 无 sha 的成功结果 —— 仅服务既有调用点/夹具；新代码一律走 {@link #ok(String, String)}。 */
    public static CloneResult ok(String localPath) {
        return ok(localPath, null);
    }

    public static CloneResult fail(String errorMessage) {
        return new CloneResult(null, false, errorMessage, null);
    }
}
