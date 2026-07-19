package com.internship.docpilot.service;

import com.internship.docpilot.dto.AgentSearchRequest;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgentToolService {
  private final InternalAgentGuard guard;
  private final KnowledgeBaseService knowledgeBases;
  private final RetrievalService retrieval;
  private final DocumentRepository documents;
  private final DocumentService documentService;
  private final AgentApprovalService approvals;

  public AgentToolService(
      InternalAgentGuard guard,
      KnowledgeBaseService knowledgeBases,
      RetrievalService retrieval,
      DocumentRepository documents,
      DocumentService documentService,
      AgentApprovalService approvals) {
    this.guard = guard;
    this.knowledgeBases = knowledgeBases;
    this.retrieval = retrieval;
    this.documents = documents;
    this.documentService = documentService;
    this.approvals = approvals;
  }

  public List<KnowledgeBase> listKnowledgeBases(String key, String username) {
    AppUser user = guard.authenticate(key, username);
    return knowledgeBases.list(user.getId(), user.getRole());
  }

  public List<SearchHit> search(String key, String username, AgentSearchRequest request) {
    AppUser user = guard.authenticate(key, username);
    knowledgeBases.requireRead(request.getKbId(), user.getId(), user.getRole());
    return retrieval.search(request.getKbId(), request.getQuery(), request.getTopK());
  }

  public Map<String, Object> chunk(String key, String username, Long chunkId) {
    AppUser user = guard.authenticate(key, username);
    Map<String, Object> chunk = documents.agentChunk(chunkId);
    if (chunk == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "引用片段不存在");
    }
    Long kbId = ((Number) chunk.remove("kbId")).longValue();
    knowledgeBases.requireRead(kbId, user.getId(), user.getRole());
    return chunk;
  }

  public List<DocumentView> listDocuments(String key, String username, Long kbId) {
    AppUser user = guard.authenticate(key, username);
    knowledgeBases.requireRead(kbId, user.getId(), user.getRole());
    return documents.byKb(kbId);
  }

  public Map<String, Object> documentDiagnostics(String key, String username, Long documentId) {
    AppUser user = guard.authenticate(key, username);
    Long kbId = documents.kbId(documentId);
    if (kbId == null) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    }
    knowledgeBases.requireRead(kbId, user.getId(), user.getRole());
    return documents.detailView(documentId);
  }

  @Transactional
  public Map<String, Object> retryDocument(
      String key,
      String username,
      String approvalId,
      String approvalToken,
      Long documentId) {
    AppUser user = guard.authenticate(key, username);
    approvals.consume(
        user.getId(), approvalId, approvalToken, "retry_document_parsing", documentId);
    documentService.retry(user.getId(), user.getRole(), documentId);
    return documents.detailView(documentId);
  }
}
