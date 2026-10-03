package com.alumni.alumni_connect.service;

import java.io.IOException;
import java.net.SocketTimeoutException;
import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class EmailServiceTest {

    private static final String API_URL = "https://api.resend.com/emails";
    private static final String API_KEY = "test-resend-key";
    private static final String FROM = "mentorax@example.test";

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        emailService = new EmailService(API_KEY, FROM, restTemplate);
    }

    @Test
    void sendsPasswordResetOtpThroughResend() {
        expectRequest("Password Reset OTP")
                .andExpect(jsonPath("$.to[0]").value("student@example.test"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("654321")))
                .andRespond(withSuccess("{\"id\":\"email-id\"}", APPLICATION_JSON));

        emailService.sendOtpEmail("student@example.test", "654321");

        server.verify();
    }

    @Test
    void passwordOtpEmailsUseEachRequestedRecipient() {
        expectRequest("Password Reset OTP")
                .andExpect(jsonPath("$.to[0]").value("first@example.test"))
                .andRespond(withSuccess("{\"id\":\"email-id-1\"}", APPLICATION_JSON));
        expectRequest("Password Reset OTP")
                .andExpect(jsonPath("$.to[0]").value("second@example.test"))
                .andRespond(withSuccess("{\"id\":\"email-id-2\"}", APPLICATION_JSON));

        emailService.sendOtpEmail("first@example.test", "123456");
        emailService.sendOtpEmail("second@example.test", "654321");

        server.verify();
    }

    @Test
    void sendsEventRegistrationDetailsThroughResend() {
        expectRequest("Event Registration Successful")
                .andExpect(jsonPath("$.to[0]").value("student@example.test"))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Mentorax launch")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("2026-08-20")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("Online")))
                .andExpect(jsonPath("$.text").value(org.hamcrest.Matchers.containsString("https://example.test/event")))
                .andRespond(withSuccess("{\"id\":\"email-id\"}", APPLICATION_JSON));

        emailService.sendEventRegistrationEmail(
                "student@example.test",
                "Mentorax launch",
                "2026-08-20",
                "Online",
                "https://example.test/event");

        server.verify();
    }

    @Test
    void providerErrorsBecomeSanitizedServiceUnavailableResponses() {
        server.expect(requestTo(API_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(APPLICATION_JSON)
                        .body("{\"message\":\"sensitive provider response\"}"));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.sendOtpEmail("student@example.test", "123456"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertEquals("Email delivery is temporarily unavailable", exception.getReason());
        server.verify();
    }

    @Test
    void connectionFailuresBecomeServiceUnavailableResponses() {
        server.expect(requestTo(API_URL))
                .andRespond(withException(new IOException("connection details")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.sendOtpEmail("student@example.test", "123456"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertEquals("Email delivery is temporarily unavailable", exception.getReason());
        server.verify();
    }

    @Test
    void providerTimeoutBecomesServiceUnavailableResponse() {
        server.expect(requestTo(API_URL))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> emailService.sendOtpEmail("student@example.test", "123456"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
        assertEquals("Email delivery is temporarily unavailable", exception.getReason());
        server.verify();
    }

    @Test
    void missingResendConfigurationFailsWithoutMakingARequest() {
        for (EmailService unconfigured : new EmailService[] {
                new EmailService("", FROM, restTemplate),
                new EmailService(API_KEY, "", restTemplate)
        }) {
            ResponseStatusException exception = assertThrows(
                    ResponseStatusException.class,
                    () -> unconfigured.sendOtpEmail("student@example.test", "123456"));

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
            assertEquals("Email delivery is not configured", exception.getReason());
        }
        server.verify();
    }

    @Test
    void doesNotSendEmailWhenDatabaseTransactionRollsBack() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:email-service-rollback;DB_CLOSE_DELAY=-1", "sa", "");
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        transaction.execute(status -> {
            emailService.sendOtpEmail("student@example.test", "123456");
            status.setRollbackOnly();
            return null;
        });

        server.verify();
    }

    private ResponseActions expectRequest(String subject) {
        return server.expect(requestTo(API_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AUTHORIZATION, "Bearer " + API_KEY))
                .andExpect(content().contentType(APPLICATION_JSON))
                .andExpect(jsonPath("$.from").value(FROM))
                .andExpect(jsonPath("$.subject").value(subject));
    }
}
