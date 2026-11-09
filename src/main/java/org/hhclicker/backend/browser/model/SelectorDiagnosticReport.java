package org.hhclicker.backend.browser.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

/** Ephemeral structural diagnostics; contains no DOM, URLs, field values, or user texts. */
public record SelectorDiagnosticReport(
        @NotNull PageType pageType,
        @NotNull Instant checkedAt,
        @NotNull Status status,
        @NotBlank @Size(max = 500) String message,
        @NotNull @Size(max = 100) List<@NotNull @Valid Row> rows,
        Summary summary,
        @Size(max = 4) List<@NotNull @Valid SelectorDiagnosticReport> scenarios,
        @Size(max = 120) String contextLabel,
        @Pattern(regexp = "[a-f0-9-]{36}") String tabId,
        @Size(max = 200) List<@NotNull @Valid Tab> tabs,
        @Size(max = 4) List<@NotNull @Pattern(regexp = "profile|search|vacancy|resumeText") String> repairableGroups,
        @Size(max = 500) String repairNote
) {
    public SelectorDiagnosticReport {
        if (rows != null) rows = List.copyOf(rows);
        if (scenarios != null) scenarios = List.copyOf(scenarios);
        if (tabs != null) tabs = List.copyOf(tabs);
        if (repairableGroups != null) repairableGroups = List.copyOf(repairableGroups);
    }
    public SelectorDiagnosticReport(PageType pageType, Instant checkedAt, Status status, String message, List<Row> rows,
            Summary summary, List<SelectorDiagnosticReport> scenarios, String contextLabel, String tabId, List<Tab> tabs) {
        this(pageType, checkedAt, status, message, rows, summary, scenarios, contextLabel, tabId, tabs, null, null);
    }
    public SelectorDiagnosticReport(PageType pageType, Instant checkedAt, Status status, String message, List<Row> rows,
            Summary summary, List<SelectorDiagnosticReport> scenarios, String contextLabel, String tabId) {
        this(pageType, checkedAt, status, message, rows, summary, scenarios, contextLabel, tabId, null);
    }
    public SelectorDiagnosticReport(PageType pageType, Instant checkedAt, Status status, String message, List<Row> rows) {
        this(pageType, checkedAt, status, message, rows, null, null, null, null);
    }
    public enum PageType { PROFILE, SEARCH, VACANCY, RESUME, UNKNOWN, CURRENT, FULL }
    public enum Status { CHECKED, WRONG_PAGE, LOGIN, CAPTCHA, PAGE_UNAVAILABLE, SKIPPED, SELECT_TAB, BLOCKED }
    public record Tab(@NotNull @Pattern(regexp = "[a-f0-9-]{36}") String tabId,
            @NotBlank @Size(max = 120) String label) {}
    public enum Result { PASS, FAIL, NOT_CHECKED, NOT_APPLICABLE, STATE_NOT_CHECKED }
    public enum Summary { PASSED, FAILED, INCOMPLETE }
    public enum Source { BUNDLED, LOCAL }
    public record Row(
            @NotBlank @Pattern(regexp = "[a-z][a-zA-Z0-9-]{0,63}") String group,
            @NotBlank @Pattern(regexp = "[a-z][a-zA-Z0-9]{0,63}") String key,
            @NotNull @Size(max = 1000) String purpose,
            @NotBlank @Size(max = 4102) String selector,
            @NotNull Source source,
            @NotNull Result status,
            boolean required,
            @Min(0) @Max(1000000) int matched,
            @Min(0) @Max(1000000) int visible,
            @NotBlank @Size(max = 500) String message
    ) {}
}
