package com.internship.docpilot.service;

import com.internship.docpilot.repository.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
  private static final int SENDING_LEASE_MINUTES = 5;
  private final OutboxRepository outbox;
  private final ParseTaskPublisher publisher;
  private final String owner = "docpilot-" + UUID.randomUUID();

  public OutboxDispatcher(OutboxRepository o, ParseTaskPublisher p) {
    outbox = o;
    publisher = p;
  }

  @Scheduled(fixedDelay = 2000L)
  public void dispatch() {
    int recovered = outbox.recoverStaleSending(SENDING_LEASE_MINUTES);
    if (recovered > 0) log.warn("Recovered {} stale SENDING outbox messages", recovered);
    Map<String, Object> row = outbox.next();
    if (row == null) return;
    Long id = ((Number) row.get("id")).longValue();
    if (!outbox.claim(id, owner)) return;
    try {
      publisher.publish(((Number) row.get("aggregate_id")).longValue());
      outbox.sent(id);
    } catch (Exception e) {
      int count = ((Number) row.get("retry_count")).intValue() + 1;
      outbox.retry(id, count);
      log.warn("Outbox {} publish failed, retry {}", id, count, e);
    }
  }
}
