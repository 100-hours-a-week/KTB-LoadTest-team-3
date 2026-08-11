package com.ktb.chatapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@ConditionalOnProperty(name = "file.storage.type", havingValue = "s3")
public class S3Config {

    @Bean
    public S3Client s3Client(@Value("${file.s3.region}") String region) {
        // 자격증명 provider를 지정하지 않아 AWS SDK v2의 Default Credential Provider Chain을 사용한다.
        return S3Client.builder()
                .region(Region.of(region))
                .build();
    }
}
