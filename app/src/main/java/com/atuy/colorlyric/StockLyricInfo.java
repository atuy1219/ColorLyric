/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.os.Parcel;

import org.json.JSONObject;

import java.util.Locale;

final class StockLyricInfo {
    static final String KEY = "lyricInfo";
    static final int MAX_PARCEL_BYTES = 512 * 1024;

    private StockLyricInfo() {}

    static String normalize(String value, TrackSnapshot track) {
        if (value == null || value.isBlank()) return null;
        try {
            JSONObject source = new JSONObject(value);
            return source.optString("lyric", "").isBlank() ? null : value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String build(TrackSnapshot track, String lyric, long generation) {
        if (track == null || lyric == null || lyric.isBlank()) return null;
        try {
            JSONObject out = new JSONObject();
            out.put("songName", track.title);
            out.put("artist", track.artist);
            out.put("songId", track.mediaId);
            out.put("lyricType", 0);
            out.put("id", "");
            out.put("lyric", lyric);
            out.put("noLyric", false);
            out.put("provider", "com.spotify.music");
            out.put("source", "com.spotify.music-v5");
            out.put("trackKey", track.stableKey());
            out.put("sessionGeneration", generation);
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static MediaMetadata withLyricInfo(MediaMetadata metadata, String value) {
        return new MediaMetadata.Builder(metadata).putString(KEY, value).build();
    }

    static int parcelSize(MediaMetadata metadata) {
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            metadata.writeToParcel(parcel, 0);
            return parcel.dataSize();
        } catch (Throwable ignored) {
            return -1;
        } finally {
            if (parcel != null) parcel.recycle();
        }
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
            if (incomingHasId && thisHasId && !mediaId.equals(incoming.mediaId)) return incoming;

            String mergedId = incomingHasId ? incoming.mediaId : mediaId;
            boolean sameTrack = incomingHasId && thisHasId && mediaId.equals(incoming.mediaId);
            String mergedTitle = choose(title, incoming.title, sameTrack);
            String mergedArtist = choose(artist, incoming.artist, sameTrack);
            String mergedAlbum = choose(album, incoming.album, sameTrack);
            long mergedDuration = durationMs;
            if (sameTrack && incoming.durationMs > 0) mergedDuration = incoming.durationMs;
            else if (mergedDuration <= 0 && incoming.durationMs > 0) mergedDuration = incoming.durationMs;
            return new TrackSnapshot(mergedId, mergedTitle, mergedArtist, mergedAlbum, mergedDuration);
        }

        boolean hasSpotifyTrackId() {
            return mediaId.startsWith("spotify:track:")
                    && mediaId.length() > "spotify:track:".length();
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

        String stableKey() {
            long seconds = durationMs > 0 ? durationMs / 1000L : 0L;
            return mediaId + "|" + title.toLowerCase(Locale.ROOT) + "|"
                    + artist.toLowerCase(Locale.ROOT) + "|" + seconds;
        }

        String debugSummary() {
            return "id=" + hasSpotifyTrackId()
                    + " title=" + !title.isBlank()
                    + " artist=" + !artist.isBlank()
                    + " album=" + !album.isBlank()
                    + " duration=" + durationMs;
        }

        private static String choose(String current, String incoming, boolean sameTrack) {
            if (sameTrack && incoming != null && !incoming.isBlank()) return incoming;
            if (current == null || current.isBlank()) return incoming == null ? "" : incoming;
            return current;
        }

        private static String clean(String value) {
            return value == null ? "" : value.trim();
        }
    }
}
