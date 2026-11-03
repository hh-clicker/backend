package org.hhclicker.backend.browser.service;

import org.springframework.stereotype.Service;
import org.hhclicker.backend.browser.exception.BrowserProfileNotFoundException;
import org.hhclicker.backend.browser.model.BrowserProfile;
import org.hhclicker.backend.browser.repository.BrowserProfileRepository;

import java.util.UUID;

/**
 * Read-only access without a dependency on the runtime gateway.
 */
@Service
public class BrowserProfileLookup {
    private final BrowserProfileRepository profiles;

    public BrowserProfileLookup(BrowserProfileRepository profiles) {
        this.profiles = profiles;
    }

    public BrowserProfile forAccount(UUID accountId) {
        return profiles.findByAccountId(accountId).orElseThrow(() -> new BrowserProfileNotFoundException(accountId));
    }
}
