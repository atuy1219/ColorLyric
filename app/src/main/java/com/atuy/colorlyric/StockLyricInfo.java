/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.os.Parcel;

import org.json.JSONObject;

import java.util.Locale;

final class StockLyricInfo {
    static final String KEY = "lyricInfo";
    static final String PLATFORM_LYRIC_KEY = "android.media.metadata.LYRIC";
    static final String OPLUS_RATING_URI_KEY = "ratingUri";
    static final int MAX_PARCEL_BYTES = 512 * 1024;

    private StockLyricInfo() {}

    static String normalize(String value, TrackSnapshot track) {
        if (value == null || value.isBlank()) return null;
        try {
            JSONObject source = new JSONObject(value);
            if (source.optString("lyric", "").isBlank()) return null;

            String songId = source.optString("songId", "").trim();
            if (track != null && track.hasSpotifyTrackId()
                    && songId.startsWith("spotify:track:")
                    && !track.mediaId.equals(songId)) {
                return null;
            }
            return value;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String rebindGeneration(String value, long generation) {
        if (value == null || value.isBlank()) return value;
        try {
            JSONObject object = new JSONObject(value);
            object.put("sessionGeneration", generation);
            return object.toString();
        } catch (Throwable ignored) {
            return value;
        }
    }

    static String build(TrackSnapshot track, String lyric, long generation) {
        return build(track, lyric, null, generation, "", "color-lyrics");
    }

    static String build(
            TrackSnapshot track,
            String lyric,
            String rawLyric,
            long generation,
            String syncType,
            String sourceName) {
        if (track == null || lyric == null || lyric.isBlank()) return null;
        try {
            String timedLyric = decorateLrc(track, lyric);
            String timedRawLyric = rawLyric == null || rawLyric.isBlank()
                    ? null : decorateLrc(track, rawLyric);

            JSONObject out = new JSONObject();
            out.put("songName", track.title);
            out.put("artist", track.artist);
            out.put("album", track.album);
            out.put("songId", track.mediaId);
            out.put("lyricType", 0);
            out.put("id", "");
            out.put("lyric", timedLyric);
            out.put("noLyric", false);
            out.put("provider", "com.spotify.music");
            out.put("source", "com.spotify.music-v5");
            out.put("trackKey", track.stableKey());
            out.put("sessionGeneration", generation);
            out.put("transLyric", "");
            out.put("txtLyric", "");
            if (timedRawLyric != null) out.put("rawLyric", timedRawLyric);
            if (syncType != null && !syncType.isBlank()) out.put("syncType", syncType);
            if (sourceName != null && !sourceName.isBlank()) out.put("spotifyLyricSource", sourceName);
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String decorateLrc(TrackSnapshot track, String lyric) {
        String normalized = lyric.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(normalized.length() + 96);
        if (!track.title.isBlank()) out.append("[ti:").append(cleanTag(track.title)).append("]\n");
        if (!track.artist.isBlank()) out.append("[ar:").append(cleanTag(track.artist)).append("]\n");
        out.append(normalized);
        if (!normalized.endsWith("\n")) out.append('\n');
        return out.toString();
    }

    private static String cleanTag(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    static MediaMetadata withLyricInfo(MediaMetadata metadata, String value) {
        MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata).putString(KEY, value);
        try {
            JSONObject payload = new JSONObject(value);
            String lyric = payload.optString("lyric", "");
            if (!lyric.isBlank()) {
                builder.putString(PLATFORM_LYRIC_KEY, lyric);
            }
            String songId = payload.optString("songId", "");
            String ratingUri = toSpotifyRatingUri(songId);
            if (!ratingUri.isBlank()) {
                builder.putString(OPLUS_RATING_URI_KEY, ratingUri);
            }
        } catch (Throwable ignored) {
        }
        return builder.build();
    }

    private static String toSpotifyRatingUri(String songId) {
        if (songId == null || songId.isBlank()) return "";
        if (songId.startsWith("spotify:track:")) {
            String raw = songId.substring("spotify:track:".length());
            return raw.isBlank() ? "" : "spotify://track/" + raw;
        }
        return "";
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
