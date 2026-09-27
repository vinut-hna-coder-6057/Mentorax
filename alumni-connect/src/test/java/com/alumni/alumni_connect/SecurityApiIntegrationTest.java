package com.alumni.alumni_connect;

import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.repository.UserRepository;
import com.alumni.alumni_connect.security.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SecurityApiIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;

    private User student;
    private User admin;

    @BeforeEach
    void setUpUsers() {
        String suffix = UUID.randomUUID().toString();
        student = users.save(new User("Security Student", "security-student-" + suffix + "@example.test",
                "not-used", "STUDENT", "APPROVED"));
        admin = users.save(new User("Security Admin", "security-admin-" + suffix + "@example.test",
                "not-used", "ADMIN", "APPROVED"));
    }

    @Test
    void protectedEndpointWithoutAuthenticationReturnsJson401() throws Exception {
        mockMvc.perform(get("/users"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"Authentication required\"}"));
    }

    @Test
    void nonAdminCannotAccessAdminListing() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isForbidden())
                .andExpect(content().json("{\"error\":\"Access denied\"}"));
    }

    @Test
    void adminCanAccessAdminListing() throws Exception {
        mockMvc.perform(get("/users")
                        .header("Authorization", bearer(admin.getEmail(), "ADMIN")))
                .andExpect(status().isOk());
    }
    @Test
void unauthenticatedUserCannotAccessStudents() throws Exception {
    mockMvc.perform(get("/users/students"))
            .andExpect(status().isUnauthorized());
}

@Test
void unauthenticatedUserCannotAccessAlumni() throws Exception {
    mockMvc.perform(get("/users/alumni"))
            .andExpect(status().isUnauthorized());
}
    @Test
    void missingUserReturns404() throws Exception {
        mockMvc.perform(get("/users/{id}", Long.MAX_VALUE)
                        .header("Authorization", bearer(student.getEmail(), "STUDENT")))
                .andExpect(status().isNotFound())
                .andExpect(content().json("{\"error\":\"User not found\"}"));
    }

    private String bearer(String email, String role) {
        return "Bearer " + jwtUtil.generateToken(email, role);
    }
    @Test
void userCannotSendConnectionRequestToSelf() throws Exception {
    mockMvc.perform(post("/connections/{receiverId}", student.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isBadRequest());
}
@Test
void duplicateConnectionRequestIsRejected() throws Exception {
    mockMvc.perform(post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isCreated());

    mockMvc.perform(post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isConflict());
}

@Test
void receiverCanAcceptConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isOk());

}
@Test
void requesterCannotAcceptOwnConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT"))
                    .param("status", "ACCEPTED"))
            .andExpect(status().isForbidden());
}
@Test
void receiverCanRejectConnectionRequest() throws Exception {
    String response = mockMvc.perform(
            post("/connections/{receiverId}", admin.getId())
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    long connectionId = new ObjectMapper()
            .readTree(response)
            .get("id")
            .asLong();

    mockMvc.perform(put("/connections/{id}", connectionId)
                    .header("Authorization",
                            bearer(admin.getEmail(), "ADMIN"))
                    .param("status", "REJECTED"))
            .andExpect(status().isOk());
}
@Test
void authenticatedUserCanGetConnections() throws Exception {
    mockMvc.perform(get("/connections")
                    .header("Authorization",
                            bearer(student.getEmail(), "STUDENT")))
            .andExpect(status().isOk());
}
}
