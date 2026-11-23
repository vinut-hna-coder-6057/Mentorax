package com.alumni.alumni_connect.service;

/** Provider explicitly determined that eligible evidence does not answer the question. */
public class GroundedInsufficientEvidenceException extends RuntimeException {
    public GroundedInsufficientEvidenceException() {
        super("Eligible evidence was insufficient for a grounded answer");
    }
}
