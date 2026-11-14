package com.alumni.alumni_connect.dto.knowledge;

import jakarta.validation.constraints.NotNull;

public record PublishConsentRequest(@NotNull Boolean studentVisibilityConsent,
                                    @NotNull Boolean aiRetrievalConsent) {}
