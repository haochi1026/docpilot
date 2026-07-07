package com.internship.docpilot.service;

import com.internship.docpilot.repository.OutboxRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class OutboxDispatcher {
  private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
  private final OutboxRepository outbox;
  private final ParseTaskPublisher publisher;

  public OutboxDispatcher(OutboxRepository o, ParseTaskPublisher p) {
    outbox = o;
    publisher = p;
  }

  @Scheduled(fixedDelay = 2000L)
  public void dispatch() {
    Map<String, Object> row = outbox.next();
    if (row == null) return;
    Long id = ((Number) row.get("id")).longValue();
    if (!outbox.claim(id)) return;
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
