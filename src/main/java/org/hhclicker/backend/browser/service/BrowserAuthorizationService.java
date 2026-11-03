package org.hhclicker.backend.browser.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.hhclicker.backend.account.model.AuthorizationStatus;
import org.hhclicker.backend.account.service.AccountService;
import org.hhclicker.backend.browser.exception.BrowserProfileNotFoundException;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;

import java.util.UUID;

@Service
public class BrowserAuthorizationService {

    private final BrowserProfileRepository profiles;
    private final AccountService accounts;

    public BrowserAuthorizationService(BrowserProfileRepository profiles, AccountService accounts) {
        this.profiles = profiles;
        this.accounts = accounts;
    }

    @Transactional
    public void record(UUID profileId, AuthorizationStatus status) {
        var profile = profiles.findById(profileId)
                .orElseThrow(() -> new BrowserProfileNotFoundException(profileId));
        accounts.updateAuthorizationStatus(profile.accountId(), status);
    }
}
