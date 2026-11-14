package com.alumni.alumni_connect.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApplicationPropertiesResolutionTest {
    @Test
    void optionalMentorApiTokenResolvesWhenEnvironmentVariableIsAbsent() throws IOException {
        Properties properties = PropertiesLoaderUtils.loadProperties(
                new FileSystemResource("src/main/resources/application.properties"));
        ConfigurableEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove("systemProperties");
        environment.getPropertySources().remove("systemEnvironment");
        environment.getPropertySources().addFirst(
                new PropertiesPropertySource("application.properties", properties));

        assertEquals("", environment.getProperty("AI_MENTOR_API_TOKEN", ""));
    }
}
