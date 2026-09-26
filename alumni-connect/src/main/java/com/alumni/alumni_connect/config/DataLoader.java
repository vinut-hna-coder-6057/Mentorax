package com.alumni.alumni_connect.config;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration
public class DataLoader {

    @Bean
    CommandLineRunner initDatabase(UserRepository repository, BCryptPasswordEncoder encoder) {
        return args -> {

            // ðŸ”¥ avoid duplicate inserts
            if (repository.count() > 0) {
                return;
            }

            // ðŸ”¥ Admin
            User admin = new User();
            admin.setName("Admin");
            admin.setEmail("admin@gmail.com");
            admin.setPassword(encoder.encode("admin")); // âœ… FIXED
            admin.setRole("ADMIN");
            admin.setStatus("APPROVED");
            repository.save(admin);

            // ðŸ”¥ Student
            User student = new User();
            student.setName("Rahul");
            student.setEmail("rahul@gmail.com");
            student.setPassword(encoder.encode("123")); // âœ… FIXED
            student.setRole("STUDENT");
            student.setStatus("APPROVED");
            student.setPassoutYear("2024");
            student.setCollege("XYZ College");
            student.setRollno("101");
            student.setSection("A");
            repository.save(student);

            // ðŸ”¥ Alumni
            User alumni = new User();
            alumni.setName("Anita");
            alumni.setEmail("anita@gmail.com");
            alumni.setPassword(encoder.encode("123")); // âœ… FIXED
            alumni.setRole("ALUMNI");
            alumni.setStatus("APPROVED");
            alumni.setPassoutYear("2020");
            alumni.setRollno("55");
            repository.save(alumni);

            System.out.println("ðŸ”¥ Sample users created with encrypted passwords");
        };
    }
}
