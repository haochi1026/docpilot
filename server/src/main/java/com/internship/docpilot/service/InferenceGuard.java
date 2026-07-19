package com.internship.docpilot.service;

import com.internship.docpilot.exception.BusinessException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class InferenceGuard {
  private final StringRedisTemplate redis;
  private final RPermitExpirableSemaphore distributed;
  private final int limit;
  private final long leaseSeconds;
  private final boolean localFallback;
  private final Semaphore local;

  public InferenceGuard(
      StringRedisTemplate redis,
      RedissonClient redisson,
      @Value("${app.inference.max-concurrency:3}") int max,
      @Value("${app.inference.per-user-per-minute:12}") int limit,
      @Value("${app.inference.lease-seconds:240}") long leaseSeconds,
      @Value("${app.inference.local-fallback:true}") boolean localFallback) {
    this.redis = redis;
    this.limit = limit;
    this.local = new Semaphore(max);
    this.leaseSeconds = Math.max(180, leaseSeconds);
    this.localFallback = localFallback;
    this.distributed = redisson.getPermitExpirableSemaphore("docpilot:inference:permits");
    try {
      this.distributed.trySetPermits(max);
    } catch (Exception ignored) {
    }
  }

  public Permit acquire(Long userId) {
    checkRate(userId);
    try {
      String id = distributed.tryAcquire(0, leaseSeconds, TimeUnit.SECONDS);
      if (id == null) throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "推理服务繁忙，请稍后重试");
      return new Permit(id, false);
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      if (!localFallback || !local.tryAcquire())
        throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "推理服务繁忙，请稍后重试");
      return new Permit(null, true);
    }
  }

  private void checkRate(Long uid) {
    String key =
        "docpilot:rate:"
            + uid
            + ":"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"));
    String script =
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],70) end; return n";
    try {
      Long n =
          redis.execute(
              new DefaultRedisScript<Long>(script, Long.class), Collections.singletonList(key));
      if (n != null && n > limit)
        throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁，请一分钟后重试");
    } catch (BusinessException e) {
      throw e;
    } catch (Exception ignored) {
    }
  }

  public void release(Permit p) {
    if (p == null) return;
    if (p.local) local.release();
    else
      try {
        distributed.release(p.id);
      } catch (Exception ignored) {
      }
  }

  public static class Permit {
    private final String id;
    private final boolean local;

    Permit(String id, boolean local) {
      this.id = id;
      this.local = local;
    }
  }
}
