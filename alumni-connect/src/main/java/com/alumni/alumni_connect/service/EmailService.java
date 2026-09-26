package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.config.*;
import com.alumni.alumni_connect.controller.*;
import com.alumni.alumni_connect.dto.*;
import com.alumni.alumni_connect.entity.*;
import com.alumni.alumni_connect.exception.*;
import com.alumni.alumni_connect.repository.*;
import com.alumni.alumni_connect.security.*;
import com.alumni.alumni_connect.service.*;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.mail.SimpleMailMessage;

import org.springframework.mail.javamail.JavaMailSender;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service

public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    // =====================================
    // MAIL SENDER
    // =====================================

    @Autowired

    private JavaMailSender mailSender;

    // =====================================
    // EVENT REGISTRATION EMAIL
    // =====================================

    public void sendEventRegistrationEmail(

            String to,

            String eventTitle,

            String eventDate,

            String location,

            String eventLink

    ) {

        try {

            SimpleMailMessage message =

                    new SimpleMailMessage();

            // =====================================
            // RECEIVER
            // =====================================

            message.setTo(to);

            // =====================================
            // SUBJECT
            // =====================================

            message.setSubject(

                    "ðŸŽ‰ Event Registration Successful"
            );

            // =====================================
            // EMAIL BODY
            // =====================================

            message.setText(

                    "Hello,\n\n"

                            +

                            "You have successfully registered for the event.\n\n"

                            +

                            "====================================\n"

                            +

                            "ðŸ“Œ Event Details\n"

                            +

                            "====================================\n\n"

                            +

                            "ðŸŽ¯ Event: "
                            + eventTitle + "\n\n"

                            +

                            "ðŸ“… Date: "
                            + eventDate + "\n\n"

                            +

                            "ðŸ“ Location: "
                            + location + "\n\n"

                            +

                            "ðŸ”— Meeting Link:\n"
                            + eventLink + "\n\n"

                            +

                            "====================================\n\n"

                            +

                            "We look forward to seeing you there.\n\n"

                            +

                            "Thank you for using Alumni Connect ðŸš€"
            );

            // =====================================
            // SEND EMAIL
            // =====================================

            mailSender.send(message);


        }

        catch (Exception e) {
            log.error("Event-registration email delivery failed ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is temporarily unavailable");
        }
    }
    // =====================================
// OTP EMAIL
// =====================================

    public void sendOtpEmail(

            String to,

            String otp

    ) {

        try {

            SimpleMailMessage message =

                    new SimpleMailMessage();

            // =====================================
            // RECEIVER
            // =====================================

            message.setTo(to);

            // =====================================
            // SUBJECT
            // =====================================

            message.setSubject(

                    "OTP Verification"
            );

            // =====================================
            // EMAIL BODY
            // =====================================

            message.setText(

                    "Hello,\n\n"

                            +

                            "Your OTP for Alumni Connect is:\n\n"

                            +

                            otp

                            +

                            "\n\n"

                            +

                            "This OTP is valid for 5 minutes.\n\n"

                            +

                            "Do not share this OTP with anyone.\n\n"

                            +

                            "Thank you,\n"

                            +

                            "Alumni Connect Team"
            );

            // =====================================
            // SEND EMAIL
            // =====================================

            mailSender.send(message);

        }

        catch (Exception e) {
            log.error("Password-reset email delivery failed ({})", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Email delivery is temporarily unavailable");
        }
    }
public void sendEmailVerificationOtp(String to, String otp) {

    try {

        SimpleMailMessage message = new SimpleMailMessage();

        // RECEIVER
        message.setTo(to);

        // SUBJECT
        message.setSubject("Verify your Alumni Connect email");

        // EMAIL BODY
        message.setText(
                "Hello,\n\n"
                        + "Your OTP for verifying your Alumni Connect email is:\n\n"
                        + otp
                        + "\n\n"
                        + "This OTP is valid for 5 minutes.\n\n"
                        + "Do not share this OTP with anyone.\n\n"
                        + "Thank you,\n"
                        + "Alumni Connect Team"
        );

        // SEND EMAIL
        mailSender.send(message);

    } catch (Exception e) {

        log.error(
                "Email verification delivery failed ({})",
                e.getClass().getSimpleName()
        );

        throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Email delivery is temporarily unavailable"
        );
}
}
}


