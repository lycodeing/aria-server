package com.aidevplatform.knowledge.infrastructure.storage;

import io.minio.MinioClient;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 对象存储配置。
 *
 * <p>配置项（均支持环境变量覆盖）：
 * <pre>
 *   minio.endpoint    MinIO 服务地址（默认 http://localhost:9000）
 *   minio.access-key  访问密钥（默认 minioadmin）
 *   minio.secret-key  密钥（默认 minioadmin）
 *   minio.bucket      存储桶名称（默认 ai-knowledge）
 * </pre>
 */
@Setter
@Configuration
@ConfigurationProperties(prefix = "minio")
public class MinioConfig {

    private String endpoint  = "http://localhost:9000";
    private String accessKey = "minioadmin";
    private String secretKey = "minioadmin";
    /** 存储桶名称（供 MinioStorageService 注入使用） */
    private String bucket    = "ai-knowledge";

    /**
     * 创建 MinioClient Bean，供 {@link MinioStorageService} 注入使用。
     */
    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }

    /**
     * 暴露存储桶名称供 StorageService 使用。
     */
    @Bean(name = "minioBucket")
    public String minioBucket() {
        return bucket;
    }
}
