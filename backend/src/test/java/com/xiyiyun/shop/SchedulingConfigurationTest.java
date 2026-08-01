package com.xiyiyun.shop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class SchedulingConfigurationTest {
    @Test
    void configuresEnoughSchedulerThreadsForIndependentWorkers() throws IOException {
        MockEnvironment environment = new MockEnvironment();
        new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.task.scheduling.pool.size", Integer.class))
            .isEqualTo(5);
    }
}
