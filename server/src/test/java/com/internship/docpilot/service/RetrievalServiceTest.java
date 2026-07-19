package com.internship.docpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.internship.docpilot.model.RetrievalCandidate;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.VectorStoreRepository;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {
  @Test
  void returnsNoChunkWhenEveryCandidateIsBelowMinimumRelevance() {
    DocumentRepository documents = mock(DocumentRepository.class);
    EmbeddingService embeddings = mock(EmbeddingService.class);
    VectorStoreRepository vectors = mock(VectorStoreRepository.class);
    when(documents.lexicalCandidates(eq(3L), anyList(), eq(160)))
        .thenReturn(Collections.singletonList(new RetrievalCandidate(9L, 0.1)));
    when(embeddings.enabled()).thenReturn(false);
    RetrievalService service =
        new RetrievalService(
            documents, embeddings, vectors, new TokenEstimator(), 0.32, 0.05, 0.45, 0.35, 0.65, 160);

    assertTrue(service.search(3L, "完全无关的问题", 4).isEmpty());
    verify(documents, never()).chunksByIds(anyList());
  }

  @Test
  void usesVectorEvidenceAndPreservesFusedScore() throws Exception {
    DocumentRepository documents = mock(DocumentRepository.class);
    EmbeddingService embeddings = mock(EmbeddingService.class);
    VectorStoreRepository vectors = mock(VectorStoreRepository.class);
    when(documents.lexicalCandidates(eq(3L), anyList(), eq(160)))
        .thenReturn(Collections.emptyList());
    when(embeddings.enabled()).thenReturn(true);
    when(embeddings.getModel()).thenReturn("embedding-model");
    when(documents.activeEmbeddingModel(3L, "embedding-model")).thenReturn("embedding-model");
    when(embeddings.embed("审批制度", "embedding-model"))
        .thenReturn(new double[] {0.1, 0.2});
    when(vectors.enabled()).thenReturn(true);
    when(vectors.search(eq(3L), eq("embedding-model"), any(double[].class)))
        .thenReturn(Collections.singletonList(new RetrievalCandidate(7L, 0.91)));
    Map<Long, SearchHit> hits = new LinkedHashMap<Long, SearchHit>();
    hits.put(7L, new SearchHit(7L, 2L, "制度.md", "需要审批", 4, 0, null));
    when(documents.chunksByIds(Collections.singletonList(7L))).thenReturn(hits);
    RetrievalService service =
        new RetrievalService(
            documents, embeddings, vectors, new TokenEstimator(), 0.32, 0.05, 0.45, 0.35, 0.65, 160);

    SearchHit result = service.search(3L, "审批制度", 4).get(0);
    assertEquals(0.91, result.getScore(), 0.0001);
    assertEquals(0.91, result.getVectorScore(), 0.0001);
    assertEquals("VECTOR", result.getRetrievalMode());
  }
}
