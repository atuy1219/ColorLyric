/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SpotifyHeaderStore {
    private static final String[] REQUIRED = {
            "authorization",
            "client-token",
            "user-agent",
            "x-client-id"
    };

    private static final ConcurrentHashMap<String, String> HEADERS = new ConcurrentHashMap<>();

    private SpotifyHeaderStore() {}

    static boolean ingest(String name, String value) {
        if (name == null || value == null || value.isBlank()) return false;
        String key = name.toLowerCase(Locale.ROOT);
        if (!isRequiredKey(key)) return false;
        boolean wasReady = isReady();
        HEADERS.put(key, value);
        return !wasReady && isReady();
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
        for (String key : REQUIRED) {
            String value = HEADERS.get(key);
            if (value == null || value.isBlank()) return false;
        }
        return true;
    }

    static Map<String, String> snapshot() {
        return new HashMap<>(HEADERS);
    }

    static String capturedKeys() {
        StringBuilder out = new StringBuilder();
        for (String key : REQUIRED) {
            if (!HEADERS.containsKey(key)) continue;
            if (out.length() > 0) out.append(',');
            out.append(key);
        }
        return out.toString();
    }

    static void invalidateAuthorization() {
        HEADERS.remove("authorization");
        HEADERS.remove("client-token");
    }

    private static boolean isRequiredKey(String key) {
        for (String required : REQUIRED) {
            if (required.equals(key)) return true;
        }
        return false;
    }
}
