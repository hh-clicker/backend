package org.hhclicker.backend.browser.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.hhclicker.backend.browser.dto.*;
import org.hhclicker.backend.browser.service.BrowserProfileService;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/browser")
@Tag(name = "Browser profiles", description = "Постоянные профили Google Chrome")
public class BrowserController {

    private final BrowserProfileService service;

    public BrowserController(BrowserProfileService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить профиль и состояние браузера")
    public BrowserPageResponse load(@RequestParam UUID accountId) {
        return BrowserPageResponse.from(service.loadPage(accountId));
    }

    @PostMapping("/profile")
    @ResponseStatus(HttpStatus.CREATED)
    public BrowserProfileResponse create(@Valid @RequestBody CreateBrowserProfileRequest request) {
        var profile = service.create(request.accountId());
        return BrowserProfileResponse.from(profile, service.loadPage(profile.accountId()).runtime());
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse start(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.open(request.accountId()));
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse stop() {
        return new BrowserCommandReceiptResponse(service.close());
    }

    @PostMapping("/check-authorization")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Проверить авторизацию в запущенном профиле HH-аккаунта")
    public BrowserCommandReceiptResponse checkAuthorization(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.checkAuthorization(request.accountId()));
    }

    @PostMapping("/open-url")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse openUrl(@Valid @RequestBody OpenBrowserUrlRequest request) {
        return new BrowserCommandReceiptResponse(service.openUrl(request.accountId(), request.url()));
    }

    @PostMapping("/discover-resumes")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Проверить вход и прочитать карточки резюме без импорта")
    public BrowserCommandReceiptResponse discoverResumes(@Valid @RequestBody StartBrowserRequest request) {
        return new BrowserCommandReceiptResponse(service.discoverResumes(request.accountId()));
    }

    @PostMapping("/resume-text")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse readResumeText(@Valid @RequestBody ResumeTextRequest request) {
        return new BrowserCommandReceiptResponse(service.readResumeText(request.accountId(), request.hhResumeId()));
    }

    public record ResumeTextRequest(@jakarta.validation.constraints.NotNull UUID accountId,
                                    @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[a-f0-9]{20,100}") String hhResumeId) {
    }

    @PostMapping("/selector-diagnostics")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Проверить селекторы на открытой вкладке без навигации и кликов")
    public BrowserCommandReceiptResponse diagnoseSelectors(@Valid @RequestBody SelectorDiagnosticsRequest request) {
        if (request.repairGroup() != null) {
            if (request.mode() != org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.CURRENT || request.tabId() == null)
                throw new org.hhclicker.backend.browser.exception.InvalidSelectorDiagnosticRequestException();
            return new BrowserCommandReceiptResponse(service.repairSelectors(request.accountId(), request.tabId(), request.repairGroup()));
        }
        if (request.mode() != null) {
            if (request.mode() != org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.CURRENT
                    && request.mode() != org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.FULL)
                throw new org.hhclicker.backend.browser.exception.InvalidSelectorDiagnosticRequestException();
            return new BrowserCommandReceiptResponse(request.tabId() == null
                    ? service.diagnoseSelectors(request.accountId(), request.mode(), request.resumeId())
                    : service.diagnoseSelectors(request.accountId(), request.mode(), request.resumeId(), request.tabId()));
        }
        if (request.pageType() == null || !java.util.Set.of(
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.PROFILE,
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.SEARCH,
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.VACANCY,
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType.RESUME).contains(request.pageType()))
            throw new org.hhclicker.backend.browser.exception.InvalidSelectorDiagnosticRequestException();
        return new BrowserCommandReceiptResponse(service.diagnoseSelectors(request.accountId(), request.pageType()));
    }

    public record SelectorDiagnosticsRequest(@jakarta.validation.constraints.NotNull UUID accountId,
            org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType pageType,
            org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType mode, UUID resumeId, UUID tabId,
            @jakarta.validation.constraints.Pattern(regexp = "profile|search|vacancy|resumeText") String repairGroup) {
        public SelectorDiagnosticsRequest(UUID accountId, org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType pageType,
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType mode, UUID resumeId, UUID tabId) {
            this(accountId, pageType, mode, resumeId, tabId, null);
        }
        public SelectorDiagnosticsRequest(UUID accountId, org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType pageType,
                org.hhclicker.backend.browser.model.SelectorDiagnosticReport.PageType mode, UUID resumeId) {
            this(accountId, pageType, mode, resumeId, null);
        }
    }

    @PostMapping("/focus-diagnostic")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse focusDiagnostic(@Valid @RequestBody FocusDiagnosticRequest request) {
        return new BrowserCommandReceiptResponse(service.focusDiagnostic(request.accountId(), request.tabId()));
    }
    public record FocusDiagnosticRequest(@jakarta.validation.constraints.NotNull UUID accountId,
            @jakarta.validation.constraints.NotNull UUID tabId) {}

    @PostMapping("/focus-vacancy")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BrowserCommandReceiptResponse focusVacancy(@Valid @RequestBody FocusVacancyRequest request) {
        return new BrowserCommandReceiptResponse(service.focusVacancy(request.accountId(), request.hhVacancyId()));
    }

    public record FocusVacancyRequest(@jakarta.validation.constraints.NotNull UUID accountId,
            @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp = "[0-9]{1,20}") String hhVacancyId) {}
}
