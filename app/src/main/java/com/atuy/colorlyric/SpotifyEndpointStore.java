/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Learns Spotify's live Color Lyrics URL shape from Spotify itself.
 *
 * <p>Only HTTPS URLs on spotify.com hosts containing a /color-lyrics/.../track/
 * path are accepted. The current track ID is replaced by a placeholder while the
 * complete query string is preserved, so endpoint version/host/query changes can
 * be followed without hard-coding each Spotify release.</p>
 */
final class SpotifyEndpointStore {
    private static final String TRACK_MARKER = "/track/";
    private static final String LYRICS_MARKER = "/color-lyrics/";
    private static final String TRACK_PLACEHOLDER = "{trackId}";
    private static final int MAX_OBSERVED_TEMPLATES = 4;

    private static final List<String> OBSERVED = new ArrayList<>();
    private static final AtomicLong REVISION = new AtomicLong();

    private SpotifyEndpointStore() {}

    static synchronized boolean observeUrl(String rawUrl) {
        String template = toTemplate(rawUrl);
        if (template == null) return false;

        boolean changed = OBSERVED.remove(template);
        OBSERVED.add(0, template);
        while (OBSERVED.size() > MAX_OBSERVED_TEMPLATES) {
            OBSERVED.remove(OBSERVED.size() - 1);
        }

        if (!changed) {
            REVISION.incrementAndGet();
            return true;
        }
        return false;
    }

    static synchronized List<String> candidateUrls(String rawTrackId, String language) {
        String trackId = rawTrackId == null ? "" : rawTrackId.trim();
        if (trackId.isEmpty()) return List.of();

        Set<String> urls = new LinkedHashSet<>();
        for (String template : OBSERVED) {
            urls.add(template.replace(TRACK_PLACEHOLDER, trackId));
        }

        String safeLanguage = language == null ? "" : language;
        urls.add("https://guc3-spclient.spotify.com/color-lyrics/v3/track/" + trackId
                + "?vocalRemoval=false&clientLanguage=" + safeLanguage + "&preview=false");
        urls.add("https://guc3-spclient.spotify.com/color-lyrics/v2/track/" + trackId
                + "?vocalRemoval=false&clientLanguage=" + safeLanguage + "&preview=false");
        return new ArrayList<>(urls);
    }

    static long revision() {
        return REVISION.get();
    }

    static synchronized int observedCount() {
        return OBSERVED.size();
    }

    private static String toTemplate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return null;
        try {
            URI uri = URI.create(rawUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme())) return null;
            String host = uri.getHost();
            if (host == null || !(host.equals("spotify.com") || host.endsWith(".spotify.com"))) {
                return null;
            }

            String path = uri.getRawPath();
            if (path == null) return null;
            int lyrics = path.indexOf(LYRICS_MARKER);
            if (lyrics < 0) return null;
            int track = path.indexOf(TRACK_MARKER, lyrics + LYRICS_MARKER.length());
            if (track < 0) return null;

            int idStart = track + TRACK_MARKER.length();
            if (idStart >= path.length()) return null;
            int idEnd = path.indexOf('/', idStart);
            if (idEnd < 0) idEnd = path.length();
            if (idEnd <= idStart) return null;

            String templatedPath = path.substring(0, idStart)
                    + TRACK_PLACEHOLDER
                    + path.substring(idEnd);
            StringBuilder out = new StringBuilder();
            out.append("https://").append(uri.getRawAuthority()).append(templatedPath);
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                out.append('?').append(uri.getRawQuery());
            }
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static synchronized void clearForTest() {
        OBSERVED.clear();
        REVISION.set(0L);
    }
}
