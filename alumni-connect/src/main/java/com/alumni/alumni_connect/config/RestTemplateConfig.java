
package com.alumni.alumni_connect.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return new RestTemplate(requestFactory);
    }

    @Bean
    @Qualifier("experienceEvidenceRestTemplate")
    public RestTemplate experienceEvidenceRestTemplate() {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(3));
        requestFactory.setReadTimeout(Duration.ofSeconds(95));
        return new RestTemplate(requestFactory);
    }

    @Bean
    @Qualifier("knowledgeAnswerRestTemplate")
    public RestTemplate knowledgeAnswerRestTemplate(
            @Value("${app.knowledge.provider.timeout-ms:20000}") int timeoutMs) {
        if (timeoutMs < 1000 || timeoutMs > 60000)
            throw new IllegalArgumentException("Knowledge provider timeout must be between 1000 and 60000 milliseconds");
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));
        return new RestTemplate(requestFactory);
    }
}
