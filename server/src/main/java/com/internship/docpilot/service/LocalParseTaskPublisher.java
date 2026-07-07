package com.internship.docpilot.service;

import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "app.queue.mode", havingValue = "local", matchIfMissing = true)
public class LocalParseTaskPublisher implements ParseTaskPublisher {
  private final Executor executor;
  private final DocumentParseService parser;

  public LocalParseTaskPublisher(@Qualifier("docTaskExecutor") Executor e, DocumentParseService p) {
    executor = e;
    parser = p;
  }

  public void publish(Long id) {
    executor.execute(() -> parser.parse(id));
  }
}
