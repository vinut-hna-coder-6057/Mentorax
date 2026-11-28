package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional request-scoped semantic reranker backed by the existing private Python AI service. */
@Component
public class KnowledgeSemanticRanker {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSemanticRanker.class);

    private final RestTemplate rest;
    private final boolean enabled;
    private final String serviceUrl;
    private final String token;

    public KnowledgeSemanticRanker(@Qualifier("knowledgeAnswerRestTemplate") RestTemplate rest,
            @Value("${app.knowledge.semantic.enabled:false}") boolean enabled,
            @Value("${AI_SERVICE_URL:}") String serviceUrl,
            @Value("${AI_MENTOR_API_TOKEN:}") String token) {
        this.rest = rest;
        this.enabled = enabled;
        this.serviceUrl = serviceUrl == null ? "" : serviceUrl.trim();
        this.token = token == null ? "" : token.trim();
    }

    public boolean isEnabled() {
        return enabled && !serviceUrl.isBlank() && !token.isBlank();
    }

    /** Returns all candidate scores or an empty map so lexical retrieval remains available. */
    public Map<Long, Double> scores(String query, List<KnowledgeRetrievalResponse.Source> sources) {
        if (!isEnabled() || sources == null || sources.isEmpty() || sources.size() > 250) return Map.of();
        try {
            URI endpoint = endpoint();
            List<Map<String, Object>> candidates = new ArrayList<>(sources.size());
            for (var source : sources) {
                // Titles and profile-like company/role metadata stay in Spring; semantic inference
                // receives only the sanitized contribution category and evidence excerpt.
                String text = String.join(" ", safe(source.category()), safe(source.excerpt()));
                text = KnowledgeContentSanitizer.redact(text);
                if (text.length() > 1200) text = text.substring(0, 1200);
                candidates.add(Map.of("id", source.contributionId(), "text", text));
            }
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(token);
            Map<String, Object> payload = Map.of("query", KnowledgeContentSanitizer.redact(query),
                    "candidates", candidates);
            var response = rest.exchange(endpoint, HttpMethod.POST, new HttpEntity<>(payload, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {});
            Object raw = response.getBody() == null ? null : response.getBody().get("scores");
            if (!(raw instanceof Map<?, ?> values)) return Map.of();
            Map<Long, Double> result = new LinkedHashMap<>();
            for (var source : sources) {
                Object value = values.get(Long.toString(source.contributionId()));
                if (!(value instanceof Number number)) return Map.of();
                double score = number.doubleValue();
                if (!Double.isFinite(score) || score < 0 || score > 1) return Map.of();
                result.put(source.contributionId(), score);
            }
            if (values.size() != result.size()) return Map.of();
            return Map.copyOf(result);
        } catch (RuntimeException failure) {
            // Do not log the query, contribution content, URL, token, or HTTP response body.
            log.info("Optional Knowledge semantic reranking unavailable (errorType={})",
                    failure.getClass().getSimpleName());
            return Map.of();
        }
    }

    private URI endpoint() {
        URI base = URI.create(serviceUrl);
        String host = base.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        if (base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
                || !("https".equalsIgnoreCase(base.getScheme())
                || ("http".equalsIgnoreCase(base.getScheme()) && loopback))) {
            throw new IllegalArgumentException("Knowledge ranking service must use HTTPS or loopback HTTP");
        }
        String path = base.getPath() == null ? "" : base.getPath().replaceAll("/$", "");
        return URI.create(base.getScheme() + "://" + base.getRawAuthority() + path + "/knowledge/rank");
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
