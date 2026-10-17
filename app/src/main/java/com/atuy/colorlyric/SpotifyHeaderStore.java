/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

final class SpotifyHeaderStore {
    private static final String[] TRACKED = {
            "authorization",
            "client-token",
            "user-agent",
            "x-client-id"
    };

    private static final ConcurrentHashMap<String, String> HEADERS = new ConcurrentHashMap<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private SpotifyHeaderStore() {}

    static boolean ingest(String name, String value) {
        if (name == null || value == null || value.isBlank()) return false;
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (!isTrackedKey(key)) return false;
        String safeValue = value.trim();
        // Spotify's Android client may no longer set client-token, user-agent
        // or x-client-id explicitly on every request. A valid Bearer token is
        // sufficient to attempt the official lyrics API.
        if ("authorization".equals(key) && !isBearer(safeValue)) return false;
        String previous = HEADERS.put(key, safeValue);
        if (safeValue.equals(previous)) return false;
        REVISION.incrementAndGet();
        // Credentials may arrive in stages. Notify the fetcher about optional
        // headers and token refreshes even after initial readiness.
        return isReady();
    }

    static boolean ingestPairs(Object raw) {
        if (!(raw instanceof String[])) return false;
        String[] values = (String[]) raw;
        boolean becameReady = false;
        for (int i = 0; i + 1 < values.length; i += 2) {
            becameReady |= ingest(values[i], values[i + 1]);
        }
        return becameReady;
    }

    static boolean isReady() {
        return isBearer(HEADERS.get("authorization"));
    }

    static long revision() {
        return REVISION.get();
    }

    private static boolean isBearer(String value) {
        return value != null && value.length() > 7
                && value.regionMatches(true, 0, "Bearer ", 0, 7)
                && !value.substring(7).isBlank();
    }

    static Map<String, String> snapshot() {
        return new HashMap<>(HEADERS);
    }

    static String capturedKeys() {
        StringBuilder out = new StringBuilder();
        for (String key : TRACKED) {
            if (!HEADERS.containsKey(key)) continue;
            if (out.length() > 0) out.append(',');
            out.append(key);
        }
        return out.toString();
    }

    static void invalidateAuthorization() {
        boolean changed = HEADERS.remove("authorization") != null;
        changed |= HEADERS.remove("client-token") != null;
        if (changed) REVISION.incrementAndGet();
    }

    private static boolean isTrackedKey(String key) {
        for (String tracked : TRACKED) {
            if (tracked.equals(key)) return true;
        }
        return false;
    }

    static void clearForTest() {
        HEADERS.clear();
        REVISION.set(0L);
    }
}
