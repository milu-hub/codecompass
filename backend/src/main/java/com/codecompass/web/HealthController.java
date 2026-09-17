package com.codecompass.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.codecompass.web.dto.HealthResponse;

/**
 * 存活探针。T0 唯一接口，不含任何业务逻辑。
 *
 * 刻意不用 spring-boot-starter-actuator 的 /actuator/health：
 * T0 约束是"依赖尽量少，只引一个 web starter"，actuator 是额外依赖。
 */
@RestController
public class HealthController {

    private final String serviceName;
    private final String version;

    public HealthController(
            @Value("${spring.application.name}") String serviceName,
            @Value("${codecompass.service.version}") String version) {
        this.serviceName = serviceName;
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return HealthResponse.up(serviceName, version);
    }
}
