/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

final class SpotifyFetchPolicy {
    static final long NO_LYRICS_TTL_MS = 30L * 60L * 1000L;
    static final long NOT_FOUND_TTL_MS = 6L * 60L * 60L * 1000L;
    static final long FORBIDDEN_TTL_MS = 5L * 60L * 1000L;
    static final long RATE_LIMIT_TTL_MS = 60L * 1000L;

    private SpotifyFetchPolicy() {}

    static String requestKey(String trackKey, long generation) {
        return trackKey + "#" + generation;
    }

    static long negativeTtlMs(String outcome) {
        if (outcome == null) return 0L;
        if ("http-404".equals(outcome)) return NOT_FOUND_TTL_MS;
        if ("http-403".equals(outcome)) return FORBIDDEN_TTL_MS;
        if ("http-429".equals(outcome)) return RATE_LIMIT_TTL_MS;
        if ("lyrics-missing".equals(outcome) || "lines-empty".equals(outcome)) {
            return NO_LYRICS_TTL_MS;
        }
        return 0L;
    }

    static boolean isNegativeCached(Long expiresAtMs, long nowMs) {
        return expiresAtMs != null && expiresAtMs > nowMs;
    }
}
