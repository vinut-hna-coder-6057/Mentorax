package com.alumni.alumni_connect;

import com.alumni.alumni_connect.controller.AuthController;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.service.AuthService;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    // =========================================================
    // LOGIN TESTS
    // =========================================================

    @Test
    void loginShouldReturnInvalidCredentialsWhenUserDoesNotExist() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User loginUser = new User();
        loginUser.setEmail("unknown@gmail.com");
        loginUser.setRole("STUDENT");
        loginUser.setPassword("password123");

        when(authService.login(loginUser))
                .thenReturn("Invalid credentials");

        Object result = controller.login(loginUser);

        assertEquals(
                "Invalid credentials",
                result
        );
    }


    @Test
    void loginShouldReturnInvalidCredentialsWhenPasswordIsWrong() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User loginUser = new User();
        loginUser.setEmail("student@gmail.com");
        loginUser.setRole("STUDENT");
        loginUser.setPassword("wrongPassword");

        when(authService.login(loginUser))
                .thenReturn("Invalid credentials");

        Object result = controller.login(loginUser);

        assertEquals(
                "Invalid credentials",
                result
        );
    }


    @Test
    void loginShouldReturnWaitApprovalWhenUserIsPending() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User loginUser = new User();
        loginUser.setEmail("pending@gmail.com");
        loginUser.setRole("STUDENT");
        loginUser.setPassword("password123");

        when(authService.login(loginUser))
                .thenReturn("WAIT_APPROVAL");

        Object result = controller.login(loginUser);

        assertEquals(
                "WAIT_APPROVAL",
                result
        );
    }


    @Test
    void loginShouldReturnRejectedWhenUserIsRejected() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User loginUser = new User();
        loginUser.setEmail("rejected@gmail.com");
        loginUser.setRole("STUDENT");
        loginUser.setPassword("password123");

        when(authService.login(loginUser))
                .thenReturn("ACCOUNT_REJECTED");

        Object result = controller.login(loginUser);

        assertEquals(
                "ACCOUNT_REJECTED",
                result
        );
    }


    @Test
    void loginShouldReturnJwtWhenUserIsApproved() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User loginUser = new User();
        loginUser.setEmail("student@gmail.com");
        loginUser.setRole("STUDENT");
        loginUser.setPassword("password123");

        String expectedJwt = "mock.jwt.token";

        when(authService.login(loginUser))
                .thenReturn(expectedJwt);

        Object result = controller.login(loginUser);

        assertEquals(
                expectedJwt,
                result
        );
    }


    // =========================================================
    // SIGNUP TESTS
    // =========================================================

    @Test
    void signupShouldReturnSuccessWhenRegistrationSucceeds() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User user = new User();
        user.setEmail("newstudent@gmail.com");
        user.setRole("STUDENT");
        user.setPassword("password123");

        when(authService.signup(user))
                .thenReturn("Signup successful");

        Object result = controller.signup(user);

        assertEquals(
                "Signup successful",
                result
        );
    }


    @Test
    void signupShouldReturnErrorWhenEmailAlreadyExists() {

        AuthService authService =
                Mockito.mock(AuthService.class);

        AuthController controller =
                new AuthController(authService);

        User user = new User();
        user.setEmail("existing@gmail.com");
        user.setRole("STUDENT");
        user.setPassword("password123");

        when(authService.signup(user))
                .thenReturn("Email already registered");

        Object result = controller.signup(user);

        assertEquals(
                "Email already registered",
                result
        );
    }
}