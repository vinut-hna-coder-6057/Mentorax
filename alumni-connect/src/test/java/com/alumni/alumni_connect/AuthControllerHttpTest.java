package com.alumni.alumni_connect;

import com.alumni.alumni_connect.controller.AuthController;
import com.alumni.alumni_connect.service.AuthService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuthControllerHttpTest {

    private MockMvc mockMvc;

    private AuthService authService;

    @BeforeEach
    void setUp() {

        authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        mockMvc =
                MockMvcBuilders
                        .standaloneSetup(controller)
                        .setControllerAdvice(
                                new com.alumni.alumni_connect.exception.ApiExceptionHandler()
                        )
                        .build();
    }


    // =========================================================
    // INVALID CREDENTIALS
    // =========================================================

    @Test
    void loginShouldReturn401WhenCredentialsAreInvalid()
            throws Exception {

        Mockito.when(
                authService.login(Mockito.any(com.alumni.alumni_connect.dto.LoginRequest.class))
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid credentials"
                )
        );

        mockMvc.perform(
                post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "student@gmail.com",
                                    "role": "STUDENT",
                                    "password": "wrongPassword"
                                }
                                """)
        )
        .andExpect(status().isUnauthorized())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Invalid credentials"));
    }


    // =========================================================
    // PENDING ACCOUNT
    // =========================================================

    @Test
    void loginShouldReturn403WhenAccountIsPending()
            throws Exception {

        Mockito.when(
                authService.login(Mockito.any(com.alumni.alumni_connect.dto.LoginRequest.class))
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Account pending approval"
                )
        );

        mockMvc.perform(
                post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "pending@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Account pending approval"));
    }


    // =========================================================
    // REJECTED ACCOUNT
    // =========================================================

    @Test
    void loginShouldReturn403WhenAccountIsRejected()
            throws Exception {

        Mockito.when(
                authService.login(Mockito.any(com.alumni.alumni_connect.dto.LoginRequest.class))
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Account rejected"
                )
        );

        mockMvc.perform(
                post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "rejected@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Account rejected"));
    }


    // =========================================================
    // INACTIVE ACCOUNT
    // =========================================================

    @Test
    void loginShouldReturn403WhenAccountIsNotActive()
            throws Exception {

        Mockito.when(
                authService.login(Mockito.any(com.alumni.alumni_connect.dto.LoginRequest.class))
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Account not active"
                )
        );

        mockMvc.perform(
                post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "inactive@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isForbidden())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Account not active"));
    }


    // =========================================================
    // SUCCESSFUL LOGIN
    // =========================================================

    @Test
    void loginShouldReturn200AndJwtWhenLoginSucceeds()
            throws Exception {

        Mockito.when(
                authService.login(Mockito.any(com.alumni.alumni_connect.dto.LoginRequest.class))
        ).thenReturn("mock.jwt.token");

        mockMvc.perform(
                post("/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "email": "student@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isOk())
        .andExpect(content().string("mock.jwt.token"));
    }


    // =========================================================
    // DUPLICATE SIGNUP
    // =========================================================

    @Test
    void signupShouldReturn409WhenEmailAlreadyExists()
            throws Exception {

        Mockito.when(
                authService.signup(Mockito.any(com.alumni.alumni_connect.dto.SignupRequest.class))
        ).thenThrow(
                new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Email already exists"
                )
        );

        mockMvc.perform(
                post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Student",
                                    "email": "existing@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(
                MediaType.APPLICATION_JSON
        ))
        .andExpect(jsonPath("$.error")
                .value("Email already exists"));
    }


    // =========================================================
    // SUCCESSFUL SIGNUP
    // =========================================================

    @Test
    void signupShouldReturn200WhenRegistrationSucceeds()
            throws Exception {

        Mockito.when(
                authService.signup(Mockito.any(com.alumni.alumni_connect.dto.SignupRequest.class))
        ).thenReturn("Signup successful");

        mockMvc.perform(
                post("/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "name": "Student",
                                    "email": "newstudent@gmail.com",
                                    "role": "STUDENT",
                                    "password": "password123"
                                }
                                """)
        )
        .andExpect(status().isOk())
        .andExpect(content().string("Signup successful"));
    }
}
