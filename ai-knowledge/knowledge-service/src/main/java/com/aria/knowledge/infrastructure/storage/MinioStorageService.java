package com.aria.knowledge.infrastructure.storage;

import io.minio.*;
import io.minio.errors.MinioException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * MinIO 对象存储服务。
 *
 * <p>storagePath 格式：{@code oss://{bucket}/docs/{docId}/{filename}}
 * 例如：{@code oss://ai-knowledge/docs/123456789/manual.pdf}
 *
 * <p>启动时自动检查 bucket 是否存在，不存在则创建。
 */
@Slf4j
@Service
public class MinioStorageService {

    private static final String PATH_PREFIX = "oss://";

    private final MinioClient minioClient;
    private final String      bucket;

    public MinioStorageService(MinioClient minioClient,
                               @Qualifier("minioBucket") String bucket) {
        this.minioClient = minioClient;
        this.bucket      = bucket;
    }

    /**
     * 启动时确保 bucket 存在，不存在则自动创建。
     */
    @PostConstruct
    public void ensureBucket() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] 存储桶已创建: {}", bucket);
            } else {
                log.info("[MinIO] 存储桶已就绪: {}", bucket);
            }
        } catch (Exception e) {
            // 非致命错误，打印警告；首次上传时会再次尝试
            log.warn("[MinIO] 存储桶检查失败（服务可能未就绪）: {}", e.getMessage());
        }
    }

    /**
     * 上传文件字节流到 MinIO。
     *
     * @param docId    文档 ID（作为路径前缀，避免同名文件冲突）
     * @param filename 原始文件名
     * @param bytes    文件字节内容
     * @return storagePath，格式：{@code oss://{bucket}/docs/{docId}/{filename}}
     */
    public String upload(String docId, String filename, byte[] bytes) {
        String objectKey = "docs/" + docId + "/" + filename;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectKey)
                    .stream(in, bytes.length, -1)
                    .contentType(resolveContentType(filename))
                    .build());
            String storagePath = PATH_PREFIX + bucket + "/" + objectKey;
            log.info("[MinIO] 文件上传成功: {}", storagePath);
            return storagePath;
        } catch (Exception e) {
            throw new RuntimeException("MinIO 文件上传失败: " + filename, e);
        }
    }

    /**
     * 从 MinIO 下载文件内容。
     *
     * @param storagePath 存储路径，格式：{@code oss://bucket/path}
     * @return 文件字节内容
     */
    public byte[] download(String storagePath) {
        String objectKey = extractObjectKey(storagePath);
        try (InputStream in = minioClient.getObject(
                GetObjectArgs.builder().bucket(bucket).object(objectKey).build())) {
            byte[] bytes = in.readAllBytes();
            log.debug("[MinIO] 文件下载成功: {} ({} bytes)", storagePath, bytes.length);
            return bytes;
        } catch (MinioException e) {
            throw new RuntimeException("MinIO 文件下载失败: " + storagePath + " — " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("MinIO 文件下载失败: " + storagePath, e);
        }
    }

    /**
     * 删除存储对象（文档下线时调用）。
     *
     * @param storagePath 存储路径
     */
    public void delete(String storagePath) {
        if (storagePath == null || storagePath.startsWith("pending://")) return;
        try {
            String objectKey = extractObjectKey(storagePath);
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket).object(objectKey).build());
            log.info("[MinIO] 文件已删除: {}", storagePath);
        } catch (Exception e) {
            log.warn("[MinIO] 文件删除失败（可忽略）: {} — {}", storagePath, e.getMessage());
        }
    }

    // ---- 内部工具 ----

    /**
     * 从 storagePath 中提取 MinIO objectKey。
     * {@code oss://ai-knowledge/docs/123/file.pdf} → {@code docs/123/file.pdf}
     */
    private String extractObjectKey(String storagePath) {
        if (!storagePath.startsWith(PATH_PREFIX)) {
            throw new IllegalArgumentException("storagePath 格式不合法（期望 oss://...）: " + storagePath);
        }
        // 去掉 "oss://bucket/" 前缀
        String withoutPrefix = storagePath.substring(PATH_PREFIX.length());
        int slashIdx = withoutPrefix.indexOf('/');
        if (slashIdx < 0) {
            throw new IllegalArgumentException("storagePath 缺少路径部分: " + storagePath);
        }
        return withoutPrefix.substring(slashIdx + 1);
    }

    /**
     * 根据文件名后缀推断 Content-Type。
     */
    private String resolveContentType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))                             return "application/pdf";
        if (lower.endsWith(".docx"))                           return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".md"))                             return "text/markdown";
        return "application/octet-stream";
    }
}
