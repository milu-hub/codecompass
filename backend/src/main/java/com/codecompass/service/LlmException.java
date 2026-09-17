package com.codecompass.service;

/** LLM 通道故障：未配置、传输失败、响应不可用。控制器映射为 502。 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
