package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.StructuredEvidenceExtraction;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SensitiveEvidenceContentValidator {
    private static final Pattern LABELED_CREDENTIAL = Pattern.compile(
            "(?i)\\b(?:one[\\s-]*time(?:\\s+(?:password|passcode|code))?"
                    + "|otp(?:\\s+code)?"
                    + "|(?:verification|security|authentication|auth)\\s+(?:code|passcode|token)"
                    + "|password|passphrase|pin"
                    + "|(?:api|client)\\s*[-_ ]?key"
                    + "|(?:access|refresh|session|authentication|auth)\\s*[-_ ]?token"
                    + "|(?:client|private)\\s*[-_ ]?secret"
                    + "|secret(?:\\s*[-_ ]?key)?"
                    + "|authorization(?:\\s+token)?"
                    + "|bearer(?:\\s+token)?"
                    + "|(?:bank\\s+)?account\\s*(?:number|no\\.?|id|identifier)"
                    + "|(?:credit|debit)?\\s*card\\s*(?:number|no\\.?)"
                    + "|(?:routing|sort)\\s*(?:number|code)"
                    + "|(?:card\\s+)?(?:cvv|cvc|security\\s+code)"
                    + "|credentials?|token)\\b"
                    + "(?:\\s*\\([^)]*\\))?(?:\\s*(?:is|was|[:=#])\\s*|\\s+)"
                    + "(?<value>[^\\r\\n,;|]{1,256})");
    private static final Pattern BEARER_CREDENTIAL =
            Pattern.compile("\\bBearer\\s+[A-Za-z0-9._~+/-]+=*", Pattern.CASE_INSENSITIVE);
    private static final Pattern KNOWN_SECRET_TOKEN = Pattern.compile(
            "\\b(?:sk-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}"
                    + "|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"
                    + "\\.[A-Za-z0-9_-]{10,})\\b");

    private SensitiveEvidenceContentValidator() {}

    static boolean containsSensitiveContent(JsonNode value) {
        if (value == null || value.isNull()) {
            return false;
        }
        if (value.isTextual()) {
            return containsSensitiveText(value.asText());
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) {
                if (containsSensitiveContent(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean containsSensitiveContent(StructuredEvidenceExtraction extraction) {
        if (extraction == null) {
            return false;
        }
        if (containsSensitiveStrings(extraction.warnings())
                || containsSensitiveProvenance(extraction.classificationProvenance())) {
            return true;
        }
        if (extraction.fields() != null) {
            for (StructuredEvidenceExtraction.Field field : extraction.fields()) {
                if (field != null && (containsSensitiveText(field.value())
                        || containsSensitiveStrings(field.candidateValues())
                        || containsSensitiveProvenance(field.provenance()))) {
                    return true;
                }
            }
        }
        if (extraction.statements() != null) {
            for (StructuredEvidenceExtraction.Statement statement : extraction.statements()) {
                if (statement != null && (containsSensitiveText(statement.text())
                        || containsSensitiveProvenance(statement.provenance()))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean containsSensitiveProvenance(
            Iterable<StructuredEvidenceExtraction.Provenance> provenance) {
        if (provenance == null) {
            return false;
        }
        for (StructuredEvidenceExtraction.Provenance item : provenance) {
            if (item != null && containsSensitiveText(item.textExcerpt())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSensitiveStrings(Iterable<String> values) {
        if (values == null) {
            return false;
        }
        for (String value : values) {
            if (containsSensitiveText(value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSensitiveText(String value) {
        if (value == null) {
            return false;
        }
        Matcher credential = LABELED_CREDENTIAL.matcher(value);
        while (credential.find()) {
            if (!"[REDACTED]".equalsIgnoreCase(credential.group("value").trim())) {
                return true;
            }
        }
        return BEARER_CREDENTIAL.matcher(value).find() || KNOWN_SECRET_TOKEN.matcher(value).find();
    }
}
