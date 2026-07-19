package com.internship.docpilot.service;

import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.VectorStoreRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DocumentRevisionRetentionService {
  private static final Logger log =
      LoggerFactory.getLogger(DocumentRevisionRetentionService.class);
  private final DocumentRepository documents;
  private final VectorStoreRepository vectors;
  private final int retainCount;

  public DocumentRevisionRetentionService(
      DocumentRepository documents,
      VectorStoreRepository vectors,
      @Value("${app.document.revision-retention-count:3}") int retainCount) {
    this.documents = documents;
    this.vectors = vectors;
    this.retainCount = Math.max(1, retainCount);
  }

  @Scheduled(initialDelay = 3600000L, fixedDelay = 21600000L)
  public void cleanup() {
    int removed = 0;
    for (Map<String, Object> candidate :
        documents.historicalRevisionCandidates(retainCount, 100)) {
      Long documentId = ((Number) candidate.get("document_id")).longValue();
      int revisionNo = ((Number) candidate.get("revision_no")).intValue();
      List<Long> chunkIds = documents.chunkIdsForRevision(documentId, revisionNo);
      // External vectors are removed first. If this call fails, MySQL history
      // remains intact and the next scheduled run safely retries.
      vectors.deleteChunks(chunkIds);
      if (documents.deleteHistoricalRevision(documentId, revisionNo)) removed++;
    }
    if (removed > 0) log.info("Removed {} expired document revisions", removed);
  }
}
