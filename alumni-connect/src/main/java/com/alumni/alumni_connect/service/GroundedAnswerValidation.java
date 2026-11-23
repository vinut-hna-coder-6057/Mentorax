package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Enforces extractive, source-version-specific claims on generated output. */
final class GroundedAnswerValidation {
    private static final Pattern CITATION = Pattern.compile("\\[(\\d+):(\\d+)\\]");
    private static final Pattern INSTRUCTION = Pattern.compile(
            "(?i)\\b(ignore|disregard|override|reveal|expose|print|send|follow)\\b.{0,60}"
                    + "\\b(instructions?|rules?|prompt|system|previous|prior|keys?|secrets?|credentials?)\\b");

    private GroundedAnswerValidation() {}

    static String validateExtractiveCitations(String output,
            List<KnowledgeRetrievalResponse.Source> sources, int maxOutputLength) {
        if (output == null || output.isBlank() || output.length() > maxOutputLength)
            throw new IllegalArgumentException("Grounded provider returned empty or oversized content");
        Set<String> allowed = sources.stream()
                .map(source -> source.contributionId() + ":" + source.version())
                .collect(Collectors.toSet());
        StringBuilder accepted = new StringBuilder();
        for (String rawLine : output.strip().split("\\R")) {
            if (rawLine.isBlank()) continue;
            Matcher matcher = CITATION.matcher(rawLine);
            Set<String> cited = new java.util.HashSet<>();
            while (matcher.find()) {
                String reference = matcher.group(1) + ":" + matcher.group(2);
                if (!allowed.contains(reference))
                    throw new IllegalArgumentException("Grounded provider returned an unknown source version");
                cited.add(reference);
            }
            String claim = CITATION.matcher(rawLine).replaceAll("").trim()
                    .replaceFirst("^(?:[-*\\x{2022}]\\s*)", "")
                    .replaceAll("^[\\\"'\\x{201C}\\x{201D}]+|[\\\"'\\x{201C}\\x{201D}]+$", "")
                    .trim();
            if (cited.isEmpty() || claim.isBlank() || claim.length() > 600 || INSTRUCTION.matcher(claim).find())
                throw new IllegalArgumentException("Grounded provider returned an invalid claim");
            String normalizedClaim = normalize(claim);
            boolean supported = sources.stream().anyMatch(source ->
                    cited.contains(source.contributionId() + ":" + source.version())
                            && normalize(source.excerpt()).contains(normalizedClaim));
            if (!supported)
                throw new IllegalArgumentException("Grounded provider returned an unsupported claim");
            if (accepted.length() > 0) accepted.append('\n');
            accepted.append(claim).append(" ").append(String.join(" ", cited.stream().sorted()
                    .map(reference -> "[" + reference + "]").toList()));
        }
        if (accepted.length() == 0) throw new IllegalArgumentException("Grounded provider returned no supported claims");
        return accepted.toString();
    }

    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .toLowerCase(Locale.ROOT);
        return decomposed.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
}
