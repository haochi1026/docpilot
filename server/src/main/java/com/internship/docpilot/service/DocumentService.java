package com.internship.docpilot.service;

import com.internship.docpilot.exception.BusinessException;
import com.internship.docpilot.model.DocumentView;
import com.internship.docpilot.repository.DocumentRepository;
import com.internship.docpilot.repository.OutboxRepository;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  private static final Set<String> EXT =
      new HashSet<String>(
          Arrays.asList("pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx"));
  private final DocumentRepository documents;
  private final OutboxRepository outbox;
  private final MinioStorageService storage;
  private final KnowledgeBaseService kb;

  public DocumentService(
      DocumentRepository d, OutboxRepository o, MinioStorageService s, KnowledgeBaseService k) {
    documents = d;
    outbox = o;
    storage = s;
    kb = k;
  }

  @Transactional
  public DocumentView upload(Long uid, String role, Long kbId, MultipartFile file)
      throws Exception {
    kb.requireWrite(kbId, uid, role);
    if (file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "文件不能为空");
    String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
    if (!EXT.contains(ext)) throw new BusinessException(HttpStatus.BAD_REQUEST, "暂不支持该文件类型");
    String key = storage.save(file);
    try {
      Long id = documents.insert(kbId, uid, name, key, file.getContentType(), file.getSize());
      outbox.create(id);
      return documents.byKb(kbId).stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
    } catch (Exception e) {
      try {
        storage.remove(key);
      } catch (Exception ignored) {
      }
      throw e;
    }
  }

  public List<DocumentView> list(Long uid, String role, Long kbId) {
    kb.requireRead(kbId, uid, role);
    return documents.byKb(kbId);
  }

  public Map<String, Object> detail(Long uid, String role, Long id) {
    Long kbId = documents.kbId(id);
    if (kbId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    kb.requireRead(kbId, uid, role);
    Map<String, Object> result = new LinkedHashMap<String, Object>(documents.detailView(id));
    result.put("chunks", documents.chunkPreview(id));
    return result;
  }

  @Transactional
  public void retry(Long uid, String role, Long id) {
    Long kbId = documents.kbId(id);
    if (kbId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    kb.requireWrite(kbId, uid, role);
    if (documents.retry(id) == 0) {
      throw new BusinessException(HttpStatus.CONFLICT, "仅解析失败的文档可以重新处理");
    }
    outbox.create(id);
  }

  @Transactional
  public void delete(Long uid, String role, Long id) throws Exception {
    Long kbId = documents.kbId(id);
    if (kbId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    kb.requireWrite(kbId, uid, role);
    String objectKey = documents.objectKey(id);
    if (objectKey != null) storage.remove(objectKey);
    documents.delete(id);
  }
}
