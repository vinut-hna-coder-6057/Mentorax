
package com.alumni.alumni_connect.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class EmailService {

    private static final Logger log =
            LoggerFactory.getLogger(EmailService.class);

    private static final String RESEND_API_URL =
            "https://api.resend.com/emails";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern OTP_PATTERN = Pattern.compile("\\b\\d{6}\\b");

    private final String apiKey;
    private final String fromEmail;
    private final RestTemplate restTemplate;

    public EmailService(
        @Value("${RESEND_API_KEY:}") String apiKey,
        @Value("${RESEND_FROM_EMAIL:}") String fromEmail,
        RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.fromEmail = fromEmail;
        this.restTemplate = restTemplate;
    }

    private void sendEmail(String to, String subject, String body) {
        if (apiKey == null || apiKey.isBlank() || fromEmail == null || fromEmail.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is not configured");
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    sendEmailNow(to, subject, body);
                }
            });
            return;
        }

        sendEmailNow(to, subject, body);
    }

    private void sendEmailNow(String to, String subject, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> payload = Map.of(
                "from", fromEmail,
                "to", List.of(to),
                "subject", subject,
                "text", body
        );

        HttpEntity<Map<String, Object>> request =
                new HttpEntity<>(payload, headers);

        try {
            ResponseEntity<String> response =
                    restTemplate.postForEntity(
                            RESEND_API_URL,
                            request,
                            String.class
                    );

            log.info("Email accepted by Resend. HTTP status: {}",
                    response.getStatusCode().value());

        } catch (HttpStatusCodeException exception) {
            JsonNode error = parseProviderError(exception.getResponseBodyAsString());
            log.error(
                    "Resend rejected email request: status={}, providerCode={}, providerMessage={}",
                    exception.getStatusCode().value(),
                    safeProviderValue(error.path("name").asText(error.path("code").asText())),
                    safeProviderMessage(error.path("message").asText()));

            throw unavailable();
        } catch (RestClientException exception) {
            log.error(
                    "Resend email request failed: exceptionType={}",
                    exception.getClass().getSimpleName());

            throw unavailable();
        }
    }

    private JsonNode parseProviderError(String responseBody) {
        try {
            return OBJECT_MAPPER.readTree(responseBody);
        } catch (Exception exception) {
            return OBJECT_MAPPER.createObjectNode();
        }
    }

    private String safeProviderValue(String value) {
        String sanitized = value == null ? "" : value.replaceAll("[^A-Za-z0-9_.-]", "");
        return sanitized.substring(0, Math.min(sanitized.length(), 64));
    }

    private String safeProviderMessage(String value) {
        if (value == null || value.isBlank()) {
            return "unavailable";
        }
        String sanitized = value
                .replace(apiKey == null || apiKey.isEmpty() ? "\u0000" : apiKey, "[redacted]")
                .replaceAll("(?i)bearer\\s+\\S+", "[redacted]")
                .replaceAll("[\\r\\n\\t]", " ")
                .replaceAll("\\s{2,}", " ");
        sanitized = EMAIL_PATTERN.matcher(sanitized).replaceAll("[redacted-email]");
        sanitized = OTP_PATTERN.matcher(sanitized).replaceAll("[redacted-code]");
        return sanitized.substring(0, Math.min(sanitized.length(), 200));
    }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Email delivery is temporarily unavailable");
    }

    public void sendEmailVerificationOtp(String to, String otp) {
        String body =
                "Hello,\n\n"
                + "Your OTP for verifying your Mentorax email is:\n\n"
                + otp
                + "\n\nThis OTP is valid for 5 minutes.\n\n"
                + "Do not share this OTP with anyone.\n\n"
                + "Thank you,\nMentorax Team";

        sendEmail(
                to,
                "Verify your Mentorax email",
                body
        );
    }

    public void sendOtpEmail(String to, String otp) {
        String body =
                "Hello,\n\n"
                + "Your password-reset OTP is:\n\n"
                + otp
                + "\n\nThis OTP is valid for 5 minutes.\n\n"
                + "Do not share this OTP with anyone.\n\n"
                + "Thank you,\nMentorax Team";

        sendEmail(to, "Password Reset OTP", body);
    }

    public void sendEventRegistrationEmail(
            String to,
            String eventTitle,
            String eventDate,
            String location,
            String eventLink) {

        String body =
                "Hello,\n\n"
                + "You have successfully registered for the event.\n\n"
                + "Event: " + eventTitle + "\n"
                + "Date: " + eventDate + "\n"
                + "Location: " + location + "\n"
                + "Meeting Link: " + eventLink + "\n\n"
                + "Thank you for using Mentorax!";

        sendEmail(
                to,
                "Event Registration Successful",
                body
        );
    }
}