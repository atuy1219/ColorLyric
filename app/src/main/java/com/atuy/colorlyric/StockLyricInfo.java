/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

final class StockLyricInfo {
    static final String KEY = "lyricInfo";

    private StockLyricInfo() {}

    static String normalize(String value, TrackSnapshot track) {
        if (value == null || value.isBlank()) return null;
        try {
            JSONObject source = new JSONObject(value);
            String lyric = source.optString("lyric", "");
            if (lyric.isBlank()) return null;
            String title = nonBlank(source.optString("songName", ""), track.title);
            String artist = nonBlank(source.optString("artist", ""), track.artist);
            String translation = source.optString("transLyric", "");
            if (translation.isBlank()) translation = source.optString("translationLyric", "");
            String txtLyric = source.optString("txtLyric", "");
            return build(title, artist, track.numericSongId(), lyric, translation, txtLyric);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String build(TrackSnapshot track, String lyric) {
        return build(track.title, track.artist, track.numericSongId(), lyric, "", "");
    }

    private static String build(String title, String artist, long songId, String lyric,
                                String transLyric, String txtLyric) {
        try {
            JSONObject out = new JSONObject();
            out.put("id", 0);
            out.put("songName", title == null ? "" : title);
            out.put("artist", artist == null ? "" : artist);
            out.put("songId", songId);
            out.put("lyricType", 0);
            out.put("lyric", lyric == null ? "" : lyric);
            out.put("noLyric", lyric == null || lyric.isBlank());
            out.put("transLyric", transLyric == null ? "" : transLyric);
            out.put("txtLyric", txtLyric == null ? "" : txtLyric);
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static MediaMetadata withLyricInfo(MediaMetadata metadata, String value) {
        return new MediaMetadata.Builder(metadata).putString(KEY, value).build();
    }

    private static String nonBlank(String first, String fallback) {
        return first != null && !first.isBlank() ? first : (fallback == null ? "" : fallback);
    }

    static final class TrackSnapshot {
        final String mediaId;
        final String title;
        final String artist;
        final String album;
        final long durationMs;

        TrackSnapshot(String mediaId, String title, String artist, String album, long durationMs) {
            this.mediaId = clean(mediaId);
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.durationMs = durationMs > 0 ? durationMs : 0L;
        }

        static TrackSnapshot empty() {
            return new TrackSnapshot("", "", "", "", 0L);
        }

        static TrackSnapshot from(MediaMetadata metadata) {
            if (metadata == null) return empty();
            return new TrackSnapshot(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                    metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)
            );
        }

        TrackSnapshot merge(TrackSnapshot incoming) {
            if (incoming == null) return this;
            boolean incomingHasId = incoming.hasSpotifyTrackId();
            boolean thisHasId = hasSpotifyTrackId();
            if (incomingHasId && thisHasId && !mediaId.equals(incoming.mediaId)) {
                return incoming;
            }

            String mergedId = incomingHasId ? incoming.mediaId : mediaId;
            boolean sameIdentifiedTrack = incomingHasId && thisHasId && mediaId.equals(incoming.mediaId);

            String mergedTitle = choose(title, incoming.title, sameIdentifiedTrack);
            String mergedArtist = choose(artist, incoming.artist, sameIdentifiedTrack);
            String mergedAlbum = choose(album, incoming.album, sameIdentifiedTrack);
            long mergedDuration = durationMs;
            if (sameIdentifiedTrack && incoming.durationMs > 0) {
                mergedDuration = incoming.durationMs;
            } else if (mergedDuration <= 0 && incoming.durationMs > 0) {
                mergedDuration = incoming.durationMs;
            }

            return new TrackSnapshot(mergedId, mergedTitle, mergedArtist, mergedAlbum, mergedDuration);
        }

        boolean hasSpotifyTrackId() {
            return mediaId.startsWith("spotify:track:") && mediaId.length() > "spotify:track:".length();
        }

        boolean hasQueryIdentity() {
            return hasSpotifyTrackId() && !title.isBlank() && !artist.isBlank();
        }

        boolean hasDuration() {
            return durationMs >= 1_000L && durationMs <= 3_600_000L;
        }

        String key() {
            return hasSpotifyTrackId() ? mediaId : "";
        }

        long numericSongId() {
            byte[] bytes = mediaId.getBytes(StandardCharsets.UTF_8);
            long hash = 0xcbf29ce484222325L;
            for (byte value : bytes) {
                hash ^= value & 0xffL;
                hash *= 0x100000001b3L;
            }
            return hash & Long.MAX_VALUE;
        }

        String debugSummary() {
            return "id=" + hasSpotifyTrackId()
                    + " title=" + !title.isBlank()
                    + " artist=" + !artist.isBlank()
                    + " album=" + !album.isBlank()
                    + " duration=" + durationMs;
        }

        private static String choose(String current, String incoming, boolean sameIdentifiedTrack) {
            if (sameIdentifiedTrack && incoming != null && !incoming.isBlank()) return incoming;
            if (current == null || current.isBlank()) return incoming == null ? "" : incoming;
            return current;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
