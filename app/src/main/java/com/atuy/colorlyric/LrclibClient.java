/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Collectors;

final class LrclibClient {
    private static final String GET_ENDPOINT = "https://lrclib.net/api/get";
    private static final String SEARCH_ENDPOINT = "https://lrclib.net/api/search";
    private static final String USER_AGENT = "ColorLyric/0.6.0 (https://github.com/atuy1219/ColorLyric)";

    private LrclibClient() {}

    static final class Result {
        final String syncedLyrics;
        final String outcome;

        private Result(String syncedLyrics, String outcome) {
            this.syncedLyrics = syncedLyrics;
            this.outcome = outcome;
        }

        static Result success(String lyric, String source) {
            return new Result(lyric, source);
        }

        static Result miss(String reason) {
            return new Result(null, reason);
        }

        boolean isSuccess() {
            return syncedLyrics != null && !syncedLyrics.isBlank();
        }
    }

    static Result fetch(StockLyricInfo.TrackSnapshot track) throws Exception {
        if (track == null || !track.hasQueryIdentity()) return Result.miss("identity-incomplete");

        if (track.hasDuration()) {
            HttpResponse response = request(buildGetUrl(track));
            if (response.code == HttpURLConnection.HTTP_OK) {
                JSONObject item = new JSONObject(response.body);
                if (matchesCandidate(track, item)) {
                    String synced = item.optString("syncedLyrics", "");
                    if (!synced.isBlank()) return Result.success(synced, "get");
                }
            } else if (response.code != HttpURLConnection.HTTP_NOT_FOUND) {
                return Result.miss("get-http-" + response.code);
            }
        }

        return search(track);
    }

    private static Result search(StockLyricInfo.TrackSnapshot track) throws Exception {
        HttpResponse response = request(buildSearchUrl(track));
        if (response.code != HttpURLConnection.HTTP_OK) {
            return Result.miss("search-http-" + response.code);
        }

        JSONArray array = new JSONArray(response.body);
        String accepted = null;
        int acceptedCount = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null || !matchesCandidate(track, item)) continue;
            String synced = item.optString("syncedLyrics", "");
            if (synced.isBlank()) continue;
            accepted = synced;
            acceptedCount++;
            if (acceptedCount > 1) return Result.miss("search-ambiguous");
        }

        if (acceptedCount == 1) return Result.success(accepted, "search");
        return Result.miss("search-no-exact-match");
    }

    private static boolean matchesCandidate(StockLyricInfo.TrackSnapshot track, JSONObject item) {
        if (item.optBoolean("instrumental", false)) return false;
        if (!matches(track.title, item.optString("trackName", ""))) return false;
        if (!matches(track.artist, item.optString("artistName", ""))) return false;

        if (!track.album.isBlank()) {
            String remoteAlbum = item.optString("albumName", "");
            if (remoteAlbum.isBlank() || !matches(track.album, remoteAlbum)) return false;
        }

        if (track.hasDuration()) {
            long remoteDuration = Math.round(item.optDouble("duration", -1));
            long localDuration = Math.round(track.durationMs / 1000.0);
            if (remoteDuration < 0 || Math.abs(remoteDuration - localDuration) > 2) return false;
        }
        return true;
    }

    private static String buildGetUrl(StockLyricInfo.TrackSnapshot track) {
        StringBuilder url = new StringBuilder(GET_ENDPOINT)
                .append("?track_name=").append(enc(track.title))
                .append("&artist_name=").append(enc(track.artist));
        if (!track.album.isBlank()) url.append("&album_name=").append(enc(track.album));
        if (track.hasDuration()) {
            url.append("&duration=").append(Math.round(track.durationMs / 1000.0));
        }
        return url.toString();
    }

    private static String buildSearchUrl(StockLyricInfo.TrackSnapshot track) {
        StringBuilder url = new StringBuilder(SEARCH_ENDPOINT)
                .append("?track_name=").append(enc(track.title))
                .append("&artist_name=").append(enc(track.artist));
        if (!track.album.isBlank()) url.append("&album_name=").append(enc(track.album));
        return url.toString();
    }

    private static HttpResponse request(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400
                ? connection.getInputStream()
                : connection.getErrorStream();
        String body = "";
        if (stream != null) {
            try (InputStream input = stream;
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                body = reader.lines().collect(Collectors.joining("\n"));
            }
        }
        connection.disconnect();
        return new HttpResponse(code, body);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean matches(String a, String b) {
        return canonical(a).equals(canonical(b));
    }

    private static String canonical(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2013', '-').replace('\u2014', '-');
        return normalized.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final class HttpResponse {
        final int code;
        final String body;

        HttpResponse(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }
}
