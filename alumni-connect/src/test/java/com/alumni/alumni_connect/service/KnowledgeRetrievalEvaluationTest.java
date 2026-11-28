package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.knowledge.KnowledgeContributionResponse;
import com.alumni.alumni_connect.entity.User;
import com.alumni.alumni_connect.security.CurrentUserService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Small, synthetic, fixed-label regression set; not a production-quality benchmark. */
class KnowledgeRetrievalEvaluationTest {
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}+#.]{2,}");
    private static final Set<String> STOP = Set.of("the", "and", "for", "with", "how", "what", "from",
            "about", "can", "could", "would", "should", "does", "did", "are", "was", "were", "you",
            "your", "into", "this", "that", "have", "has", "will", "when", "where", "who", "why",
            "a", "an", "to", "of", "in", "on", "is", "it");

    @Test
    void hybridLexicalRetrievalImprovesSyntheticSynonymRecallOverLocalHeadBaseline() {
        List<KnowledgeContributionResponse> corpus = List.of(
                contribution(901, "Backend API project roadmap", "Built a Java Spring Boot backend service with REST endpoints and API tests.", "Northwind", "Backend Developer"),
                contribution(902, "Server room operations", "Maintained physical servers and office equipment.", "OfficeCo", "IT Support"),
                contribution(903, "INTERVIEW_EXPERIENCE", "Acme technical interview", "The remote Acme interview included a timed coding assessment and data-structure questions.", "Acme", "Software Engineer Intern"),
                contribution(904, "Garden planning", "Designed a community garden and irrigation plan.", "GardenCo", "Landscape Designer"),
                contribution(905, "React interface project", "Built accessible React UI components and tested client-side navigation.", "WebCo", "Frontend Developer"),
                contribution(906, "Data analyst internship", "Used Python, SQL, and pandas to validate a reporting dataset.", "DataCo", "Data Analyst Intern"),
                contribution(907, "Resume project bullets", "Describe project impact with clear actions, tools, and measurable outcomes.", "CareerOffice", "Career Coach"),
                contribution(908, "INTERVIEW_EXPERIENCE", "Acme onsite interview report", "The onsite Acme interview for a software engineer intern role included a technical assessment.", "Acme", "Software Engineer Intern"),
                contribution(909, "Hiring policy discussion", "This report does not provide a current company hiring policy or guarantee.", "PolicyForum", "General"));
        List<EvalCase> cases = List.of(
                new EvalCase("How can I learn server side programming?", Set.of(901L)),
                new EvalCase("SWE internship OA at Acme", Set.of(903L)),
                new EvalCase("How do alumni prepare for a coding round at Acme?", Set.of(903L, 908L)),
                new EvalCase("How should I build a Java REST API backend?", Set.of(901L)),
                new EvalCase("React client interface accessibility project", Set.of(905L)),
                new EvalCase("Python SQL data analyst internship preparation", Set.of(906L)),
                new EvalCase("How do I write stronger resume project bullets?", Set.of(907L)),
                new EvalCase("SDE summer internship technical assessment at Acme", Set.of(903L, 908L)),
                new EvalCase("Build a backend API and test the endpoints", Set.of(901L)));
        KnowledgeContributionService contributions = mock(KnowledgeContributionService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUser()).thenReturn(new User("Student", "student@example.test", "hash", "STUDENT", "APPROVED"));
        when(contributions.listAiEligible(0, 50)).thenReturn(corpus);
        when(contributions.recheckAiEligible(anyList())).thenAnswer(call -> {
            Set<String> refs = new HashSet<>();
            List<KnowledgeContributionService.ContributionVersionRef> requested = call.getArgument(0);
            for (KnowledgeContributionService.ContributionVersionRef ref : requested)
                refs.add(ref.contributionId() + ":" + ref.version());
            return corpus.stream().filter(c -> refs.contains(c.id() + ":" + c.version())).toList();
        });

        KnowledgeRetrievalService service = new KnowledgeRetrievalService(contributions, currentUser);
        Metric baseline = evaluate(cases, corpus, query -> baselineRanking(query, corpus));
        Metric current = evaluate(cases, corpus, query -> service.retrieve(query, null, 0, 10).sources().stream()
                .map(source -> source.contributionId()).toList());

        System.out.printf(Locale.ROOT,
                "Synthetic retrieval evaluation (local HEAD baseline -> current; P@3 %.3f -> %.3f, R@5 %.3f -> %.3f, MRR %.3f -> %.3f)%n",
                baseline.precisionAt3(), current.precisionAt3(), baseline.recallAt5(), current.recallAt5(),
                baseline.mrr(), current.mrr());
        assertTrue(current.recallAt5() >= baseline.recallAt5(), "expanded retrieval should not lose labeled relevant items on this fixture");
        assertTrue(current.mrr() >= baseline.mrr(), "expanded retrieval should not lower reciprocal rank on this fixture");
    }

    private static Metric evaluate(List<EvalCase> cases, List<KnowledgeContributionResponse> pool,
                                   java.util.function.Function<String, List<Long>> ranker) {
        double p = 0, r = 0, reciprocalRank = 0;
        for (EvalCase testCase : cases) {
            List<Long> ranking = ranker.apply(testCase.query());
            int relevant = 0;
            for (int i = 0; i < Math.min(3, ranking.size()); i++)
                if (testCase.relevant().contains(ranking.get(i))) relevant++;
            p += (double) relevant / Math.min(3, pool.size());
            int atFive = 0;
            for (int i = 0; i < Math.min(5, ranking.size()); i++)
                if (testCase.relevant().contains(ranking.get(i))) atFive++;
            r += (double) atFive / testCase.relevant().size();
            for (int i = 0; i < ranking.size(); i++) if (testCase.relevant().contains(ranking.get(i))) {
                reciprocalRank += 1.0 / (i + 1);
                break;
            }
        }
        return new Metric(p / cases.size(), r / cases.size(), reciprocalRank / cases.size());
    }

    /** Reproduces the tracked local HEAD lexical scoring/tokenization for comparison. */
    private static List<Long> baselineRanking(String query, List<KnowledgeContributionResponse> corpus) {
        List<String> terms = new ArrayList<>();
        var matcher = TOKEN.matcher(query.toLowerCase(Locale.ROOT));
        while (matcher.find()) if (!STOP.contains(matcher.group())) terms.add(matcher.group());
        return corpus.stream().map(c -> Map.entry(c, oldScore(c, terms)))
                .filter(pair -> pair.getValue() > 0)
                .sorted(Comparator.<Map.Entry<KnowledgeContributionResponse, Integer>>comparingInt(Map.Entry::getValue)
                        .reversed().thenComparing(pair -> pair.getKey().title(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparingLong(pair -> pair.getKey().id()))
                .map(pair -> pair.getKey().id()).toList();
    }

    private static int oldScore(KnowledgeContributionResponse c, List<String> terms) {
        String title = c.title().toLowerCase(Locale.ROOT);
        String context = (Objects.toString(c.companyName(), "") + " " + Objects.toString(c.jobRole(), "") + " " + c.category()).toLowerCase(Locale.ROOT);
        String narrative = c.narrative().toLowerCase(Locale.ROOT);
        int points = 0;
        for (String term : terms) {
            if (title.contains(term)) points += 4;
            if (context.contains(term)) points += 2;
            if (narrative.contains(term)) points++;
        }
        return points;
    }

    private static KnowledgeContributionResponse contribution(long id, String title, String narrative,
            String company, String role) {
        return contribution(id, "GENERAL", title, narrative, company, role);
    }

    private static KnowledgeContributionResponse contribution(long id, String category, String title,
            String narrative, String company, String role) {
        LocalDateTime now = LocalDateTime.now();
        return new KnowledgeContributionResponse(id, 1, 1, category, title, narrative, company, role,
                null, "PUBLISHED", true, true, now, now, now, List.of());
    }

    private record EvalCase(String query, Set<Long> relevant) {}
    private record Metric(double precisionAt3, double recallAt5, double mrr) {}
}
