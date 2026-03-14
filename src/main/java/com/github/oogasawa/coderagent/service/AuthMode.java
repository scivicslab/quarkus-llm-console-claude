package com.github.oogasawa.coderagent.service;

/**
 * Authentication mode for Claude access.
 */
public enum AuthMode {
    /** Claude CLI is installed locally. */
    CLI,
    /** API key provided via environment variable or config property. */
    API_KEY,
    /** No authentication configured; must be provided via Web UI. */
    NONE
}
