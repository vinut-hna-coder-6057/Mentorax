package com.alumni.alumni_connect.repository;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlumniRepository extends JpaRepository<User, Long> {

    // ðŸ”¥ Get only approved users
    List<User> findByStatus(String status);
}
