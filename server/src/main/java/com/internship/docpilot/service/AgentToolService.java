package com.internship.docpilot.service;

import com.internship.docpilot.dto.AgentSearchRequest;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.AppUser;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.model.SearchHit;
import com.internship.docpilot.repository.DocumentRepository;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentToolService {
  private final InternalAgentGuard guard;
  private final KnowledgeBaseService knowledgeBases;
  private final RetrievalService retrieval;
  private final DocumentRepository documents;

  public AgentToolService(
      InternalAgentGuard guard,
      KnowledgeBaseService knowledgeBases,
      RetrievalService retrieval,
      DocumentRepository documents) {
    this.guard = guard;
    this.knowledgeBases = knowledgeBases;
    this.retrieval = retrieval;
    this.documents = documents;
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
}

