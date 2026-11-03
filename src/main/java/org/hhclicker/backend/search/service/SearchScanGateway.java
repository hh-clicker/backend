package org.hhclicker.backend.search.service;

import java.util.UUID;

/**
 * Transport adapter starts a scan; the search domain owns its state.
 */
public interface SearchScanGateway {
    SearchProfile scanSearch(UUID searchId);
}
