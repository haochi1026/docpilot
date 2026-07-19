package com.internship.docpilot.service;

import com.internship.docpilot.repository.DocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class LexicalIndexBackfillService {
  private static final Logger log = LoggerFactory.getLogger(LexicalIndexBackfillService.class);
  private final DocumentRepository documents;

  public LexicalIndexBackfillService(DocumentRepository documents) {
    this.documents = documents;
  }

  @Scheduled(initialDelay = 5000L, fixedDelay = 30000L)
  public void backfill() {
    int indexed = documents.backfillMissingLexicalTerms(500);
    if (indexed > 0) {
      log.info("Backfilled knowledge-base BM25 terms for {} chunks", indexed);
    }
  }
}
