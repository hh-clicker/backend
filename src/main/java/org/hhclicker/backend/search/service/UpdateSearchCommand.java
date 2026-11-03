package org.hhclicker.backend.search.service;

public record UpdateSearchCommand(String name, String sourceUrl, int pagesLimit) {
}
