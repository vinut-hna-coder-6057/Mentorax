package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.ExperienceClaimRepository;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExperienceClaimControllerTest {
    @DynamicPropertySource
    static void evidenceTestKey(DynamicPropertyRegistry registry) {
        registry.add("EXPERIENCE_EVIDENCE_ENCRYPTION_KEY",
                () -> Base64.getEncoder().encodeToString(
                        "synthetic-test-evidence-key-32!!".getBytes(StandardCharsets.UTF_8)));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository userRepository;
    @Autowired private ExperienceClaimRepository experienceClaimRepository;

    private User student;
    private User otherStudent;
    private User admin;

    @BeforeEach
    void setUp() {
        student = userRepository.save(new User("Student", "student-" + UUID.randomUUID() + "@example.test", "pw", "STUDENT", "APPROVED"));
        student.setEmailVerified(true);
        otherStudent = userRepository.save(new User("Other Student", "other-" + UUID.randomUUID() + "@example.test", "pw", "STUDENT", "APPROVED"));
        otherStudent.setEmailVerified(true);
        admin = userRepository.save(new User("Admin", "admin-" + UUID.randomUUID() + "@example.test", "pw", "ADMIN", "APPROVED"));
        admin.setEmailVerified(true);
    }

    @Test
    void studentCanCreateAndFetchOwnClaim() throws Exception {
        mockMvc.perform(post("/experience-claims")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimJson(UUID.randomUUID().toString(), "PENDING")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.company").value("Google"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.aiStatus").value("PENDING"));

        mockMvc.perform(get("/experience-claims/mine")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void repeatedCreateWithSameRequestIdDoesNotCreateDuplicateClaimsAndClientCannotSetStatus() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String request = claimJson(requestId, "VERIFIED");
        String first = mockMvc.perform(post("/experience-claims")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(post("/experience-claims")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT"))
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(
                                org.hamcrest.Matchers.equalTo(
                                        com.jayway.jsonpath.JsonPath.read(first, "$.id"))));
        mockMvc.perform(get("/experience-claims/mine")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void userCannotViewOtherUsersClaim() throws Exception {
        var claim = new com.alumni.alumni_connect.entity.ExperienceClaim();
        claim.setUser(admin);
        claim.setCompany("Google");
        claim.setJobTitle("SWE");
        claim.setSummary("Built backend services");
        claim.setEvidenceSummary("Offer letter");
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        var saved = experienceClaimRepository.save(claim);

        mockMvc.perform(get("/experience-claims/{id}", saved.getId())
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanReviewClaim() throws Exception {
        var claim = new com.alumni.alumni_connect.entity.ExperienceClaim();
        claim.setUser(student);
        claim.setCompany("Google");
        claim.setJobTitle("SWE");
        claim.setSummary("Built backend services");
        claim.setEvidenceSummary("Offer letter");
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        claim = experienceClaimRepository.save(claim);

        mockMvc.perform(post("/experience-claims/{id}/review", claim.getId())
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"NEEDS_INFO\",\"note\":\"Provide issuer details\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_INFO"))
                .andExpect(jsonPath("$.aiStatus").value("PENDING"));

        mockMvc.perform(post("/experience-claims/{id}/review", claim.getId())
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"VERIFIED\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidEvidenceIsRejectedAndOnlyOwnerCanRetrievePrivateEvidence() throws Exception {
        var claim = saveClaim(student);
        mockMvc.perform(multipart("/experience-claims/{id}/evidence", claim.getId())
                        .file(new MockMultipartFile("file", "proof.pdf", "application/pdf",
                                "not a PDF".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void malformedPdfWithHeaderAndEofIsRejectedBeforeStorage() throws Exception {
        var claim = saveClaim(student);
        byte[] malformedPdf = "%PDF-1.7\nnot a PDF structure\n%%EOF".getBytes(StandardCharsets.US_ASCII);

        mockMvc.perform(multipart("/experience-claims/{id}/evidence", claim.getId())
                        .file(new MockMultipartFile("file", "malformed.pdf", "application/pdf", malformedPdf))
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isUnsupportedMediaType());
        org.junit.jupiter.api.Assertions.assertNull(
                experienceClaimRepository.findById(claim.getId()).orElseThrow().getEncryptedEvidence());
    }

    @Test
    void pdfContainingOnlyHeaderIsRejectedBeforeStorage() throws Exception {
        var claim = saveClaim(student);

        mockMvc.perform(multipart("/experience-claims/{id}/evidence", claim.getId())
                        .file(new MockMultipartFile("file", "header-only.pdf", "application/pdf",
                                "%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isUnsupportedMediaType());
        org.junit.jupiter.api.Assertions.assertNull(
                experienceClaimRepository.findById(claim.getId()).orElseThrow().getEncryptedEvidence());
    }

    @Test
    void truncatedPdfIsRejectedBeforeStorage() throws Exception {
        var claim = saveClaim(student);
        byte[] validPdf = validPdf();
        byte[] truncatedPdf = java.util.Arrays.copyOf(validPdf, validPdf.length - 8);

        mockMvc.perform(multipart("/experience-claims/{id}/evidence", claim.getId())
                        .file(new MockMultipartFile("file", "truncated.pdf", "application/pdf", truncatedPdf))
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isUnsupportedMediaType());
        org.junit.jupiter.api.Assertions.assertNull(
                experienceClaimRepository.findById(claim.getId()).orElseThrow().getEncryptedEvidence());
    }

    @Test
    void validEvidenceIsEncryptedAndOwnerCanRetrieveOrDeleteIt() throws Exception {
        var claim = saveClaim(student);
        byte[] pdf = validPdf();
        mockMvc.perform(multipart("/experience-claims/{id}/evidence", claim.getId())
                        .file(new MockMultipartFile("file", "offer.pdf", "application/pdf", pdf))
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evidenceAvailable").value(true))
                .andExpect(jsonPath("$.aiStatus").value("UNAVAILABLE"));

        var stored = experienceClaimRepository.findById(claim.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertFalse(
                java.util.Arrays.equals(pdf, stored.getEncryptedEvidence()));

        mockMvc.perform(get("/experience-claims/{id}/evidence", claim.getId())
                        .header("Authorization", bearer(otherStudent.getEmail(), "STUDENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/experience-claims/{id}/evidence", claim.getId())
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .bytes(pdf));
        mockMvc.perform(delete("/experience-claims/{id}/evidence", claim.getId())
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/experience-claims/{id}/evidence", claim.getId())
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isNotFound());
    }

    private byte[] validPdf() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void pendingClaimCannotBeMovedBackwardsByAdmin() throws Exception {
        var claim = saveClaim(student);
        mockMvc.perform(post("/experience-claims/{id}/review", claim.getId())
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PENDING\"}"))
                .andExpect(status().isConflict());
    }

    private com.alumni.alumni_connect.entity.ExperienceClaim saveClaim(User owner) {
        var claim = new com.alumni.alumni_connect.entity.ExperienceClaim();
        claim.setUser(owner);
        claim.setRequestId(UUID.randomUUID().toString());
        claim.setCompany("Google");
        claim.setJobTitle("SWE");
        claim.setSummary("Built backend services");
        claim.setStatus("PENDING");
        claim.setAiStatus("PENDING");
        return experienceClaimRepository.saveAndFlush(claim);
    }

    private String claimJson(String requestId, String attemptedStatus) {
        return "{\"company\":\"Google\",\"jobTitle\":\"SWE\",\"requestId\":\"" + requestId
                + "\",\"summary\":\"Built backend services\",\"status\":\"" + attemptedStatus
                + "\",\"aiStatus\":\"VERIFIED\",\"reviewerEmail\":\"attacker@example.test\"}";
    }

    private String bearer(String email, String role) {
        return "Bearer " + jwtUtil.generateToken(email, role);
    }
}
