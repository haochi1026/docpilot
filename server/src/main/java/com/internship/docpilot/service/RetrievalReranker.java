package com.internship.docpilot.service;

import com.internship.docpilot.model.SearchHit;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/** A deterministic, explainable second-stage reranker for already-qualified candidates. */
@Component
public class RetrievalReranker {
  private final TokenEstimator tokens;

  public RetrievalReranker(TokenEstimator tokens) {
    this.tokens = tokens;
  }

  public double score(String question, SearchHit hit) {
    List<String> queryTerms = tokens.terms(question);
    if (queryTerms.isEmpty()) return 0;
    Set<String> contentTerms = new HashSet<String>(tokens.terms(hit.getContent()));
    Set<String> titleTerms =
        new HashSet<String>(
            tokens.terms(
                safe(hit.getDocumentName()) + " " + safe(hit.getHeading())));
    int contentMatches = 0;
    int titleMatches = 0;
    for (String term : queryTerms) {
      if (contentTerms.contains(term)) contentMatches++;
      if (titleTerms.contains(term)) titleMatches++;
    }
    double coverage = (double) contentMatches / queryTerms.size();
    double titleCoverage = (double) titleMatches / queryTerms.size();
    double proximity = proximity(queryTerms, safe(hit.getContent()).toLowerCase(Locale.ROOT));
    String normalizedQuestion = normalize(question);
    String normalizedContent = normalize(hit.getContent());
    double phrase =
        normalizedQuestion.length() >= 4 && normalizedContent.contains(normalizedQuestion)
            ? 1.0
            : 0.0;
    return clamp(0.55 * coverage + 0.20 * titleCoverage + 0.15 * proximity + 0.10 * phrase);
  }

  private double proximity(List<String> terms, String content) {
    int first = Integer.MAX_VALUE;
    int last = -1;
    int found = 0;
    for (String term : terms) {
      int position = content.indexOf(term.toLowerCase(Locale.ROOT));
      if (position >= 0) {
        first = Math.min(first, position);
        last = Math.max(last, position + term.length());
        found++;
      }
    }
    if (found < 2) return found == 1 ? 0.25 : 0;
    int window = Math.max(1, last - first);
    return clamp(1.0 - Math.min(window, 240) / 240.0);
  }

  private String normalize(String value) {
    return safe(value)
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^\\p{IsHan}a-z0-9]+", "")
        .replaceFirst("^(请|请问|what|which|how|is|are)+", "");
  }

  private String safe(String value) {
    return value == null ? "" : value;
  }

  private double clamp(double value) {
    return Math.max(0, Math.min(1, value));
  }
}
