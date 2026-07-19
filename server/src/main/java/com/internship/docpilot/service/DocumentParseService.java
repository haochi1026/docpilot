package com.internship.docpilot.service;

import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.OutboxRepository;
import com.internship.docpilot.model.DocumentChunkDraft;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class DocumentParseService {
  private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
  private final DocumentRepository documents;
  private final OutboxRepository outbox;
  private final MinioStorageService storage;
  private final EmbeddingService embeddings;
  private final DocumentTextExtractor extractor;
  private final SemanticChunker chunker;
  private final ScheduledExecutorService leaseExecutor =
      Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "docpilot-parse-lease");
        thread.setDaemon(true);
        return thread;
      });

  public DocumentParseService(
      DocumentRepository d,
      OutboxRepository outbox,
      MinioStorageService s,
      EmbeddingService embeddings,
      DocumentTextExtractor extractor,
      SemanticChunker chunker) {
    documents = d;
    this.outbox = outbox;
    storage = s;
    this.embeddings = embeddings;
    this.extractor = extractor;
    this.chunker = chunker;
  }

  @Scheduled(initialDelay = 60000L, fixedDelay = 60000L)
  public void recoverStaleProcessing() {
    for (Long documentId : documents.staleProcessingIds(10)) {
      if (documents.releaseStaleProcessing(documentId, 10)) {
        outbox.create(documentId);
        log.warn("Recovered stale PROCESSING document {}", documentId);
      }
    }
    for (Long documentId : documents.stalePendingIds(2)) {
      if (!outbox.hasOpenForDocument(documentId)) {
        outbox.create(documentId);
        log.warn("Recovered stale PENDING document {}", documentId);
      }
    }
  }

  public void parse(Long documentId) {
    if (!documents.claim(documentId)) return;
    ScheduledFuture<?> heartbeat = null;
    AtomicBoolean leaseLost = new AtomicBoolean(false);
    try {
      Map<String, Object> d = documents.details(documentId);
      if (d == null) return;
      String jobId = String.valueOf(d.get("parse_job_id"));
      if (!documents.touchLease(documentId, jobId, 30)) {
        throw new IllegalStateException("document parse lease not acquired");
      }
      heartbeat = leaseExecutor.scheduleAtFixedRate(
          () -> {
            if (!documents.touchLease(documentId, jobId, 30)) leaseLost.set(true);
          },
          15,
          15,
          TimeUnit.SECONDS);
      String key = String.valueOf(d.get("object_key"));
      String originalName = String.valueOf(d.get("original_name"));
      DocumentTextExtractor.ExtractionResult extraction;
      try (InputStream in = storage.open(key)) {
        extraction = extractor.extract(in, originalName);
      }
      ensureLease(documentId, jobId, leaseLost);
      int extractedCharacters =
          extraction.getPages().stream().mapToInt(page -> page.getText().length()).sum();
      if (extractedCharacters < 10) {
        throw new IllegalArgumentException("未提取到足够文本，OCR 后仍无法形成可索引内容");
      }
      List<DocumentChunkDraft> chunks = chunker.chunk(extraction.getPages());
      if (chunks.isEmpty()) throw new IllegalArgumentException("文档未形成有效语义切片");
      ensureLease(documentId, jobId, leaseLost);
      Long kbId = ((Number) d.get("kb_id")).longValue();
      int revisionNo = ((Number) d.get("version")).intValue();
      documents.replaceChunks(documentId, kbId, chunks, jobId);
      boolean pdf = originalName.toLowerCase().endsWith(".pdf");
      String extractionMethod =
          extraction.isOcrUsed() ? "PDFBOX_OCR" : (pdf ? "PDFBOX" : "TIKA");
      if (embeddings.enabled()) {
        EmbeddingService.DocumentIndexDraft vectorDraft = null;
        try {
          ensureLease(documentId, jobId, leaseLost);
          vectorDraft = embeddings.indexDocumentRevision(documentId, revisionNo, jobId);
          int indexed = vectorDraft.getCount();
          ensureLease(documentId, jobId, leaseLost);
          if (indexed != chunks.size()) {
            throw new IllegalStateException(
                "向量索引数量不完整：expected=" + chunks.size() + ", actual=" + indexed);
          }
          embeddings.promoteDocumentRevision(documentId, vectorDraft);
          log.info("Document {} indexed into {} embedding vectors", documentId, indexed);
        } catch (Exception embeddingError) {
          // Keep the previous public vectors until the new revision's complete
          // shadow set has been promoted.
          embeddings.discardDocumentRevision(documentId, vectorDraft);
          documents.partial(
              documentId,
              jobId,
              embeddingError.getMessage(),
              extractionMethod,
              extraction.getPages().size());
          log.warn("Document {} text parsed but vector indexing failed", documentId, embeddingError);
          return;
        }
      }
      documents.embeddingModel(documentId, jobId, embeddings.enabled() ? embeddings.getModel() : null);
      documents.success(
          documentId,
          jobId,
          embeddings.enabled() ? "READY" : "DISABLED",
          extractionMethod,
          extraction.getPages().size());
      log.info("Document {} parsed into {} chunks", documentId, chunks.size());
    } catch (Exception e) {
      documents.failed(
          documentId, dJobId(documentId), e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      log.warn("Document {} parse failed", documentId, e);
    } finally {
      if (heartbeat != null) heartbeat.cancel(true);
    }
  }

  private void ensureLease(Long documentId, String jobId, AtomicBoolean leaseLost) {
    if (leaseLost.get() || !documents.touchLease(documentId, jobId, 30)) {
      throw new IllegalStateException("document parse lease lost");
    }
  }

  private String dJobId(Long documentId) {
    Map<String, Object> current = documents.details(documentId);
    if (current == null || current.get("parse_job_id") == null) return null;
    return String.valueOf(current.get("parse_job_id"));
  }

}
