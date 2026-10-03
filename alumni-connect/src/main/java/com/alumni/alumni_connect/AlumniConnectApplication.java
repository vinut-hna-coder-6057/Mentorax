package com.alumni.alumni_connect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AlumniConnectApplication {
    public static void main(String[] args) {
        SpringApplication.run(AlumniConnectApplication.class, args);
    }
}