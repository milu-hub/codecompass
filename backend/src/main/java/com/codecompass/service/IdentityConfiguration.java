package com.codecompass.service;

import java.time.Clock;

import com.codecompass.persistence.AnonymousUserRepository;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** F5 装配。 */
@Configuration
public class IdentityConfiguration {

    @Bean
    public IdentityService identityService(AnonymousUserRepository repository, Clock clock) {
        return new IdentityService(repository, clock);
    }
}
