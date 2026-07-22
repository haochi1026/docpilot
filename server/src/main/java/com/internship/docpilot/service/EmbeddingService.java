package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.VectorStoreRepository;
import com.internship.docpilot.config.HttpClientFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class EmbeddingService {
  private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);
  private final DocumentRepository documents;
  private final ModelSettingsService settings;
  private final VectorStoreRepository vectors;
  private final RestTemplate http;
  private final ObjectMapper mapper = new ObjectMapper();
  private final ReentrantLock rebuildLock = new ReentrantLock();

  public EmbeddingService(
      DocumentRepository documents,
      ModelSettingsService settings,
      VectorStoreRepository vectors) {
    this(documents, settings, vectors, 2000, 60000);
  }

  @Autowired
  public EmbeddingService(
      DocumentRepository documents,
      ModelSettingsService settings,
      VectorStoreRepository vectors,
      @Value("${app.embedding.connect-timeout-ms:2000}") int connectTimeoutMs,
      @Value("${app.embedding.read-timeout-ms:60000}") int readTimeoutMs) {
    this.documents = documents;
    this.settings = settings;
    this.vectors = vectors;
    this.http = HttpClientFactory.bounded(connectTimeoutMs, readTimeoutMs);
  }

  public boolean enabled() {
    return settings.current().embeddingEnabled();
  }

  public String getMode() {
    return settings.current().getEmbeddingMode();
  }

  public String getModel() {
    return settings.current().getEmbeddingModel();
  }

  public int countChunks(Long kbId) {
    return documents.countChunks(kbId);
  }

  public int countEmbeddings(Long kbId) {
    return vectors.count(kbId, documents.activeEmbeddingModel(kbId, getModel()));
  }

  public double[] embed(String text) throws Exception {
    return embed(text, getModel());
  }

  public double[] embed(String text, String model) throws Exception {
    List<String> input = new ArrayList<String>();
    input.add(text);
    return request(input, model).get(0);
  }

  public int indexDocument(Long documentId) throws Exception {
    if (!enabled()) return 0;
    String model = getModel();
    return indexDocument(documentId, model, model, null);
  }

  private int indexDocument(
      Long documentId, String embeddingModel, String storageModel, Integer revisionNo)
      throws Exception {
    Long kbId = documents.kbId(documentId);
    if (kbId == null) throw new IllegalArgumentException("Document does not exist");
    List<Map<String, Object>> chunks = revisionNo == null
        ? documents.chunksForDocument(documentId)
        : documents.chunksForDocumentRevision(documentId, revisionNo);
    int indexed = 0;
    for (int offset = 0; offset < chunks.size(); offset += 16) {
      int end = Math.min(chunks.size(), offset + 16);
      List<String> texts = new ArrayList<String>();
      for (int i = offset; i < end; i++) texts.add(String.valueOf(chunks.get(i).get("content")));
      List<double[]> vectors = request(texts, embeddingModel);
      for (int i = 0; i < vectors.size(); i++) {
        Long chunkId = ((Number) chunks.get(offset + i).get("id")).longValue();
        this.vectors.save(chunkId, documentId, kbId, storageModel, vectors.get(i));
        indexed++;
      }
    }
    return indexed;
  }

  public DocumentIndexDraft indexDocumentRevision(
      Long documentId, int revisionNo, String jobId) throws Exception {
    if (!enabled()) return new DocumentIndexDraft("", getModel(), 0);
    String targetModel = getModel();
    String shadowModel = shadowName(targetModel);
    vectors.deleteDocumentModel(documentId, shadowModel);
    int indexed;
    try {
      indexed = indexDocument(documentId, targetModel, shadowModel, revisionNo);
      if (vectors.countDocumentModel(documentId, shadowModel) != indexed) {
        throw new IllegalStateException("Document shadow vector count mismatch");
      }
    } catch (Exception error) {
      vectors.deleteDocumentModel(documentId, shadowModel);
      throw error;
    }
    return new DocumentIndexDraft(shadowModel, targetModel, indexed);
  }

  public void promoteDocumentRevision(Long documentId, DocumentIndexDraft draft) {
    if (enabled()) {
      vectors.promoteDocumentModel(documentId, draft.shadowModel, draft.targetModel);
    }
  }

  public void discardDocumentRevision(Long documentId, DocumentIndexDraft draft) {
    if (enabled() && draft != null && !draft.shadowModel.isEmpty()) {
      vectors.deleteDocumentModel(documentId, draft.shadowModel);
    }
  }

  public int reindexKnowledgeBase(Long kbId) throws Exception {
    return rebuildKnowledgeBase(kbId, getModel());
  }

  public int migrateKnowledgeBase(Long kbId) throws Exception {
    if (!enabled()) throw new IllegalStateException("Embedding mode is not enabled");
    return rebuildKnowledgeBase(kbId, getModel());
  }

  private int rebuildKnowledgeBase(Long kbId, String targetModel) throws Exception {
    if (!enabled()) throw new IllegalStateException("Embedding mode is not enabled");
    rebuildLock.lockInterruptibly();
    try {
      return rebuildKnowledgeBaseLocked(kbId, targetModel);
    } finally {
      rebuildLock.unlock();
    }
  }

  private int rebuildKnowledgeBaseLocked(Long kbId, String targetModel) throws Exception {
    int expectedBeforeRebuild = documents.countChunks(kbId);
    String previousModel = documents.activeEmbeddingModel(kbId, targetModel);
    boolean completeActiveIndex =
        expectedBeforeRebuild > 0
            && vectors.count(kbId, previousModel) == expectedBeforeRebuild;
    String shadowModel = shadowName(targetModel);
    documents.markKnowledgeBaseReindexing(kbId);
    int total = 0;
    try {
      for (Long documentId : documents.successfulDocumentIds(kbId)) {
        int expected = documents.chunksForDocument(documentId).size();
        int indexed = indexDocument(documentId, targetModel, shadowModel, null);
        if (indexed != expected) {
          throw new IllegalStateException("Embedding migration count mismatch for document " + documentId);
        }
        total += indexed;
      }
      if (vectors.count(kbId, shadowModel) != total) {
        throw new IllegalStateException("Shadow vector count mismatch before promotion");
      }
      vectors.promoteKnowledgeBaseModel(kbId, shadowModel, targetModel);
      documents.activateEmbeddingModel(kbId, targetModel);
    } catch (Exception error) {
      vectors.deleteKnowledgeBaseModel(kbId, shadowModel);
      if (completeActiveIndex) {
        documents.markKnowledgeBaseReindexRolledBack(kbId, error.getMessage());
      } else {
        documents.markKnowledgeBaseReindexFailed(kbId, error.getMessage());
      }
      throw error;
    }
    return total;
  }

  /** Periodically repairs metadata/vector divergence without blocking application startup. */
  @Scheduled(initialDelay = 15000L, fixedDelay = 300000L)
  public void reconcileIndexes() {
    if (!enabled() || !vectors.enabled() || rebuildLock.isLocked()) return;
    String targetModel = getModel();
    for (Long kbId : documents.knowledgeBaseIdsWithActiveChunks()) {
      int expected = documents.countChunks(kbId);
      String activeModel = documents.activeEmbeddingModel(kbId, targetModel);
      int actual = vectors.count(kbId, activeModel);
      if (expected > 0 && expected == actual && targetModel.equals(activeModel)) continue;
      String reason =
          "expected="
              + expected
              + ", indexed="
              + actual
              + ", activeModel="
              + activeModel
              + ", targetModel="
              + targetModel;
      documents.markKnowledgeBaseEmbeddingStale(kbId, reason);
      try {
        rebuildKnowledgeBase(kbId, targetModel);
        log.info("Reconciled vector index for knowledge base {} ({})", kbId, reason);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        return;
      } catch (Exception error) {
        log.warn("Automatic vector reconciliation failed for knowledge base {}", kbId, error);
      }
    }
  }

  public void deleteDocument(Long documentId) {
    vectors.deleteDocument(documentId);
  }

  @Scheduled(initialDelay = 3600000L, fixedDelay = 3600000L)
  public void cleanupOrphanShadows() {
    vectors.cleanupOrphanShadows(24);
  }

  private String shadowName(String targetModel) {
    String prefix = targetModel == null ? "embedding" : targetModel;
    if (prefix.length() > 72) prefix = prefix.substring(0, 72);
    return prefix + "#shadow#" + UUID.randomUUID();
  }

  public static class DocumentIndexDraft {
    private final String shadowModel;
    private final String targetModel;
    private final int count;

    DocumentIndexDraft(String shadowModel, String targetModel, int count) {
      this.shadowModel = shadowModel;
      this.targetModel = targetModel;
      this.count = count;
    }

    public int getCount() {
      return count;
    }

    public String getTargetModel() {
      return targetModel;
    }
  }

  private List<double[]> request(List<String> input) throws Exception {
    return request(input, getModel());
  }

  private List<double[]> request(List<String> input, String model) throws Exception {
    if (!enabled()) throw new IllegalStateException("Embedding mode is not enabled");
    ModelSettingsService.Snapshot config = settings.current();
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("model", model);
    body.put("input", input);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    String apiKey = settings.getEmbeddingApiKey();
    if (apiKey != null && !apiKey.trim().isEmpty()) headers.setBearerAuth(apiKey);
    ResponseEntity<String> response =
        http.exchange(
            config.getEmbeddingBaseUrl() + "/embeddings",
            HttpMethod.POST,
            new HttpEntity<Map<String, Object>>(body, headers),
            String.class);
    JsonNode data = mapper.readTree(response.getBody()).path("data");
    List<double[]> result = new ArrayList<double[]>();
    for (JsonNode item : data) {
      result.add(mapper.treeToValue(item.path("embedding"), double[].class));
    }
    if (result.size() != input.size()) {
      throw new IllegalStateException("Embedding service returned an unexpected vector count");
    }
    return result;
  }
}
