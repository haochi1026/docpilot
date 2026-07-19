package com.internship.docpilot.service;

import com.internship.docpilot.repository.OutboxRepository;
import com.internship.docpilot.repository.DocumentRepository;
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
  private static final int BATCH_SIZE = 32;
  private final OutboxRepository outbox;
  private final ParseTaskPublisher publisher;
  private final DocumentRepository documents;
  private final MinioStorageService storage;
  private final EmbeddingService embeddings;
  private final String owner = "docpilot-" + UUID.randomUUID();

  public OutboxDispatcher(
      OutboxRepository o,
      ParseTaskPublisher p,
      DocumentRepository d,
      MinioStorageService s,
      EmbeddingService e) {
    outbox = o;
    publisher = p;
    documents = d;
    storage = s;
    embeddings = e;
  }

  @Scheduled(fixedDelay = 2000L)
  public void dispatch() {
    int recovered = outbox.recoverStaleSending(SENDING_LEASE_MINUTES);
    if (recovered > 0) log.warn("Recovered {} stale SENDING outbox messages", recovered);
    for (Map<String, Object> row : outbox.nextBatch(BATCH_SIZE)) {
      Long id = ((Number) row.get("id")).longValue();
      if (!outbox.claim(id, owner)) continue;
      try {
        Long documentId = ((Number) row.get("aggregate_id")).longValue();
        String eventType = String.valueOf(row.get("event_type"));
        if ("DOCUMENT_DELETE".equals(eventType)) {
          String objectKey = documents.objectKeyAny(documentId);
          if (objectKey != null) storage.remove(objectKey);
          embeddings.deleteDocument(documentId);
        } else {
          publisher.publish(documentId);
        }
        outbox.sent(id);
      } catch (Exception e) {
        int count = ((Number) row.get("retry_count")).intValue() + 1;
        outbox.retry(id, count, e.getMessage());
        log.warn("Outbox {} publish failed, retry {}", id, count, e);
      }
    }
  }
}
