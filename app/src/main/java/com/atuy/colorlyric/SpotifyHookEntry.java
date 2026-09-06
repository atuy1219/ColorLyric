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
            new LinkedHashMap<String, String>(24, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > 24;
                }
            }
    );

    private static volatile String currentTrackKey = "";
    private static volatile MediaSession currentSession;
    private static volatile MediaMetadata currentMetadata;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SPOTIFY.equals(lpparam.packageName) || !SPOTIFY.equals(lpparam.processName)) return;
        log("loaded in Spotify main process");
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

    private static void onMetadata(MediaSession session, MediaMetadata metadata, XC_MethodHook.MethodHookParam param) {
        StockLyricInfo.TrackSnapshot track = StockLyricInfo.TrackSnapshot.from(metadata);
        long generation = noteTrack(session, metadata, track);

        String existing = metadata.getString(StockLyricInfo.KEY);
        if (existing != null && !existing.isBlank()) {
            String normalized = StockLyricInfo.normalize(existing, track);
            if (normalized != null) {
                if (track.isMusicTrack()) CACHE.put(track.key(), normalized);
                if (!normalized.equals(existing)) {
                    param.args[0] = StockLyricInfo.withLyricInfo(metadata, normalized);
                    currentMetadata = (MediaMetadata) param.args[0];
                    log("QQ schema applied from existing lyricInfo: " + shortId(track.mediaId));
                }
            }
            return;
        }

        if (!track.isMusicTrack()) return;
        String cached = CACHE.get(track.key());
        if (cached != null) {
            param.args[0] = StockLyricInfo.withLyricInfo(metadata, cached);
            currentMetadata = (MediaMetadata) param.args[0];
            return;
        }

        scheduleFetch(track, generation);
    }

    private static long noteTrack(MediaSession session, MediaMetadata metadata, StockLyricInfo.TrackSnapshot track) {
        synchronized (STATE_LOCK) {
            currentSession = session;
            currentMetadata = metadata;
            if (track.isMusicTrack() && !track.key().equals(currentTrackKey)) {
                currentTrackKey = track.key();
                long generation = GENERATION.incrementAndGet();
                log("track=" + shortId(track.mediaId) + " generation=" + generation);
                return generation;
            }
            return GENERATION.get();
        }
    }

    private static void scheduleFetch(StockLyricInfo.TrackSnapshot track, long generation) {
        if (!IN_FLIGHT.add(track.key())) return;
        EXECUTOR.schedule(() -> {
            try {
                if (!isCurrent(track.key(), generation) || CACHE.containsKey(track.key())) return;
                String lyric = LrclibClient.fetch(track);
                if (lyric == null) {
                    log("LRCLIB no exact synced lyric: " + shortId(track.mediaId));
                    return;
                }
                String payload = StockLyricInfo.build(track, lyric);
                if (payload == null || !isCurrent(track.key(), generation)) return;
                CACHE.put(track.key(), payload);
                publish(track.key(), generation, payload);
            } catch (Throwable error) {
                log("LRCLIB fetch failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                IN_FLIGHT.remove(track.key());
            }
        }, 700, TimeUnit.MILLISECONDS);
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
        StockLyricInfo.TrackSnapshot live = StockLyricInfo.TrackSnapshot.from(metadata);
        if (!trackKey.equals(live.key())) return;

        try {
            MODULE_WRITE.set(Boolean.TRUE);
            MediaMetadata patched = StockLyricInfo.withLyricInfo(metadata, payload);
            currentMetadata = patched;
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
}
