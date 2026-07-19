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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.tika.Tika;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  private static final long MAX_UPLOAD_BYTES = 100L * 1024L * 1024L;
  private static final Set<String> ARCHIVE_EXT =
      new HashSet<String>(Arrays.asList("docx", "pptx", "xlsx"));
  private static final Set<String> EXT =
      new HashSet<String>(
          Arrays.asList("pdf", "doc", "docx", "txt", "md", "ppt", "pptx", "xls", "xlsx"));
  private final DocumentRepository documents;
  private final OutboxRepository outbox;
  private final MinioStorageService storage;
  private final KnowledgeBaseService kb;
  private final EmbeddingService embeddings;
  private final Tika tika = new Tika();

  public DocumentService(
      DocumentRepository d,
      OutboxRepository o,
      MinioStorageService s,
      KnowledgeBaseService k,
      EmbeddingService embeddings) {
    documents = d;
    outbox = o;
    storage = s;
    kb = k;
    this.embeddings = embeddings;
  }

  @Transactional
  public DocumentView upload(Long uid, String role, Long kbId, MultipartFile file)
      throws Exception {
    kb.requireWrite(kbId, uid, role);
    if (file.isEmpty()) throw new BusinessException(HttpStatus.BAD_REQUEST, "文件不能为空");
    if (file.getSize() > MAX_UPLOAD_BYTES) {
      throw new BusinessException(HttpStatus.PAYLOAD_TOO_LARGE, "文件超过 100MB 限制");
    }
    String name = file.getOriginalFilename() == null ? "document" : file.getOriginalFilename();
    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
    if (!EXT.contains(ext)) throw new BusinessException(HttpStatus.BAD_REQUEST, "暂不支持该文件类型");
    String detected = tika.detect(file.getInputStream(), name).toLowerCase(Locale.ROOT);
    if (!mimeMatches(ext, detected)) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "文件扩展名与实际内容类型不一致");
    }
    if (ARCHIVE_EXT.contains(ext)) validateArchive(file);
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
    result.put("revisions", documents.revisionHistory(id));
    return result;
  }

  @Transactional
  public void retry(Long uid, String role, Long id) {
    Long kbId = documents.kbId(id);
    if (kbId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    kb.requireWrite(kbId, uid, role);
    if (documents.retry(id) == 0) {
      throw new BusinessException(HttpStatus.CONFLICT, "仅解析失败或向量索引不完整的文档可以重新处理");
    }
    outbox.create(id);
  }

  @Transactional
  public void delete(Long uid, String role, Long id) throws Exception {
    Long kbId = documents.kbId(id);
    if (kbId == null) throw new BusinessException(HttpStatus.NOT_FOUND, "文档不存在");
    kb.requireWrite(kbId, uid, role);
    documents.delete(id);
    // MinIO and pgvector are outside the MySQL transaction. The tombstone is
    // committed first and an Outbox event performs external cleanup/retries.
    outbox.createDelete(id);
  }

  private boolean mimeMatches(String ext, String detected) {
    if ("txt".equals(ext) || "md".equals(ext)) {
      return detected.startsWith("text/") || detected.contains("octet-stream");
    }
    if ("pdf".equals(ext)) return detected.contains("pdf");
    if ("doc".equals(ext)) return detected.contains("msword") || detected.contains("octet-stream");
    if ("docx".equals(ext)) return detected.contains("wordprocessingml") || detected.contains("zip");
    if ("ppt".equals(ext)) return detected.contains("powerpoint") || detected.contains("octet-stream");
    if ("pptx".equals(ext)) return detected.contains("presentationml") || detected.contains("zip");
    if ("xls".equals(ext)) return detected.contains("ms-excel") || detected.contains("octet-stream");
    return detected.contains("spreadsheetml") || detected.contains("zip");
  }

  private void validateArchive(MultipartFile file) throws Exception {
    long uncompressed = 0;
    int entries = 0;
    try (ZipInputStream zip = new ZipInputStream(file.getInputStream())) {
      ZipEntry entry;
      byte[] buffer = new byte[8192];
      while ((entry = zip.getNextEntry()) != null) {
        if (++entries > 1000) throw new BusinessException(HttpStatus.BAD_REQUEST, "压缩文档条目数量过多");
        long entryBytes = 0;
        int read;
        while ((read = zip.read(buffer)) >= 0) {
          entryBytes += read;
          uncompressed += read;
          if (entryBytes > 200L * 1024L * 1024L || uncompressed > 500L * 1024L * 1024L) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "压缩文档解压后资源超限");
          }
        }
        long compressed = entry.getCompressedSize();
        if (compressed > 0 && entryBytes / compressed > 100) {
          throw new BusinessException(HttpStatus.BAD_REQUEST, "疑似压缩包炸弹");
        }
      }
    }
  }
}
