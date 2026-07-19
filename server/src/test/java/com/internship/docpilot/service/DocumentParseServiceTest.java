package com.internship.docpilot.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.internship.docpilot.model.DocumentChunkDraft;
import com.internship.docpilot.model.PageText;
import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.OutboxRepository;
import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DocumentParseServiceTest {
  @Test
  void marksDocumentPartialAndKeepsPublishedVectorsWhenEmbeddingFails() throws Exception {
    DocumentRepository documents = mock(DocumentRepository.class);
    OutboxRepository outbox = mock(OutboxRepository.class);
    MinioStorageService storage = mock(MinioStorageService.class);
    EmbeddingService embeddings = mock(EmbeddingService.class);
    DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
    SemanticChunker chunker = mock(SemanticChunker.class);
    Map<String, Object> details = new HashMap<String, Object>();
    details.put("object_key", "documents/7.pdf");
    details.put("original_name", "制度.pdf");
    details.put("kb_id", 3L);
    details.put("parse_job_id", "job-7");
    details.put("version", 0);
    DocumentChunkDraft chunk = new DocumentChunkDraft(1, "制度", "有效正文内容超过十个字符", 12);
    when(documents.claim(7L)).thenReturn(true);
    when(documents.details(7L)).thenReturn(details);
    when(documents.touchLease(7L, "job-7", 30)).thenReturn(true);
    when(storage.open("documents/7.pdf")).thenReturn(new ByteArrayInputStream(new byte[] {1}));
    when(extractor.extract(any(), eq("制度.pdf")))
        .thenReturn(
            new DocumentTextExtractor.ExtractionResult(
                Collections.singletonList(new PageText(1, "有效正文内容超过十个字符", false)),
                false));
    when(chunker.chunk(anyList())).thenReturn(Collections.singletonList(chunk));
    when(embeddings.enabled()).thenReturn(true);
    when(embeddings.indexDocumentRevision(7L, 0, "job-7"))
        .thenThrow(new IllegalStateException("embedding unavailable"));
    DocumentParseService service =
        new DocumentParseService(documents, outbox, storage, embeddings, extractor, chunker);

    service.parse(7L);

    verify(documents).replaceChunks(eq(7L), eq(3L), anyList(), eq("job-7"));
    verify(embeddings).discardDocumentRevision(eq(7L), isNull());
    verify(embeddings, never()).deleteDocument(7L);
    verify(documents).partial(eq(7L), eq("job-7"), anyString(), eq("PDFBOX"), eq(1));
    verify(documents, never()).success(anyLong(), anyString(), anyString(), anyString(), anyInt());
    verify(documents, never()).failed(eq(7L), eq("job-7"), anyString());
  }
}
