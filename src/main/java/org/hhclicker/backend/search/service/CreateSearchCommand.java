package org.hhclicker.backend.search.service;

import java.util.UUID;

public record CreateSearchCommand(
        String name,
        String sourceUrl,
        UUID resumeId,
        int pagesLimit
) {
}

