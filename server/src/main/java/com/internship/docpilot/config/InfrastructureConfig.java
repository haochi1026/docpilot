package com.internship.docpilot.config;

import java.util.concurrent.Executor;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class InfrastructureConfig {
  @Bean(destroyMethod = "shutdown")
  public RedissonClient redisson(
      @Value("${spring.redis.host}") String host, @Value("${spring.redis.port}") int port) {
    Config config = new Config();
    config
        .useSingleServer()
        .setAddress("redis://" + host + ":" + port)
        .setConnectionMinimumIdleSize(1)
        .setConnectionPoolSize(8);
    return Redisson.create(config);
  }

  @Bean("docTaskExecutor")
  public Executor docTaskExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(2);
    e.setMaxPoolSize(4);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("doc-task-");
    e.initialize();
    return e;
  }

  @Bean("chatExecutor")
  public Executor chatExecutor() {
    ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
    e.setCorePoolSize(4);
    e.setMaxPoolSize(8);
    e.setQueueCapacity(100);
    e.setThreadNamePrefix("chat-");
    e.initialize();
    return e;
  }
}
