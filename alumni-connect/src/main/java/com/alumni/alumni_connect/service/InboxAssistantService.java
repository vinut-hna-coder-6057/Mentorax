package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.InboxAssistantRequest;
import com.alumni.alumni_connect.dto.InboxAssistantResponse;
import com.alumni.alumni_connect.entity.Message;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.MessageRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class InboxAssistantService {
    private static final Logger log = LoggerFactory.getLogger(InboxAssistantService.class);
    private static final String SCHEMA = "mentorax-inbox-assistant-v1";
    private static final String SAFE_DRAFT = "Hi, thanks for reaching out. Could you share any additional context that would help me understand your request?";
    private static final String SAFE_MISSING_INFO = "Review the original message for details that may need clarification.";
    private static final Set<String> CATEGORIES = Set.of("CAREER_GUIDANCE", "INTERVIEW_PREPARATION",
            "REFERRAL_JOB_APPLICATION", "PROJECT_TECHNICAL_ADVICE", "MENTORSHIP_REQUEST", "OTHER");
    private static final java.util.Map<String, String> CATEGORY_LABELS = java.util.Map.of(
            "CAREER_GUIDANCE", "Career guidance", "INTERVIEW_PREPARATION", "Interview preparation",
            "REFERRAL_JOB_APPLICATION", "Referral or job application",
            "PROJECT_TECHNICAL_ADVICE", "Project or technical advice",
            "MENTORSHIP_REQUEST", "Mentorship request", "OTHER", "Other or unclear");
    private final MessageRepository messages;
    private final CurrentUserService currentUser;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    @Value("${app.ai.service-url:http://127.0.0.1:8090}") private String aiServiceUrl;
    @Value("${AI_INBOX_ASSISTANT_ENABLED:false}") private boolean enabled;
    @Value("${INBOX_ASSISTANT_API_TOKEN:}") private String apiToken;

    public InboxAssistantService(MessageRepository messages, CurrentUserService currentUser,
                                 RestTemplate restTemplate, ObjectMapper mapper) {
        this.messages = messages;
        this.currentUser = currentUser;
        this.restTemplate = restTemplate;
        this.mapper = mapper;
    }

    public InboxAssistantResponse analyzeIncomingMessage(long messageId) {
        User caller = currentUser.requireUser();
        if (!"ALUMNI".equalsIgnoreCase(caller.getRole())
                || !"APPROVED".equalsIgnoreCase(caller.getStatus())) {
            throw new ResponseStatusException(FORBIDDEN);
        }
        Message message = messages.findById(messageId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Message not found"));
        if (message.getReceiverEmail() == null || !message.getReceiverEmail().equalsIgnoreCase(caller.getEmail())) {
            throw new ResponseStatusException(FORBIDDEN, "You may only analyze messages received in your inbox");
        }
        String content = message.getContent();
        if (content == null || content.isBlank() || content.length() > 2000) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                    "Message cannot be analyzed");
        }
        if (!enabled) {
            log.warn("Inbox assistant unavailable: feature flag is disabled");
            return InboxAssistantResponse.unavailable();
        }
        if (apiToken == null || apiToken.isBlank()) {
            log.warn("Inbox assistant unavailable: internal token is not configured");
            return InboxAssistantResponse.unavailable();
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiToken);
            InboxAssistantRequest request = new InboxAssistantRequest(message.getId(), content,
                    message.getTimestamp() == null ? null : message.getTimestamp().toString());
            ResponseEntity<Object> response = restTemplate.exchange(aiServiceUrl + "/inbox/assist",
                    HttpMethod.POST, new HttpEntity<>(request, headers), Object.class);
            InboxAssistantResponse result;
            try {
                result = mapper.convertValue(response.getBody(), InboxAssistantResponse.class);
            } catch (IllegalArgumentException exception) {
                log.warn("Inbox assistant unavailable: Python response could not be parsed");
                return InboxAssistantResponse.unavailable();
            }
            if (result != null && !result.available()) {
                log.warn("Inbox assistant unavailable: Python service returned unavailable");
                return InboxAssistantResponse.unavailable();
            }
            if (!isValid(result, content)) {
                log.warn("Inbox assistant unavailable: Python response failed contract validation");
                return InboxAssistantResponse.unavailable();
            }
            return result;
        } catch (RestClientResponseException exception) {
            log.warn("Inbox assistant unavailable: Python service returned HTTP {}",
                    exception.getStatusCode().value());
            return InboxAssistantResponse.unavailable();
        } catch (ResourceAccessException exception) {
            log.warn("Inbox assistant unavailable: Python service connection failed ({})",
                    exception.getClass().getSimpleName());
            return InboxAssistantResponse.unavailable();
        } catch (RestClientException exception) {
            log.warn("Inbox assistant unavailable: Python request failed ({})",
                    exception.getClass().getSimpleName());
            return InboxAssistantResponse.unavailable();
        } catch (IllegalArgumentException exception) {
            log.warn("Inbox assistant unavailable: request or response processing failed ({})",
                    exception.getClass().getSimpleName());
            return InboxAssistantResponse.unavailable();
        }
    }

    private boolean isValid(InboxAssistantResponse result, String original) {
        if (result == null || !result.available() || !SCHEMA.equals(result.schemaVersion())
                || !CATEGORIES.contains(result.category())
                || !CATEGORY_LABELS.get(result.category()).equals(result.categoryLabel())
                || !Set.of("low", "moderate").contains(result.confidence())
                || result.uncertain() != (result.category().equals("OTHER") || result.confidence().equals("low"))
                || !Set.of("normal", "urgent").contains(result.prioritySuggestion())
                || !Set.of("none", "heuristic", "explicit_deadline").contains(result.urgencyKind())
                || !"deterministic_template".equals(result.draftKind()) || !SAFE_DRAFT.equals(result.draft())
                || !SAFE_MISSING_INFO.equals(result.missingInformation()) || result.message() != null
                || result.summary() == null || result.summary().isBlank() || result.summary().length() > 300
                || !original.toLowerCase(Locale.ROOT).contains(result.summary().toLowerCase(Locale.ROOT))
                || result.categoryEvidence() == null || result.categoryEvidence().size() > 5
                || result.categoryEvidence().stream().anyMatch(e -> e == null || !contains(original, e))
                || !evidenceOptional(original, result.urgencyEvidence())
                || !evidenceOptional(original, result.deadlineEvidence())
                || result.modelMetadata() == null
                || !"TF-IDF category-description similarity plus deterministic urgency rules".equals(result.modelMetadata().strategy())
                || result.modelMetadata().trainedForMentorax() || result.modelMetadata().scoresAreCalibratedProbabilities()
                || !"not_configured".equals(result.modelMetadata().transformer())) return false;
        return (result.urgencyKind().equals("explicit_deadline") && result.deadlineEvidence() != null
                && result.prioritySuggestion().equals("urgent"))
                || (result.urgencyKind().equals("heuristic") && result.urgencyEvidence() != null
                && result.deadlineEvidence() == null && result.prioritySuggestion().equals("urgent"))
                || (result.urgencyKind().equals("none") && result.urgencyEvidence() == null
                && result.deadlineEvidence() == null && result.prioritySuggestion().equals("normal"));
    }

    private static boolean evidenceOptional(String original, String evidence) {
        return evidence == null || contains(original, evidence);
    }

    private static boolean contains(String original, String evidence) {
        return !evidence.isBlank() && original.toLowerCase(Locale.ROOT).contains(evidence.toLowerCase(Locale.ROOT));
    }
}
