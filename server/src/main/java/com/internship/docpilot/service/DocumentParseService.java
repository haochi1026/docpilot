package com.internship.docpilot.service;

import com.internship.docpilot.repository.DocumentRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.tika.Tika;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentParseService {
  private static final Logger log = LoggerFactory.getLogger(DocumentParseService.class);
  private final DocumentRepository documents;
  private final MinioStorageService storage;
  private final EmbeddingService embeddings;
  private final Tika tika = new Tika();

  public DocumentParseService(
      DocumentRepository d, MinioStorageService s, EmbeddingService embeddings) {
    documents = d;
    storage = s;
    this.embeddings = embeddings;
  }

  public void parse(Long documentId) {
    if (!documents.claim(documentId)) return;
    try {
      Map<String, Object> d = documents.details(documentId);
      if (d == null) return;
      String key = String.valueOf(d.get("object_key"));
      String text;
      try (InputStream in = storage.open(key)) {
        text = tika.parseToString(in);
      }
      text = normalize(text);
      if (text.length() < 10) throw new IllegalArgumentException("未提取到足够文本，扫描版 PDF 请先进行 OCR");
      List<String> chunks = chunk(text, 700, 100);
      documents.replaceChunks(documentId, chunks);
      if (embeddings.enabled()) {
        try {
          int indexed = embeddings.indexDocument(documentId);
          log.info("Document {} indexed into {} embedding vectors", documentId, indexed);
        } catch (Exception embeddingError) {
          log.warn(
              "Document {} text parsed, but embedding indexing failed: {}",
              documentId,
              embeddingError.getMessage());
        }
      }
      documents.success(documentId);
      log.info("Document {} parsed into {} chunks", documentId, chunks.size());
    } catch (Exception e) {
      documents.failed(
          documentId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
      log.warn("Document {} parse failed", documentId, e);
    }
  }

  private String normalize(String s) {
    return s.replace('\u0000', ' ')
        .replaceAll("[ \\t]+", " ")
        .replaceAll("\\n{3,}", "\\n\\n")
        .trim();
  }

  private List<String> chunk(String text, int size, int overlap) {
    List<String> out = new ArrayList<String>();
    int start = 0;
    while (start < text.length()) {
      int end = Math.min(text.length(), start + size);
      if (end < text.length()) {
        int p = Math.max(text.lastIndexOf('\n', end), text.lastIndexOf('。', end));
        if (p > start + size / 2) end = p + 1;
      }
      out.add(text.substring(start, end).trim());
      if (end >= text.length()) break;
      start = Math.max(start + 1, end - overlap);
    }
    return out;
  }
}
