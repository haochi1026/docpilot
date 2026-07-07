package com.internship.docpilot.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.InputStream;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MinioStorageService {
  private final MinioClient client;
  private final String bucket;

  public MinioStorageService(
      @Value("${app.storage.endpoint}") String endpoint,
      @Value("${app.storage.access-key}") String access,
      @Value("${app.storage.secret-key}") String secret,
      @Value("${app.storage.bucket}") String bucket) {
    this.client = MinioClient.builder().endpoint(endpoint).credentials(access, secret).build();
    this.bucket = bucket;
  }

  @PostConstruct
  public void init() {
    try {
      if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build()))
        client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    } catch (Exception e) {
      throw new IllegalStateException("MinIO 初始化失败，请确认服务已启动: " + e.getMessage(), e);
    }
  }

  public String save(MultipartFile file) throws Exception {
    String clean =
        file.getOriginalFilename() == null
            ? "document"
            : file.getOriginalFilename().replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");
    String key = UUID.randomUUID() + "/" + clean;
    String type =
        file.getContentType() == null ? "application/octet-stream" : file.getContentType();
    client.putObject(
        PutObjectArgs.builder().bucket(bucket).object(key).stream(
                file.getInputStream(), file.getSize(), -1)
            .contentType(type)
            .build());
    return key;
  }

  public InputStream open(String key) throws Exception {
    return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
  }

  public void remove(String key) throws Exception {
    client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
  }
}
