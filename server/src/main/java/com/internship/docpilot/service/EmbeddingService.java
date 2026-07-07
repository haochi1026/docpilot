package com.internship.docpilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.internship.docpilot.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class EmbeddingService {
  private final DocumentRepository documents;
  private final ModelSettingsService settings;
  private final RestTemplate http = new RestTemplate();
  private final ObjectMapper mapper = new ObjectMapper();

  public EmbeddingService(DocumentRepository documents, ModelSettingsService settings) {
    this.documents = documents;
    this.settings = settings;
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
    return documents.countEmbeddings(kbId, getModel());
  }

  public double[] embed(String text) throws Exception {
    List<String> input = new ArrayList<String>();
    input.add(text);
    return request(input).get(0);
  }

  public int indexDocument(Long documentId) throws Exception {
    if (!enabled()) return 0;
    String model = getModel();
    List<Map<String, Object>> chunks = documents.chunksForDocument(documentId);
    int indexed = 0;
    for (int offset = 0; offset < chunks.size(); offset += 16) {
      int end = Math.min(chunks.size(), offset + 16);
      List<String> texts = new ArrayList<String>();
      for (int i = offset; i < end; i++) texts.add(String.valueOf(chunks.get(i).get("content")));
      List<double[]> vectors = request(texts);
      for (int i = 0; i < vectors.size(); i++) {
        Long chunkId = ((Number) chunks.get(offset + i).get("id")).longValue();
        documents.saveEmbedding(chunkId, model, mapper.writeValueAsString(vectors.get(i)));
        indexed++;
      }
    }
    return indexed;
  }

  public int reindexKnowledgeBase(Long kbId) throws Exception {
    int total = 0;
    for (Long documentId : documents.successfulDocumentIds(kbId)) {
      total += indexDocument(documentId);
    }
    return total;
  }

  public double[] parse(String value) {
    if (value == null || value.isEmpty()) return null;
    try {
      return mapper.readValue(value, double[].class);
    } catch (Exception ignored) {
      return null;
    }
  }

  private List<double[]> request(List<String> input) throws Exception {
    if (!enabled()) throw new IllegalStateException("Embedding mode is not enabled");
    ModelSettingsService.Snapshot config = settings.current();
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("model", config.getEmbeddingModel());
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
