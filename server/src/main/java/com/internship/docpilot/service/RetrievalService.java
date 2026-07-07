package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
  private final DocumentRepository documents;
  private final EmbeddingService embeddings;

  public RetrievalService(DocumentRepository d, EmbeddingService embeddings) {
    documents = d;
    this.embeddings = embeddings;
  }

  public List<SearchHit> search(Long kbId, String question, int topK) {
    Set<String> q = grams(question);
    List<SearchHit> all = documents.candidates(kbId, embeddings.getModel());
    double[] questionVector = null;
    if (embeddings.enabled()) {
      try {
        questionVector = embeddings.embed(question);
      } catch (Exception e) {
        log.warn("Embedding retrieval unavailable, falling back to lexical search: {}", e.getMessage());
      }
    }
    for (SearchHit h : all) {
      Set<String> g = grams(h.getContent());
      int hit = 0;
      for (String token : q) if (g.contains(token)) hit++;
      double lexical = q.isEmpty() ? 0 : (double) hit / q.size();
      double[] chunkVector = embeddings.parse(h.getEmbedding());
      double score =
          questionVector != null && chunkVector != null
              ? cosine(questionVector, chunkVector) * 0.75 + lexical * 0.25
              : lexical;
      h.setScore(score);
    }
    Collections.sort(all, Comparator.comparingDouble(SearchHit::getScore).reversed());
    List<SearchHit> out = new ArrayList<SearchHit>();
    for (SearchHit h : all) {
      if (out.size() >= topK) break;
      if (h.getScore() > 0 || out.isEmpty()) out.add(h);
    }
    return out;
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

  private Set<String> grams(String text) {
    String s = text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
    Set<String> set = new HashSet<String>();
    for (int i = 0; i < s.length(); i++) {
      set.add(String.valueOf(s.charAt(i)));
      if (i + 1 < s.length()) set.add(s.substring(i, i + 2));
    }
    return set;
  }
}
