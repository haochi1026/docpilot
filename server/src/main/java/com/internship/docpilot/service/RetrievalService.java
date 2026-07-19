package com.internship.docpilot.service;

import com.internship.docpilot.model.RetrievalCandidate;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.VectorStoreRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
  private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

  private final DocumentRepository documents;
  private final EmbeddingService embeddings;
  private final VectorStoreRepository vectors;
  private final TokenEstimator tokens;
  private final double minimumScore;
  private final double minimumLexicalScore;
  private final double minimumVectorScore;
  private final double lexicalWeight;
  private final double vectorWeight;
  private final int lexicalCandidateLimit;

  public RetrievalService(
      DocumentRepository documents,
      EmbeddingService embeddings,
      VectorStoreRepository vectors,
      TokenEstimator tokens,
      @Value("${app.retrieval.min-score:0.20}") double minimumScore,
      @Value("${app.retrieval.min-lexical-score:0.05}") double minimumLexicalScore,
      @Value("${app.retrieval.min-vector-score:0.45}") double minimumVectorScore,
      @Value("${app.retrieval.lexical-weight:0.35}") double lexicalWeight,
      @Value("${app.retrieval.vector-weight:0.65}") double vectorWeight,
      @Value("${app.retrieval.lexical-candidate-limit:160}") int lexicalCandidateLimit) {
    this.documents = documents;
    this.embeddings = embeddings;
    this.vectors = vectors;
    this.tokens = tokens;
    this.minimumScore = Math.max(0, Math.min(1, minimumScore));
    this.minimumLexicalScore = Math.max(0, Math.min(1, minimumLexicalScore));
    this.minimumVectorScore = Math.max(0, Math.min(1, minimumVectorScore));
    this.lexicalWeight = Math.max(0, lexicalWeight);
    this.vectorWeight = Math.max(0, vectorWeight);
    this.lexicalCandidateLimit = Math.max(20, lexicalCandidateLimit);
  }

  public List<SearchHit> search(Long kbId, String question, int topK) {
    List<RetrievalCandidate> lexical =
        documents.lexicalCandidates(kbId, tokens.terms(question), lexicalCandidateLimit);
    List<RetrievalCandidate> semantic = Collections.emptyList();
    if (embeddings.enabled() && vectors.enabled()) {
      try {
        String activeModel = documents.activeEmbeddingModel(kbId, embeddings.getModel());
        semantic = vectors.search(kbId, activeModel, embeddings.embed(question, activeModel));
      } catch (Exception error) {
        log.warn("Vector retrieval unavailable; continuing with knowledge-base BM25", error);
      }
    }

    Map<Long, Score> scores = new LinkedHashMap<Long, Score>();
    for (RetrievalCandidate item : lexical) {
      scores.computeIfAbsent(item.getChunkId(), ignored -> new Score()).lexical =
          normalizeBm25(item.getScore());
    }
    for (RetrievalCandidate item : semantic) {
      scores.computeIfAbsent(item.getChunkId(), ignored -> new Score()).vector =
          Math.max(0, Math.min(1, item.getScore()));
    }
    if (scores.isEmpty()) return Collections.emptyList();

    boolean hasVector = !semantic.isEmpty();
    boolean hasLexical = !lexical.isEmpty();
    double activeLexicalWeight = hasLexical ? lexicalWeight : 0;
    double activeVectorWeight = hasVector ? vectorWeight : 0;
    double totalWeight = activeLexicalWeight + activeVectorWeight;
    if (totalWeight <= 0) return Collections.emptyList();

    List<Map.Entry<Long, Score>> ranked = new ArrayList<Map.Entry<Long, Score>>(scores.entrySet());
    for (Map.Entry<Long, Score> entry : ranked) {
      Score score = entry.getValue();
      score.fused =
          (score.lexical * activeLexicalWeight + score.vector * activeVectorWeight)
              / totalWeight;
    }
    ranked.sort((a, b) -> Double.compare(b.getValue().fused, a.getValue().fused));

    List<Long> eligibleIds = new ArrayList<Long>();
    for (Map.Entry<Long, Score> entry : ranked) {
      if (!eligible(entry.getValue())) continue;
      eligibleIds.add(entry.getKey());
      if (eligibleIds.size() >= Math.max(topK * 3, topK)) break;
    }
    if (eligibleIds.isEmpty()) return Collections.emptyList();

    Map<Long, SearchHit> hits = documents.chunksByIds(eligibleIds);
    List<SearchHit> result = new ArrayList<SearchHit>();
    for (Map.Entry<Long, Score> entry : ranked) {
      if (!eligible(entry.getValue())) continue;
      SearchHit hit = hits.get(entry.getKey());
      if (hit == null) continue;
      Score score = entry.getValue();
      hit.setScore(score.fused);
      hit.setLexicalScore(score.lexical);
      hit.setVectorScore(score.vector);
      hit.setRetrievalMode(
          score.lexical > 0 && score.vector > 0
              ? "HYBRID"
              : score.vector > 0 ? "VECTOR" : "LEXICAL");
      result.add(hit);
      if (result.size() >= topK) break;
    }
    result.sort(Comparator.comparingDouble(SearchHit::getScore).reversed());
    return result;
  }

  private double normalizeBm25(double score) {
    return score <= 0 ? 0 : score / (score + 6.0);
  }

  private boolean eligible(Score score) {
    boolean channelQualified =
        score.lexical >= minimumLexicalScore || score.vector >= minimumVectorScore;
    return channelQualified && score.fused >= minimumScore;
  }

  private static class Score {
    private double lexical;
    private double vector;
    private double fused;
  }
}
