package com.alumni.alumni_connect;

import com.alumni.alumni_connect.dto.ConnectionResponse;
import com.alumni.alumni_connect.entity.Connection;
import com.alumni.alumni_connect.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResponseDtoSerializationTest {
    @Test void connectionJsonKeepsFlutterUserFieldsWithoutSensitivePersistenceFields() throws Exception {
        User alice=new User("Alice","alice@example.com","hash-secret","STUDENT","APPROVED");
        User bob=new User("Bob","bob@example.com","hash-secret","ALUMNI","APPROVED");
        alice.setRollno("private-roll");
        alice.setSection("private-section");
        Connection connection=new Connection(); connection.setRequester(alice); connection.setReceiver(bob); connection.setStatus("PENDING");
        String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(ConnectionResponse.from(connection));
        assertTrue(json.contains("\"requester\""));
        assertFalse(json.contains("alice@example.com"));
        assertFalse(json.contains("private-roll"));
        assertFalse(json.contains("private-section"));
        assertFalse(json.contains("hash-secret")); assertFalse(json.contains("emailVerified"));
        assertFalse(json.contains("studentProfile")); assertFalse(json.contains("alumniProfile"));

        connection.setStatus("ACCEPTED");
        String acceptedJson=new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(ConnectionResponse.from(connection));
        assertTrue(acceptedJson.contains("alice@example.com"));
        assertFalse(acceptedJson.contains("private-roll"));
        assertFalse(acceptedJson.contains("private-section"));
    }
}
