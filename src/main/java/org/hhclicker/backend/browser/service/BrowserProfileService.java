package org.hhclicker.backend.browser.service;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.account.model.HhAccount;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.browser.exception.*;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class BrowserProfileService {

    private final BrowserProfileRepository repository;
    private final AccountService accountService;
    private final BrowserRuntimeGateway runtimeGateway;
    private final Clock clock;

    public BrowserProfileService(BrowserProfileRepository repository, AccountService accountService, BrowserRuntimeGateway runtimeGateway, Clock clock) {
        this.repository = repository;
        this.accountService = accountService;
        this.runtimeGateway = runtimeGateway;
        this.clock = clock;
    }

    public BrowserPageData loadPage(UUID accountId) {
        HhAccount account = accountService.findById(accountId);
        return new BrowserPageData(runtimeGateway.getSnapshot(), account.authorizationStatus(), repository.findByAccountId(accountId).orElse(null));
    }

    @Transactional
    public BrowserProfile create(UUID accountId) {
        accountService.findById(accountId);
        if (repository.findByAccountId(accountId).isPresent()) throw new BrowserProfileAlreadyExistsException();
        Instant now = clock.instant();
        BrowserProfile profile = new BrowserProfile(UUID.randomUUID(), accountId, now, now);
        try {
            repository.insert(profile);
        } catch (DuplicateKeyException exception) {
            throw new BrowserProfileAlreadyExistsException();
        }
        return profile;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID open(UUID accountId) {
        requireRuntimeAvailable();
        BrowserProfile profile = repository.findByAccountId(accountId).orElseThrow(() -> new BrowserProfileNotFoundException(accountId));
        return runtimeGateway.open(profile);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID close() {
        requireRuntimeAvailable();
        return runtimeGateway.close();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID openUrl(UUID accountId, String sourceUrl) {
        requireRuntimeAvailable();
        BrowserProfile profile = repository.findByAccountId(accountId)
                .orElseThrow(() -> new BrowserProfileNotFoundException(accountId));
        BrowserRuntimeSnapshot runtime = runtimeGateway.getSnapshot();
        if (!runtime.browserOpen() || !profile.id().equals(runtime.activeProfileId())) {
            throw new BrowserNotRunningException();
        }
        return runtimeGateway.openUrl(profile, normalizeHhUrl(sourceUrl));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID checkAuthorization(UUID accountId) {
        return runtimeGateway.checkAuthorization(requireRunningProfile(accountId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID discoverResumes(UUID accountId) {
        return runtimeGateway.discoverResumes(requireRunningProfile(accountId));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public UUID readResumeText(UUID accountId, String hhResumeId) {
        return runtimeGateway.readResumeText(requireRunningProfile(accountId), hhResumeId);
    }

    private BrowserProfile requireRunningProfile(UUID accountId) {
        requireRuntimeAvailable();
        BrowserProfile profile = repository.findByAccountId(accountId)
                .orElseThrow(() -> new BrowserProfileNotFoundException(accountId));
        BrowserRuntimeSnapshot runtime = runtimeGateway.getSnapshot();
        if (!runtime.browserOpen() || !profile.id().equals(runtime.activeProfileId())) {
            throw new BrowserNotRunningException();
        }
        return profile;
    }

    private void requireRuntimeAvailable() {
        String status = runtimeGateway.getSnapshot().agentStatus();
        if (!"READY".equals(status) && !"BUSY".equals(status)) {
            throw new BrowserRuntimeUnavailableException();
        }
    }

    private String normalizeHhUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            throw new InvalidBrowserUrlException();
        }
        URI uri;
        try {
            uri = URI.create(sourceUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw new InvalidBrowserUrlException();
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !(host.equals("hh.ru") || host.endsWith(".hh.ru"))) {
            throw new InvalidBrowserUrlException();
        }
        return uri.toString();
    }

}
