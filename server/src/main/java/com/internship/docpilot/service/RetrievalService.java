package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
  private static final double K1 = 1.5;
  private static final double B = 0.75;

  private final DocumentRepository documents;
  private final EmbeddingService embeddings;

  public RetrievalService(DocumentRepository documents, EmbeddingService embeddings) {
    this.documents = documents;
    this.embeddings = embeddings;
  }

  public List<SearchHit> search(Long kbId, String question, int topK) {
    List<String> queryTerms = grams(question);
    Set<String> querySet = new HashSet<String>(queryTerms);
    List<SearchHit> candidates = documents.candidates(kbId, embeddings.getModel());
    List<List<String>> docTerms = new ArrayList<List<String>>();
    Map<String, Integer> documentFrequency = new HashMap<String, Integer>();
    int totalLength = 0;

    for (SearchHit hit : candidates) {
      List<String> terms = grams(hit.getContent());
      docTerms.add(terms);
      totalLength += terms.size();
      Set<String> unique = new HashSet<String>(terms);
      for (String term : querySet) {
        if (unique.contains(term)) {
          documentFrequency.put(term, documentFrequency.getOrDefault(term, 0) + 1);
        }
      }
    }

    double avgLength = candidates.isEmpty() ? 1.0 : Math.max(1.0, (double) totalLength / candidates.size());
    double[] questionVector = null;
    if (embeddings.enabled()) {
      try {
        questionVector = embeddings.embed(question);
      } catch (Exception e) {
        log.warn("Embedding retrieval unavailable, falling back to lexical search: {}", e.getMessage());
      }
    }

    for (int i = 0; i < candidates.size(); i++) {
      SearchHit hit = candidates.get(i);
      double lexical =
          bm25(querySet, docTerms.get(i), documentFrequency, candidates.size(), avgLength);
      double normalizedLexical = lexical <= 0 ? 0 : lexical / (lexical + 8.0);
      double[] chunkVector = embeddings.parse(hit.getEmbedding());
      double score =
          questionVector != null && chunkVector != null
              ? cosine(questionVector, chunkVector) * 0.75 + normalizedLexical * 0.25
              : normalizedLexical;
      hit.setScore(score);
    }

    Collections.sort(candidates, Comparator.comparingDouble(SearchHit::getScore).reversed());
    List<SearchHit> out = new ArrayList<SearchHit>();
    for (SearchHit hit : candidates) {
      if (out.size() >= topK) break;
      if (hit.getScore() > 0 || out.isEmpty()) out.add(hit);
    }
    return out;
  }

  private double bm25(
      Set<String> queryTerms,
      List<String> documentTerms,
      Map<String, Integer> documentFrequency,
      int documentCount,
      double avgLength) {
    if (queryTerms.isEmpty() || documentTerms.isEmpty()) return 0;
    Map<String, Integer> termFrequency = new HashMap<String, Integer>();
    for (String term : documentTerms) {
      if (queryTerms.contains(term)) termFrequency.put(term, termFrequency.getOrDefault(term, 0) + 1);
    }
    double score = 0;
    for (String term : queryTerms) {
      int tf = termFrequency.getOrDefault(term, 0);
      if (tf == 0) continue;
      int df = documentFrequency.getOrDefault(term, 0);
      double idf = Math.log(1 + (documentCount - df + 0.5) / (df + 0.5));
      double denominator = tf + K1 * (1 - B + B * documentTerms.size() / avgLength);
      score += idf * (tf * (K1 + 1)) / denominator;
    }
    return score;
  }

  private double cosine(double[] a, double[] b) {
    if (a.length == 0 || a.length != b.length) return 0;
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0 || normB == 0) return 0;
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private List<String> grams(String text) {
    String normalized = text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    List<String> result = new ArrayList<String>();
    for (int i = 0; i < normalized.length(); i++) {
      result.add(String.valueOf(normalized.charAt(i)));
      if (i + 1 < normalized.length()) result.add(normalized.substring(i, i + 2));
    }
    return result;
  }
}
