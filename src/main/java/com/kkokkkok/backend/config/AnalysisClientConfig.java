package com.kkokkkok.backend.config;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AnalysisClientConfig {
    @Bean
    public RestClient analysisRestClient(@Value("${analysis.base-url:}") String url,
            @Value("${analysis.api-key:}") String key,@Value("${analysis.timeout-seconds:30}") int timeout) {
        if(!url.isBlank()) {
            URI uri=URI.create(url);
            if(!Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null)
                throw new IllegalArgumentException("Invalid analysis.base-url");
        }
        if(timeout<1) throw new IllegalArgumentException("analysis.timeout-seconds must be positive");
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5)); factory.setReadTimeout(Duration.ofSeconds(timeout));
        var builder=RestClient.builder().requestFactory(factory).baseUrl(url.isBlank()?"http://localhost":url);
        if(!key.isBlank()) builder.defaultHeader("X-Analysis-Api-Key",key);
        return builder.build();
    }
}
