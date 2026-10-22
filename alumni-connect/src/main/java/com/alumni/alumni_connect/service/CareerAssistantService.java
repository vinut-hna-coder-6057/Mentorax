package com.alumni.alumni_connect.service;

import com.alumni.alumni_connect.dto.MentorDiscoveryResponse;
import com.alumni.alumni_connect.dto.QuestionIntent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class CareerAssistantService {
    private final QuestionIntentClassifier classifier;
    private final MentorDiscoveryService mentorDiscovery;
    private final GeneralQuestionAnswerService generalAnswerService;

    public CareerAssistantService(QuestionIntentClassifier classifier,
                                  MentorDiscoveryService mentorDiscovery,
                                  GeneralQuestionAnswerService generalAnswerService) {
        this.classifier = classifier;
        this.mentorDiscovery = mentorDiscovery;
        this.generalAnswerService = generalAnswerService;
    }

    public MentorDiscoveryResponse respond(String question) {
        QuestionIntentClassifier.Classification classification = classifier.classify(question);
        QuestionIntent intent = classification.intent();
        if (intent == QuestionIntent.CAREER_MENTOR_SEARCH || intent == QuestionIntent.ALUMNI_QUESTION) {
            MentorDiscoveryResponse ranking = mentorDiscovery.discover(question);
            String message = ranking.message();
            if (intent == QuestionIntent.ALUMNI_QUESTION) {
                message = ranking.recommendations().isEmpty()
                        ? Objects.toString(message, "No relevant alumni were found.")
                                + " Your question was not sent."
                        : "These approved alumni may be relevant. Your question has not been sent. "
                                + "Request a connection first; after it is accepted, open the profile, review your message, and send it from chat.";
            }
            return new MentorDiscoveryResponse(ranking.recommendations(), ranking.rankingMethod(),
                    ranking.fallbackUsed(), intent, null, false, null, message, List.of());
        }

        if (intent == QuestionIntent.GENERAL_KNOWLEDGE) {
            GeneralQuestionAnswerService.AnswerResult answer = generalAnswerService.answer(question);
            return new MentorDiscoveryResponse(List.of(), "No alumni ranking performed", false,
                    intent, answer.answer(), false, null, answer.message(), answer.sources());
        }
        if (intent == QuestionIntent.GREETING_OR_SMALLTALK) {
            return response(intent,
                    "Hi! I can help you find approved alumni for career guidance. General factual answers are not available yet.",
                    false, null, null);
        }
        return response(intent, null, classification.needsClarification(),
                classification.clarificationQuestion(),
                classification.needsClarification() ? null
                        : "I can help find alumni for career guidance or route supported factual questions.");
    }

    private MentorDiscoveryResponse response(QuestionIntent intent, String answer,
                                             boolean needsClarification,
                                             String clarificationQuestion, String message) {
        return new MentorDiscoveryResponse(List.of(), "No alumni ranking performed", false,
                intent, answer, needsClarification, clarificationQuestion, message, List.of());
    }
}
