package com.alumni.alumni_connect.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class UnansweredQuestionService {
    private static final Pattern WORD = Pattern.compile("[\\p{L}]{3,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "how", "what", "from", "about", "can", "could", "would", "should", "does", "did", "are", "was", "were", "you", "your", "into", "this", "that", "have", "has", "will", "when", "where", "who", "why", "help", "please", "need");
    private final JdbcTemplate jdbc;
    @Value("${app.knowledge.question-opportunity-min-students:5}") private int minimumStudents = 5;

    public UnansweredQuestionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void record(long studentId, String question) {
        List<String> terms = normalize(question);
        if (terms.size() < 2) return;
        String label = String.join(" ", terms);
        String hash = sha256(label);
        try {
            jdbc.update("INSERT INTO knowledge_question_interest (student_user_id,topic_hash,topic_label) VALUES (?,?,?)",
                    studentId, hash, label.substring(0, Math.min(120, label.length())));
        } catch (DuplicateKeyException ignored) {
            // One row per student and normalized topic: repeated asking does not inflate demand.
        }
    }

    @Transactional(readOnly = true)
    public List<com.alumni.alumni_connect.dto.knowledge.KnowledgeOpportunityResponse> opportunities() {
        int threshold = Math.max(5, minimumStudents);
        return jdbc.query("""
                SELECT topic_label, COUNT(DISTINCT student_user_id) AS student_count
                FROM knowledge_question_interest GROUP BY topic_hash, topic_label
                HAVING COUNT(DISTINCT student_user_id) >= ?
                ORDER BY student_count DESC, topic_label ASC LIMIT 100
                """, (rs, n) -> new com.alumni.alumni_connect.dto.knowledge.KnowledgeOpportunityResponse(
                rs.getString("topic_label"), rs.getInt("student_count")), threshold);
    }

    private static List<String> normalize(String question) {
        String normalized = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}", " ")
                .replaceAll("\\+?\\d[\\d ()-]{7,}\\d", " ");
        var matcher = WORD.matcher(normalized);
        Set<String> terms = new TreeSet<>();
        while (matcher.find()) {
            String term = matcher.group();
            if (!STOP.contains(term)) terms.add(term);
        }
        return terms.stream().limit(4).toList();
    }
    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
