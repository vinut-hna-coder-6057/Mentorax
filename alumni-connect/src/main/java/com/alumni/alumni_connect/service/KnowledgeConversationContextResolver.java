package com.alumni.alumni_connect.service;

import java.util.List;
import java.util.regex.Pattern;

/** Uses only the immediately relevant prior turn to resolve clearly referential follow-ups. */
public final class KnowledgeConversationContextResolver {
    private static final Pattern FOLLOW_UP = Pattern.compile(
            "(?i)^(what about|how about|and for|what if|why is that|does that|will that|"
                    + "can you explain (the )?(first|second|third|last|above|previous|that)|"
                    + "explain (the )?(first|second|third|last|above|previous|that)|"
                    + "what should i do first|how many weeks|can you make (it|this) easier|"
                    + "which (project|skill|one) should i|what about the interview round)\\b.*");
    private static final int MAX_RESOLVED_QUERY = 500;

    public String resolve(String question, List<Turn> recentTurnsNewestFirst) {
        String current = question == null ? "" : question.strip();
        if (current.isEmpty() || recentTurnsNewestFirst == null || recentTurnsNewestFirst.isEmpty()
                || !FOLLOW_UP.matcher(current).matches()) return bound(current);

        String priorQuestion = recentTurnsNewestFirst.stream()
                .filter(turn -> "USER".equals(turn.speaker()))
                .map(Turn::content).filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("");
        if (priorQuestion.isBlank()) return bound(current);

        String context = priorQuestion.strip();
        if (current.matches("(?is).*(second|third|first|last) (point|step|item|option).*")) {
            String priorAnswer = recentTurnsNewestFirst.stream()
                    .filter(turn -> "ASSISTANT".equals(turn.speaker()))
                    .map(Turn::content).filter(value -> value != null && !value.isBlank())
                    .findFirst().orElse("");
            List<String> answerPoints = java.util.Arrays.stream(priorAnswer.split("\\R"))
                    .map(String::strip).filter(line -> !line.isBlank())
                    .filter(line -> line.matches("^(?:[-*•]|\\d+[.)]).*"))
                    .toList();
            if (answerPoints.isEmpty()) answerPoints = java.util.Arrays.stream(priorAnswer.split("\\R"))
                    .map(String::strip).filter(line -> !line.isBlank()).toList();
            String marker = current.toLowerCase(java.util.Locale.ROOT).contains("second") ? "second"
                    : current.toLowerCase(java.util.Locale.ROOT).contains("third") ? "third"
                    : current.toLowerCase(java.util.Locale.ROOT).contains("last") ? "last" : "first";
            int index = switch (marker) { case "second" -> 1; case "third" -> 2; default -> 0; };
            if (answerPoints.size() > index) context += " " + answerPoints.get(index);
        }
        String resolved = context + " " + current;
        if (resolved.length() <= MAX_RESOLVED_QUERY) return resolved;
        int contextBudget = MAX_RESOLVED_QUERY - current.length() - 1;
        return contextBudget > 0 ? context.substring(0, Math.min(context.length(), contextBudget)) + " " + current
                : bound(current);
    }

    private static String bound(String value) {
        return value.length() <= MAX_RESOLVED_QUERY ? value : value.substring(0, MAX_RESOLVED_QUERY);
    }

    public record Turn(String speaker, String content) {}
}
