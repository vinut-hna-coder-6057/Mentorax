package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.repository.UserSkillRepository;
import com.alumni.alumni_connect.security.CurrentUserService;
import com.alumni.alumni_connect.config.RestTemplateConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Exercises the production RestTemplate configuration against a local HTTP stub. */
class MentorDiscoveryPythonIntegrationTest {
    private static final String GOAL = "data engineering analytics";
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<StubResponse> nextResponse = new AtomicReference<>(
            new StubResponse(200, successBody(), 0));
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    private HttpServer server;
    private ExecutorService serverExecutor;
    private UserRepository users;
    private UserSkillRepository userSkills;
    private CurrentUserService currentUser;
    private MentorDiscoveryService service;
    private List<User> approvedProfiles;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "mentorax-python-stub");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(serverExecutor);
        server.createContext("/rank", this::handleRank);
        server.start();

        users = mock(UserRepository.class);
        userSkills = mock(UserSkillRepository.class);
        currentUser = mock(CurrentUserService.class);
        User student = mock(User.class);
        when(student.getRole()).thenReturn("STUDENT");
        when(currentUser.requireUser()).thenReturn(student);

        User relevant = alumni(21L, "Private Mentor One", "data engineering analytics", "Data Engineer");
        User secondRelevant = alumni(22L, "Private Mentor Two", "data analytics", "Data Scientist");
        User irrelevantApproved = alumni(99L, "Other Approved Person", "gardening", "Gardener");
        approvedProfiles = List.of(relevant, secondRelevant, irrelevantApproved);
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc(
                eq("ALUMNI"), eq("APPROVED"), any(Long.class)))
                .thenReturn(approvedProfiles);
        when(userSkills.findByUser_IdIn(anyList())).thenReturn(List.of());

        RestTemplate restTemplate = new RestTemplateConfig().restTemplate();
        service = new MentorDiscoveryService(users, userSkills, currentUser, restTemplate);
        ReflectionTestUtils.setField(service, "aiEnabled", true);
        ReflectionTestUtils.setField(service, "aiServiceUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ReflectionTestUtils.setField(service, "rankingStrategy", "tfidf");
        nextResponse.set(new StubResponse(200, successBody(), 0));
        lastRequestBody.set(null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.shutdownNow();
    }

    @Test
    void pythonSuccessUsesHttpContractAndOnlyEligibleProfessionalFields() throws Exception {
        var response = service.discover(GOAL);

        assertFalse(response.fallbackUsed());
        assertEquals("TF-IDF ordering with Java profile evidence gate", response.rankingMethod());
        assertEquals(List.of(22L, 21L), response.recommendations().stream()
                .map(row -> row.id()).toList());
        JsonNode request = mapper.readTree(lastRequestBody.get());
        assertEquals(GOAL, request.path("goal").asText());
        assertEquals("tfidf", request.path("strategy").asText());
        assertEquals(List.of(21, 22), mapper.convertValue(request.path("candidates").findValuesAsText("id"),
                mapper.getTypeFactory().constructCollectionType(List.class, Integer.class)));
        for (JsonNode candidate : request.path("candidates")) {
            assertFalse(candidate.has("name"));
            assertFalse(candidate.has("email"));
            assertTrue(candidate.has("skills"));
        }
    }

    @Test
    void connectionFailureFallsBackAndMarksResponse() {
        ReflectionTestUtils.setField(service, "aiServiceUrl", "http://127.0.0.1:1");

        var response = service.discover(GOAL);

        assertSafeFallback(response);
    }

    @Test
    void readTimeoutFallsBackWithinConfiguredBound() {
        nextResponse.set(new StubResponse(200, successBody(), 6_500));
        long started = System.nanoTime();

        var response = service.discover(GOAL);

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertSafeFallback(response);
        assertTrue(elapsedMillis < 8_000, "read timeout should bound the request; took " + elapsedMillis + "ms");
    }

    @Test
    void httpFiveHundredFallsBackWithoutLeakingRemoteErrorBody() {
        nextResponse.set(new StubResponse(503, "{\"detail\":\"password=secret-token\"}", 0));

        var response = service.discover(GOAL);

        assertSafeFallback(response);
        assertFalse(response.toString().contains("secret-token"));
        assertFalse(response.toString().contains("password="));
    }

    @Test
    void semanticModelWarmingResponseUsesTheExistingSafeFallback() {
        ReflectionTestUtils.setField(service, "rankingStrategy", "semantic");
        nextResponse.set(new StubResponse(503,
                "{\"status\":\"not_ready\",\"strategy\":\"semantic\",\"semanticModel\":\"warming\"}", 0));

        var response = service.discover(GOAL);

        assertSafeFallback(response);
        assertTrue(response.message() == null || !response.message().contains("warming"));
    }

    @Test
    void malformedJsonFallsBackWithoutExposingParserDetails() {
        nextResponse.set(new StubResponse(200, "{not-json", 0));

        var response = service.discover(GOAL);

        assertSafeFallback(response);
        assertFalse(response.toString().contains("not-json"));
    }

    @Test
    void emptyRankingFallsBackToJavaOrdering() {
        nextResponse.set(new StubResponse(200,
                "{\"rankedIds\":[],\"scores\":{},\"rankingMethod\":\"TF-IDF cosine similarity\"}", 0));

        var response = service.discover(GOAL);

        assertSafeFallback(response);
    }

    @Test
    void unknownAndIneligibleIdsAreRejectedWithoutRecommendationLeakage() {
        nextResponse.set(new StubResponse(200,
                "{\"rankedIds\":[999,99,22],\"scores\":{\"999\":0.9,\"99\":0.8,\"22\":0.7},\"rankingMethod\":\"TF-IDF cosine similarity\"}", 0));

        var response = service.discover(GOAL);

        assertSafeFallback(response);
        assertTrue(response.recommendations().stream().noneMatch(row -> row.id() == 999L || row.id() == 99L));
    }

    @Test
    void weakAndNoMatchQueriesDoNotCallPythonWhenJavaEvidenceGateRejectsThem() {
        User weak = alumni(31L, "Weak Profile", "data analytics", "Data Analyst");
        when(users.findTop500ByRoleAndStatusAndIdGreaterThanOrderByIdAsc(
                eq("ALUMNI"), eq("APPROVED"), any(Long.class))).thenReturn(List.of(weak));
        ReflectionTestUtils.setField(service, "minimumRelevance", 0.75);

        // This profile matches one of two meaningful terms (analytics), so it
        // has nonzero but insufficient local evidence at the configured gate.
        var weakResponse = service.discover("data engineering analytics");
        var noMatchResponse = service.discover("quantum computing");

        assertTrue(weakResponse.recommendations().isEmpty());
        assertTrue(noMatchResponse.recommendations().isEmpty());
        assertNull(lastRequestBody.get());
    }

    private void assertSafeFallback(com.alumni.alumni_connect.dto.MentorDiscoveryResponse response) {
        assertTrue(response.fallbackUsed());
        assertEquals("Java keyword overlap with profile evidence gate", response.rankingMethod());
        assertFalse(response.recommendations().isEmpty());
        assertTrue(response.recommendations().stream().allMatch(row -> row.evidence() != null && !row.evidence().isEmpty()));
        assertTrue(response.recommendations().stream().allMatch(row -> approvedProfiles.stream()
                .anyMatch(profile -> profile.getId().equals(row.id()))));
        assertTrue(response.recommendations().stream().noneMatch(row -> row.id() == 99L || row.id() == 999L));
    }

    private void handleRank(HttpExchange exchange) throws IOException {
        lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        StubResponse current = nextResponse.get();
        try {
            if (current.delayMillis() > 0) Thread.sleep(current.delayMillis());
            byte[] body = current.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(current.status(), body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // The client intentionally closes the exchange in the timeout test.
        } finally {
            exchange.close();
        }
    }

    private static User alumni(long id, String name, String skills, String role) {
        User alumni = mock(User.class);
        when(alumni.getId()).thenReturn(id);
        when(alumni.getName()).thenReturn(name);
        when(alumni.getSkills()).thenReturn(skills);
        when(alumni.getJobRole()).thenReturn(role);
        when(alumni.getCompany()).thenReturn("Example Co");
        return alumni;
    }

    private static String successBody() {
        return "{\"rankedIds\":[22,21],\"scores\":{\"21\":0.8,\"22\":0.9},"
                + "\"rankingMethod\":\"TF-IDF cosine similarity\"}";
    }

    private record StubResponse(int status, String body, long delayMillis) {}
}
