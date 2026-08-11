package com.ktb.chatapp.service;

import com.ktb.chatapp.storage.StorageKey;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 공개 프로필 파일에 한해서 CloudFront 전달 URL을 만든다. */
@Component
public class CloudFrontUrl {

    private final boolean enabled;
    private final boolean s3Storage;
    private final String domain;

    public CloudFrontUrl(
            @Value("${file.cdn.enabled:false}") boolean enabled,
            @Value("${file.storage.type:local}") String storageType,
            @Value("${file.cdn.domain:}") String domain) {
        this.enabled = enabled;
        this.s3Storage = "s3".equalsIgnoreCase(storageType);
        this.domain = normalize(domain);
    }

    public Optional<URI> profile(String key) {
        if (!enabled || !s3Storage || !StorageKey.isProfile(key) || !StringUtils.hasText(domain)) {
            return Optional.empty();
        }
        return Optional.of(URI.create(domain + "/" + key));
    }

    private String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("/+$", "");
        URI uri = URI.create(normalized);
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("file.cdn.domain은 유효한 HTTP(S) base URL이어야 합니다.");
        }
        return normalized;
    }
}
