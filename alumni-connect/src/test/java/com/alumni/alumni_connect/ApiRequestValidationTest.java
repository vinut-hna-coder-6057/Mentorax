package com.alumni.alumni_connect;

import com.alumni.alumni_connect.dto.*;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApiRequestValidationTest {
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test void signupRejectsAdminRole() {
        assertFalse(validator.validate(new SignupRequest("Name","n@example.com","password123","ADMIN",null,null,null,null,null,null,null,null,null,null,null,null,null,null)).isEmpty());
    }
    @Test void rejectsMalformedEmailShortPasswordAndOversizedFields() {
        assertFalse(validator.validate(new SignupRequest("Name","bad","1234567","STUDENT",null,null,null,null,null,null,null,null,null,null,null,null,null,null)).isEmpty());
        assertFalse(validator.validate(new SignupRequest("x".repeat(121),"n@example.com","password123","STUDENT",null,null,null,null,null,null,null,null,null,null,null,null,null,null)).isEmpty());
    }
    @Test void loginAcceptsOptionalRoleButNoAccountStateFields() {
        assertEquals(3, LoginRequest.class.getRecordComponents().length);
        assertEquals("role", LoginRequest.class.getRecordComponents()[2].getName());
        assertFalse(java.util.Arrays.stream(LoginRequest.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("status")
                        || component.getName().equals("emailVerified")));
    }
    @Test void signupAndProfileDtosIgnorePrivilegedCompatibilityFields() throws Exception {
        ObjectMapper mapper=new ObjectMapper();
        SignupRequest signup=mapper.readValue("{\"name\":\"N\",\"email\":\"n@example.com\",\"password\":\"password123\",\"role\":\"STUDENT\",\"status\":\"ADMIN\",\"emailVerified\":true,\"id\":9}",SignupRequest.class);
        assertEquals("STUDENT",signup.role());
        UserProfileUpdateRequest update=mapper.readValue("{\"name\":\"Changed\",\"role\":\"ADMIN\",\"status\":\"APPROVED\",\"email\":\"other@example.com\",\"emailVerified\":true}",UserProfileUpdateRequest.class);
        assertEquals("Changed",update.name());
        assertEquals(15,UserProfileUpdateRequest.class.getRecordComponents().length);
    }
}
