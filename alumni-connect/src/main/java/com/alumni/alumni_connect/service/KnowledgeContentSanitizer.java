package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;

import java.util.List;
import java.util.regex.Pattern;

/** Redacts common direct identifiers, secrets, and instruction-like content before public/model use. */
public final class KnowledgeContentSanitizer {
    private static final Pattern EMAIL = Pattern.compile("(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("(?<!\\w)(?:\\+?\\d[\\d ()-]{7,}\\d)(?!\\w)");
    private static final Pattern SECRET = Pattern.compile("(?i)(api[_ -]?key|password|secret|token)\\s*[:=]\\s*\\S+");
    private static final Pattern INSTRUCTION = Pattern.compile(
            "(?i)[^.\\n]*\\b(ignore|disregard|override)\\b[^.\\n]*(\\b(previous|prior|system|developer)\\b|\\binstructions?\\b)[^.\\n]*\\.?|"
                    + "[^.\\n]*\\b(system prompt|developer message|reveal .*secret|act as (the )?system|do not cite)\\b[^.\\n]*\\.?");

    private KnowledgeContentSanitizer() {}

    public static String redact(String input) {
        if (input == null || input.isBlank()) return input;
        String clean = input.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "");
        clean = EMAIL.matcher(clean).replaceAll("[contact removed]");
        clean = PHONE.matcher(clean).replaceAll("[contact removed]");
        clean = SECRET.matcher(clean).replaceAll("$1=[removed]");
        return INSTRUCTION.matcher(clean).replaceAll("[untrusted instruction removed]");
    }

    public static KnowledgeContributionResponse publicView(KnowledgeContributionResponse source) {
        List<KnowledgeContributionResponse.Entry> entries = source.entries().stream().map(entry ->
                new KnowledgeContributionResponse.Entry(entry.type(), entry.position(), entry.parentPosition(),
                        redact(entry.value()), redact(entry.detail()), redact(entry.url()), redact(entry.fromRole()),
                        redact(entry.toRole()), entry.year())).toList();
        return new KnowledgeContributionResponse(source.id(), source.version(), source.publishedVersion(),
                source.category(), redact(source.title()), redact(source.narrative()), redact(source.companyName()),
                redact(source.jobRole()), source.experienceYear(), source.status(), source.studentVisibilityConsent(),
                source.aiRetrievalConsent(), source.createdAt(), source.updatedAt(), source.publishedAt(), entries);
    }
}
