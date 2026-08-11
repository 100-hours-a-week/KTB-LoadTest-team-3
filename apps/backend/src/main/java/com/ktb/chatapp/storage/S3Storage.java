package com.ktb.chatapp.storage;

import java.io.InputStream;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Component
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Storage implements StoragePort {

    private final S3Client s3Client;
    private final String bucket;
    private final String profileCacheControl;

    public S3Storage(
            S3Client s3Client,
            @Value("${file.s3.bucket}") String bucket,
            @Value("${file.s3.profile-cache-control:public, max-age=31536000, immutable}")
                    String profileCacheControl) {
        if (!StringUtils.hasText(bucket)) {
            throw new IllegalArgumentException("file.s3.bucket 설정이 필요합니다.");
        }
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.profileCacheControl = profileCacheControl;
    }

    @Override
    public StoredObject put(InputStream content, String key, String contentType, long size) {
        validateKey(key);

        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key);
        if (StringUtils.hasText(contentType)) {
            request.contentType(contentType);
        }
        if (StorageKey.isProfile(key) && StringUtils.hasText(profileCacheControl)) {
            request.cacheControl(profileCacheControl);
        }

        try {
            s3Client.putObject(request.build(), RequestBody.fromInputStream(content, size));
            return new StoredObject(key, size);
        } catch (S3Exception ex) {
            throw new RuntimeException("S3 파일 저장에 실패했습니다: " + errorMessage(ex), ex);
        }
    }

    @Override
    public Optional<Resource> open(String key) {
        validateKey(key);

        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
            return Optional.of(new InputStreamResource(response));
        } catch (NoSuchKeyException ex) {
            return Optional.empty();
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw new RuntimeException("S3 파일 조회에 실패했습니다: " + errorMessage(ex), ex);
        }
    }

    @Override
    public void delete(String key) {
        validateKey(key);

        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .build());
        } catch (S3Exception ex) {
            throw new RuntimeException("S3 파일 삭제에 실패했습니다: " + errorMessage(ex), ex);
        }
    }

    private String errorMessage(S3Exception ex) {
        return ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorMessage() : ex.getMessage();
    }

    private void validateKey(String key) {
        if (!StringUtils.hasText(key)
                || key.startsWith("/")
                || key.contains("\\")
                || key.equals("..")
                || key.startsWith("../")
                || key.endsWith("/..")
                || key.contains("/../")) {
            throw new IllegalArgumentException("잘못된 스토리지 key입니다.");
        }
    }
}
