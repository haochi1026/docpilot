package com.internship.docpilot.service;

import com.internship.docpilot.dto.CreateKbRequest;
import com.internship.docpilot.dto.KbMemberRequest;
import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.KnowledgeBase;
import com.internship.docpilot.repository.KnowledgeBaseRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseService {
  private final KnowledgeBaseRepository repo;
  private final EmbeddingService embeddings;

  public KnowledgeBaseService(KnowledgeBaseRepository r, EmbeddingService embeddings) {
    repo = r;
    this.embeddings = embeddings;
  }

  public List<KnowledgeBase> list(Long uid, String role) {
    return repo.accessible(uid, role);
  }

  public Long create(Long uid, String role, CreateKbRequest r) {
    if (!"ADMIN".equals(role) && !"MANAGER".equals(role)) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "普通成员不能创建知识库");
    }
    Long departmentId = "MANAGER".equals(role) ? repo.managedDepartment(uid) : null;
    if ("MANAGER".equals(role) && departmentId == null) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "当前账号未绑定可管理的部门");
    }
    return repo.create(uid, r.getName(), r.getDescription(), departmentId);
  }

  public void requireRead(Long kb, Long uid, String role) {
    if (!repo.canRead(kb, uid, role)) throw new BusinessException(HttpStatus.FORBIDDEN, "无权访问该知识库");
  }

  public void requireWrite(Long kb, Long uid, String role) {
    if (!repo.canManage(kb, uid, role))
      throw new BusinessException(HttpStatus.FORBIDDEN, "无权修改该知识库");
  }

  public List<Map<String, Object>> members(Long kbId, Long uid, String role) {
    requireRead(kbId, uid, role);
    return repo.members(kbId);
  }

  public void grantMember(
      Long kbId, Long operatorId, String role, KbMemberRequest request) {
    requireWrite(kbId, operatorId, role);
    Long targetId = repo.userId(request.getUsername());
    if (targetId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "用户不存在或已停用");
    if (repo.isOwner(kbId, targetId)) {
      throw new BusinessException(HttpStatus.CONFLICT, "知识库负责人无需重复授权");
    }
    repo.grant(kbId, targetId, request.getPermission());
    repo.audit(kbId, operatorId, targetId, "GRANT", request.getPermission());
  }

  public void revokeMember(Long kbId, Long operatorId, String role, Long targetId) {
    requireWrite(kbId, operatorId, role);
    if (repo.isOwner(kbId, targetId)) {
      throw new BusinessException(HttpStatus.CONFLICT, "不能移除知识库负责人");
    }
    if (repo.revoke(kbId, targetId) == 0) {
      throw new BusinessException(HttpStatus.NOT_FOUND, "该用户没有知识库授权");
    }
    repo.audit(kbId, operatorId, targetId, "REVOKE", null);
  }

  public int rebuildIndex(Long kbId, Long userId, String role) throws Exception {
    requireWrite(kbId, userId, role);
    if (!embeddings.enabled()) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "当前未启用 Embedding 模型");
    }
    return embeddings.reindexKnowledgeBase(kbId);
  }

  public Map<String, Object> indexStatus(Long kbId, Long userId, String role) {
    requireRead(kbId, userId, role);
    int chunks = embeddings.countChunks(kbId);
    int indexed = embeddings.enabled() ? embeddings.countEmbeddings(kbId) : 0;
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("enabled", embeddings.enabled());
    result.put("model", embeddings.enabled() ? embeddings.getModel() : "未启用向量检索");
    result.put("totalChunks", chunks);
    result.put("indexedChunks", indexed);
    result.put("complete", embeddings.enabled() && chunks > 0 && chunks == indexed);
    return result;
  }
}
