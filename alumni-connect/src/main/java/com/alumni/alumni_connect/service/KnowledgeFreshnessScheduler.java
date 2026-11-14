package com.alumni.alumni_connect.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeFreshnessScheduler {
    private final KnowledgeFreshnessService freshness;
    public KnowledgeFreshnessScheduler(KnowledgeFreshnessService freshness) { this.freshness = freshness; }

    @Scheduled(fixedDelayString = "${app.knowledge.freshness.poll-delay-ms:3600000}")
    public void dispatch() { freshness.dispatchDueReminders(); }
}
