package org.hhclicker.backend.vacancy.selection.service;

import java.util.UUID;

public interface QuestionnaireApplicationVerificationGateway {
    UUID verifyQuestionnaireApplication(UUID vacancyId);
}
