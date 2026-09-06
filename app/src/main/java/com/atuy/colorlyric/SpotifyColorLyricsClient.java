/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

final class SpotifyColorLyricsClient {
    private static final String BASE_URL =
            "https://guc3-spclient.spotify.com/color-lyrics/v2/track/";
    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    private SpotifyColorLyricsClient() {}

    static final class FetchCall {
        private volatile HttpURLConnection connection;
        private volatile boolean cancelled;

        void bind(HttpURLConnection value) throws InterruptedIOException {
            if (cancelled) {
                value.disconnect();
                throw new InterruptedIOException("cancelled");
            }
            connection = value;
            if (cancelled) {
                value.disconnect();
                throw new InterruptedIOException("cancelled");
            }
        }

        void clear(HttpURLConnection value) {
            if (connection == value) connection = null;
        }

        void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        boolean isCancelled() {
            return cancelled;
        }
    }

    static final class Result {
        final String lineLyric;
        final String rawLyric;
        final String syncType;
        final String source;
        final String outcome;

        Result(String lineLyric, String rawLyric, String syncType, String source, String outcome) {
            this.lineLyric = lineLyric;
            this.rawLyric = rawLyric;
            this.syncType = syncType == null ? "" : syncType;
            this.source = source == null ? "color-lyrics" : source;
            this.outcome = outcome == null ? "unknown" : outcome;
        }

        boolean isSuccess() {
            return lineLyric != null && !lineLyric.isBlank();
        }

        static Result miss(String outcome) {
            return new Result(null, null, "", "", outcome);
        }
    }

    static Result fetch(
            StockLyricInfo.TrackSnapshot track,
            Map<String, String> headers,
            FetchCall call) throws Exception {
        if (track == null || !track.hasSpotifyTrackId()) return Result.miss("invalid-track");
        if (headers == null || headers.isEmpty()) return Result.miss("headers-missing");
        if (call != null && call.isCancelled()) return Result.miss("cancelled");

        String rawId = track.mediaId.substring("spotify:track:".length());
        String language = Locale.getDefault().toLanguageTag();
        String url = BASE_URL + rawId
                + "?vocalRemoval=false&clientLanguage=" + language
                + "&preview=false";

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            if (call != null) call.bind(connection);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("accept", "application/json");
            connection.setRequestProperty("app-platform", "WebPlayer");
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || entry.getValue().isBlank()) continue;
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }

            if (call != null && call.isCancelled()) return Result.miss("cancelled");
            int code = connection.getResponseCode();
            if (call != null && call.isCancelled()) return Result.miss("cancelled");

            InputStream stream = code >= 200 && code < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String body = "";
            if (stream != null) {
                try (InputStream input = stream;
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(input, StandardCharsets.UTF_8))) {
                    body = reader.lines().collect(Collectors.joining("\n"));
                }
            }

            if (code == 401) return Result.miss("http-401");
            if (code == 403) return Result.miss("http-403");
            if (code == 404) return Result.miss("http-404");
            if (code == 429) return Result.miss("http-429");
            if (code < 200 || code >= 300) return Result.miss("http-" + code);
            if (body.isBlank()) return Result.miss("empty-body");
            if (call != null && call.isCancelled()) return Result.miss("cancelled");

            return decode(body);
        } catch (InterruptedIOException error) {
            if (call != null && call.isCancelled()) return Result.miss("cancelled");
            throw error;
        } finally {
            if (call != null && connection != null) call.clear(connection);
            if (connection != null) connection.disconnect();
        }
    }

    static Result decode(String body) {
        try {
            JSONObject root = new JSONObject(body);
            JSONObject lyrics = root.optJSONObject("lyrics");
            if (lyrics == null) return Result.miss("lyrics-missing");
            JSONArray sourceLines = lyrics.optJSONArray("lines");
            if (sourceLines == null || sourceLines.length() == 0) {
                return Result.miss("lines-empty");
            }

            List<Line> lines = new ArrayList<>();
            for (int i = 0; i < sourceLines.length(); i++) {
                JSONObject item = sourceLines.optJSONObject(i);
                if (item == null) continue;
                String text = item.optString("words", "");
                if (text == null || text.trim().isEmpty()) continue;
                long start = flexibleLong(item.opt("startTimeMs"));
                long end = flexibleLong(item.opt("endTimeMs"));
                JSONArray syllables = item.optJSONArray("syllables");
                lines.add(new Line(start, end, text, syllables));
            }
            if (lines.isEmpty()) return Result.miss("lines-empty");

            StringBuilder lineLrc = new StringBuilder();
            StringBuilder enhanced = new StringBuilder();
            boolean hasWordTiming = false;

            for (int i = 0; i < lines.size(); i++) {
                Line line = lines.get(i);
                long nextStart = i + 1 < lines.size() ? lines.get(i + 1).start : -1L;
                long lineEnd = line.end > line.start
                        ? line.end
                        : (nextStart > line.start ? nextStart : line.start + 5_000L);

                lineLrc.append('[').append(formatTime(line.start)).append(']')
                        .append(cleanPlain(line.text)).append('\n');

                JSONArray syllables = line.syllables;
                if (syllables != null && syllables.length() > 0) {
                    int appendedWords = 0;
                    enhanced.append('[').append(formatTime(line.start)).append(']');
                    for (int j = 0; j < syllables.length(); j++) {
                        JSONObject syllable = syllables.optJSONObject(j);
                        if (syllable == null) continue;
                        String segment = firstNonBlank(
                                syllable.optString("chars", null),
                                syllable.optString("text", null),
                                syllable.optString("words", null),
                                syllable.optString("syllable", null));
                        if (segment == null || segment.isEmpty()) continue;
                        long wordStart = Math.max(line.start,
                                flexibleLong(syllable.opt("startTimeMs")));
                        enhanced.append('<').append(formatTime(wordStart)).append('>')
                                .append(cleanInline(segment));
                        appendedWords++;
                    }
                    if (appendedWords > 0) {
                        enhanced.append('<').append(formatTime(lineEnd)).append('>').append('\n');
                        hasWordTiming = true;
                    } else {
                        enhanced.append(cleanPlain(line.text)).append('\n');
                    }
                } else {
                    enhanced.append('[').append(formatTime(line.start)).append(']')
                            .append(cleanPlain(line.text)).append('\n');
                }
            }

            String syncType = lyrics.optString("syncType", "");
            String source = lyrics.optString("provider", "color-lyrics");
            return new Result(
                    lineLrc.toString(),
                    hasWordTiming ? enhanced.toString() : null,
                    syncType,
                    source,
                    "ok");
        } catch (Throwable error) {
            return Result.miss("decode-" + error.getClass().getSimpleName());
        }
    }

    private static long flexibleLong(Object value) {
        if (value instanceof Number) return Math.max(0L, ((Number) value).longValue());
        if (value instanceof String) {
            try {
                return Math.max(0L, Long.parseLong((String) value));
            } catch (NumberFormatException ignored) {
            }
        }
        return 0L;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private static String cleanPlain(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static String cleanInline(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ');
    }

    private static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60_000L;
        long seconds = (safe % 60_000L) / 1_000L;
        long ms = safe % 1_000L;
        return String.format(Locale.ROOT, "%02d:%02d.%03d", minutes, seconds, ms);
    }

    private static final class Line {
        final long start;
        final long end;
        final String text;
        final JSONArray syllables;

        Line(long start, long end, String text, JSONArray syllables) {
            this.start = Math.max(0L, start);
            this.end = Math.max(0L, end);
            this.text = text == null ? "" : text;
            this.syllables = syllables;
        }
    }
}
