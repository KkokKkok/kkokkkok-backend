package com.kkokkkok.backend.config;

import com.kkokkkok.backend.service.AssetStorage;
import com.kkokkkok.backend.service.S3AssetStorage;
import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "assets.provider", havingValue = "s3")
public class AssetStorageConfig {
    @Bean(destroyMethod = "close")
    S3Client assetS3Client(@Value("${assets.s3.region}") String region,
                          @Value("${assets.s3.endpoint:}") String endpoint,
                          @Value("${assets.s3.path-style:false}") boolean pathStyle) {
        var builder = S3Client.builder().region(Region.of(region)).forcePathStyle(pathStyle)
                .serviceConfiguration(S3Configuration.builder().chunkedEncodingEnabled(false).build())
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(5)).socketTimeout(Duration.ofSeconds(15)))
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(30))
                        .apiCallAttemptTimeout(Duration.ofSeconds(20)));
        // Default credential chain: EC2 role in production, synthetic environment credentials in local tests.
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }

    @Bean
    AssetStorage assetStorage(S3Client assetS3Client, @Value("${assets.s3.bucket}") String bucket,
                              @Value("${assets.s3.prefix:assets/}") String prefix) {
        return new S3AssetStorage(assetS3Client, bucket, prefix);
    }
}
