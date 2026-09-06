/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SpotifyHookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "ColorLyric";
    private static final String SPOTIFY = "com.spotify.music";
    private static final Object STATE_LOCK = new Object();
    private static final ThreadLocal<Boolean> MODULE_WRITE = new ThreadLocal<>();
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ColorLyric-Lyrics");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<String, String> CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 32;
                }
            }
    );

    private static volatile String currentTrackKey = "";
    private static volatile MediaSession currentSession;
    private static volatile MediaMetadata currentMetadata;
    private static volatile StockLyricInfo.TrackSnapshot currentTrack = StockLyricInfo.TrackSnapshot.empty();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SPOTIFY.equals(lpparam.packageName) || !SPOTIFY.equals(lpparam.processName)) return;
        log("loaded in Spotify main process; provider not required");

        XposedBridge.hookAllMethods(MediaSession.class, "setMetadata", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
                if (!(param.thisObject instanceof MediaSession) || param.args.length == 0
                        || !(param.args[0] instanceof MediaMetadata)) return;
                onMetadata((MediaSession) param.thisObject, (MediaMetadata) param.args[0], param);
            }
        });
    }

    private static void onMetadata(MediaSession session, MediaMetadata metadata,
                                   XC_MethodHook.MethodHookParam param) {
        Observation observation = observe(session, metadata);
        StockLyricInfo.TrackSnapshot track = observation.track;

        String existing = safeLyricInfo(metadata);
        if (existing != null && !existing.isBlank()) {
            String normalized = StockLyricInfo.normalize(existing, track);
            if (normalized != null) {
                if (track.hasSpotifyTrackId()) CACHE.put(track.key(), normalized);
                MediaMetadata patched = normalized.equals(existing)
                        ? metadata
                        : StockLyricInfo.withLyricInfo(metadata, normalized);
                if (patched != metadata) {
                    param.args[0] = patched;
                    currentMetadata = patched;
                    log("QQ schema applied from existing lyricInfo: " + shortId(track.mediaId));
                }
            }
            return;
        }

        if (!track.hasSpotifyTrackId()) return;

        String cached = CACHE.get(track.key());
        if (cached != null) {
            MediaMetadata patched = StockLyricInfo.withLyricInfo(metadata, cached);
            param.args[0] = patched;
            currentMetadata = patched;
            log("cached lyricInfo re-injected: " + shortId(track.mediaId));
            return;
        }

        if (!track.hasQueryIdentity()) {
            log("metadata incomplete: " + track.debugSummary());
            return;
        }

        scheduleFetch(track.key(), observation.generation);
    }

    private static Observation observe(MediaSession session, MediaMetadata metadata) {
        StockLyricInfo.TrackSnapshot incoming = StockLyricInfo.TrackSnapshot.from(metadata);
        synchronized (STATE_LOCK) {
            currentSession = session;
            currentMetadata = metadata;

            boolean incomingHasId = incoming.hasSpotifyTrackId();
            if (incomingHasId && !incoming.mediaId.equals(currentTrackKey)) {
                currentTrackKey = incoming.mediaId;
                currentTrack = incoming;
                long generation = GENERATION.incrementAndGet();
                log("track=" + shortId(incoming.mediaId) + " generation=" + generation
                        + " " + incoming.debugSummary());
                return new Observation(currentTrack, generation);
            }

            if (incomingHasId && incoming.mediaId.equals(currentTrackKey)) {
                currentTrack = currentTrack.merge(incoming);
            } else if (!incomingHasId && !currentTrackKey.isBlank()) {
                currentTrack = currentTrack.merge(incoming);
            } else if (currentTrackKey.isBlank()) {
                currentTrack = incoming;
            }
            return new Observation(currentTrack, GENERATION.get());
        }
    }

    private static void scheduleFetch(String trackKey, long generation) {
        if (!IN_FLIGHT.add(trackKey)) return;
        EXECUTOR.schedule(() -> {
            try {
                StockLyricInfo.TrackSnapshot track = snapshotCurrent(trackKey, generation);
                if (track == null || CACHE.containsKey(trackKey)) return;
                if (!track.hasQueryIdentity()) {
                    log("LRCLIB skipped; identity still incomplete: " + track.debugSummary());
                    return;
                }

                LrclibClient.Result result = LrclibClient.fetch(track);
                log("LRCLIB outcome=" + result.outcome + " track=" + shortId(track.mediaId)
                        + " " + track.debugSummary());
                if (!result.isSuccess()) return;

                String payload = StockLyricInfo.build(track, result.syncedLyrics);
                if (payload == null || !isCurrent(trackKey, generation)) return;
                CACHE.put(trackKey, payload);
                publish(trackKey, generation, payload);
            } catch (Throwable error) {
                log("LRCLIB fetch failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                IN_FLIGHT.remove(trackKey);
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static StockLyricInfo.TrackSnapshot snapshotCurrent(String trackKey, long generation) {
        synchronized (STATE_LOCK) {
            if (!isCurrent(trackKey, generation)) return null;
            return currentTrack;
        }
    }

    private static void publish(String trackKey, long generation, String payload) {
        MediaSession session;
        MediaMetadata metadata;
        synchronized (STATE_LOCK) {
            if (!isCurrent(trackKey, generation)) return;
            session = currentSession;
            metadata = currentMetadata;
        }
        if (session == null || metadata == null) return;

        StockLyricInfo.TrackSnapshot raw = StockLyricInfo.TrackSnapshot.from(metadata);
        if (raw.hasSpotifyTrackId() && !trackKey.equals(raw.key())) return;

        try {
            MODULE_WRITE.set(Boolean.TRUE);
            MediaMetadata patched = StockLyricInfo.withLyricInfo(metadata, payload);
            synchronized (STATE_LOCK) {
                currentMetadata = patched;
            }
            session.setMetadata(patched);
            log("stock lyricInfo published: " + shortId(trackKey));
        } catch (Throwable error) {
            log("publish failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            MODULE_WRITE.remove();
        }
    }

    private static boolean isCurrent(String trackKey, long generation) {
        return generation == GENERATION.get() && trackKey.equals(currentTrackKey);
    }

    private static String safeLyricInfo(MediaMetadata metadata) {
        try {
            return metadata.getString(StockLyricInfo.KEY);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String shortId(String value) {
        if (value == null) return "";
        int index = value.lastIndexOf(':');
        String id = index >= 0 ? value.substring(index + 1) : value;
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log("[ColorLyric] " + message);
    }

    private static final class Observation {
        final StockLyricInfo.TrackSnapshot track;
        final long generation;

        Observation(StockLyricInfo.TrackSnapshot track, long generation) {
            this.track = track;
            this.generation = generation;
        }
    }
}
