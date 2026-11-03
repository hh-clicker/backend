package org.hhclicker.backend.application.service;

import org.hhclicker.backend.application.model.ApplicationModels.Run;
import org.hhclicker.backend.application.model.ApplicationModels.RunStatus;
import org.hhclicker.backend.application.model.ApplicationModels.Start;

import java.util.UUID;

public interface ApplicationRunGateway {
    Run startApplications(Start start);

    Run changeApplicationStatus(UUID id, RunStatus status);
    default Run changeApplicationStatus(UUID id, RunStatus status, boolean confirmFallback) {
        if (confirmFallback) throw new IllegalArgumentException("Fallback confirmation is unsupported");
        return changeApplicationStatus(id, status);
    }
}
