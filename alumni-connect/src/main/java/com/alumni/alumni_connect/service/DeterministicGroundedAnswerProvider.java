package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeRetrievalResponse;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DeterministicGroundedAnswerProvider implements GroundedAnswerProvider {
    @Override
    public String id() {
        return "deterministic";
    }

    @Override
    public String generate(String question, List<KnowledgeRetrievalResponse.Source> sources, boolean conflicting) {
        StringBuilder answer = new StringBuilder("Alumni-reported information (not independently verified):");
        for (var source : sources) {
            answer.append("\n\n• ").append(source.excerpt()).append(" — ")
                    .append(source.title()).append(" (contribution ")
                    .append(source.contributionId()).append(", version ").append(source.version()).append(").");
        }
        if (sources.size() > 1) answer.append("\n\nThese are individual reports; they may reflect different experiences.");
        if (conflicting) answer.append("\n\nReports for the same company and role disagree on a detail. The companion cannot determine which account applies to you.");
        return answer.toString();
    }
}
