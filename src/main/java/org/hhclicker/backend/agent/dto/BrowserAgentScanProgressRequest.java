package org.hhclicker.backend.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record BrowserAgentScanProgressRequest(
        @NotBlank @Size(max = 100) String agentId,
        @NotNull @Min(1) @Max(20) Integer currentPage,
        @NotNull @Min(1) @Max(20) Integer totalPages,
        @NotNull @Min(0) @Max(2000) Integer collected,
        @Min(0) Integer totalResults
) {
}
