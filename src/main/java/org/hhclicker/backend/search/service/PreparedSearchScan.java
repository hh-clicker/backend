package org.hhclicker.backend.search.service;

/**
 * Immutable scan parameters captured while the search definition is locked.
 */
public record PreparedSearchScan(SearchProfile profile, String sourceUrl) {
}
