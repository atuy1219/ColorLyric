/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.Normalizer;
import java.util.Locale;
import java.util.stream.Collectors;

final class LrclibClient {
    private static final String ENDPOINT = "https://lrclib.net/api/get";
    private static final String USER_AGENT = "ColorLyric/0.5.0 (https://github.com/atuy1219/ColorLyric)";

    private LrclibClient() {}

    static String fetch(StockLyricInfo.TrackSnapshot track) throws Exception {
        StringBuilder url = new StringBuilder(ENDPOINT)
                .append("?track_name=").append(enc(track.title))
                .append("&artist_name=").append(enc(track.artist))
                .append("&duration=").append(Math.round(track.durationMs / 1000.0));
        if (!track.album.isBlank()) url.append("&album_name=").append(enc(track.album));

        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setInstanceFollowRedirects(true);

        int code = connection.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            connection.disconnect();
            return null;
        }

        String body;
        try (InputStream input = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
            body = reader.lines().collect(Collectors.joining("\n"));
        } finally {
            connection.disconnect();
        }

        JSONObject result = new JSONObject(body);
        if (!matches(track.title, result.optString("trackName", ""))) return null;
        if (!matches(track.artist, result.optString("artistName", ""))) return null;
        long remoteDuration = Math.round(result.optDouble("duration", -1));
        long localDuration = Math.round(track.durationMs / 1000.0);
        if (remoteDuration < 0 || Math.abs(remoteDuration - localDuration) > 2) return null;
        if (!track.album.isBlank()) {
            String remoteAlbum = result.optString("albumName", "");
            if (!remoteAlbum.isBlank() && !matches(track.album, remoteAlbum)) return null;
        }
        if (result.optBoolean("instrumental", false)) return null;

        String synced = result.optString("syncedLyrics", "");
        return synced.isBlank() ? null : synced;
    }

    private static String enc(String value) throws Exception { return URLEncoder.encode(value, "UTF-8"); }
    private static boolean matches(String a, String b) { return canonical(a).equals(canonical(b)); }

    private static String canonical(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201c', '"').replace('\u201d', '"')
                .replace('\u2013', '-').replace('\u2014', '-');
        return normalized.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
