package com.codecompass.service;

import java.util.List;

/**
 * LLM 配置服务 —— 「获取当前 LLM 配置」这唯一一步的抽象。
 *
 * <p>问答链路只依赖本接口；MVP 实现是 {@link InMemoryLlmConfigService}（从
 * application.yml 读单配置）。未来换成数据库存储 / 多配置管理时，接口不变、
 * 业务层与装配层零改动 —— 这是多配置的预留扩展点。
 *
 * <p><b>注意</b>：LLM 客户端是启动时用默认配置构建的单例；即使未来 switchDefault
 * 在内存里改了默认，正在服务的客户端也不会变。运行时切换要真正生效，必须让客户端
 * 改为「按请求解析配置」——那是多配置版本的真正改动点，不属本接口的职责。
 */
public interface LlmConfigService {

    /** 全部配置。MVP 恒为单条。 */
    List<LlmConfig> list();

    /** 当前生效配置 —— 问答链路的唯一消费点。 */
    LlmConfig getDefault();

    /**
     * 切换默认配置。
     *
     * @throws IllegalArgumentException         未知 id
     * @throws UnsupportedOperationException   MVP 单配置版本不支持运行时切换
     */
    LlmConfig switchDefault(String id);

    /**
     * 保存（新建或更新）配置。
     *
     * @throws UnsupportedOperationException MVP 单配置版本配置以 application.yml 为准
     */
    LlmConfig save(LlmConfig config);

    /**
     * 删除配置。
     *
     * @throws UnsupportedOperationException MVP 单配置版本配置以 application.yml 为准
     */
    void delete(String id);
}
