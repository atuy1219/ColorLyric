/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
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
    private static final SpotifySessionRegistry REGISTRY = new SpotifySessionRegistry();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
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
    private static volatile StockLyricInfo.TrackSnapshot currentTrack = StockLyricInfo.TrackSnapshot.empty();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SPOTIFY.equals(lpparam.packageName) || !SPOTIFY.equals(lpparam.processName)) return;
        log("loaded in Spotify main process; SystemUI not hooked");

        XposedBridge.hookAllConstructors(MediaSession.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof MediaSession)) return;
                MediaSession session = (MediaSession) param.thisObject;
                String tag = SpotifySessionRegistry.constructorTag(param.args);
                REGISTRY.onConstructed(session, tag);
                log("session constructed tag=" + tag + " cast=" + SpotifySessionRegistry.isCastTag(tag));
            }
        });

        XposedBridge.hookAllMethods(MediaSession.class, "setMetadata", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
                if (!(param.thisObject instanceof MediaSession) || param.args.length == 0
                        || !(param.args[0] instanceof MediaMetadata)) return;
                onMetadata((MediaSession) param.thisObject, (MediaMetadata) param.args[0], param);
            }
        });

        XposedBridge.hookAllMethods(MediaSession.class, "setPlaybackState", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof MediaSession) || param.args.length == 0
                        || !(param.args[0] instanceof PlaybackState)) return;
                MediaSession session = (MediaSession) param.thisObject;
                REGISTRY.onPlaybackState(session, ((PlaybackState) param.args[0]).getState());
                replayCachedIfReady(session, "playback");
            }
        });

        XposedBridge.hookAllMethods(MediaSession.class, "setActive", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof MediaSession) || param.args.length == 0
                        || !(param.args[0] instanceof Boolean)) return;
                MediaSession session = (MediaSession) param.thisObject;
                REGISTRY.onActive(session, (Boolean) param.args[0]);
                replayCachedIfReady(session, "active");
            }
        });

        XposedBridge.hookAllMethods(MediaSession.class, "release", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject instanceof MediaSession) {
                    REGISTRY.onReleased((MediaSession) param.thisObject);
                }
            }
        });

        log("MediaSession hooks installed");
    }

    private static void onMetadata(MediaSession session, MediaMetadata metadata,
                                   XC_MethodHook.MethodHookParam param) {
        StockLyricInfo.TrackSnapshot sessionTrack = REGISTRY.onHostMetadata(session, metadata);
        if (REGISTRY.isCast(session)) return;

        Observation observation = observe(sessionTrack);
        StockLyricInfo.TrackSnapshot track = observation.track;
        String existing = safeLyricInfo(metadata);

        if (existing != null && !existing.isBlank()) {
            String accepted = StockLyricInfo.normalize(existing, track);
            if (accepted != null && track.hasSpotifyTrackId()) {
                CACHE.put(track.key(), accepted);
                log("existing native lyricInfo observed: " + shortId(track.mediaId));
            }
            return;
        }

        if (!track.hasSpotifyTrackId()) return;

        String cached = CACHE.get(track.key());
        if (cached != null) {
            MediaMetadata patched = StockLyricInfo.withLyricInfo(metadata, cached);
            int parcelBytes = StockLyricInfo.parcelSize(patched);
            if (parcelBytes > 0 && parcelBytes <= StockLyricInfo.MAX_PARCEL_BYTES) {
                param.args[0] = patched;
                REGISTRY.onHostMetadata(session, patched);
                log("cached lyricInfo injected into host metadata: " + shortId(track.mediaId)
                        + " parcel=" + parcelBytes);
            }
            return;
        }

        if (!track.hasQueryIdentity()) {
            log("metadata incomplete: " + track.debugSummary());
            return;
        }

        scheduleFetch(track.key(), observation.generation);
    }

    private static Observation observe(StockLyricInfo.TrackSnapshot incoming) {
        synchronized (STATE_LOCK) {
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
                    log("LRCLIB skipped; identity incomplete: " + track.debugSummary());
                    return;
                }

                LrclibClient.Result result = LrclibClient.fetch(track);
                log("LRCLIB outcome=" + result.outcome + " track=" + shortId(track.mediaId)
                        + " " + track.debugSummary());
                if (!result.isSuccess()) return;

                String payload = StockLyricInfo.build(track, result.syncedLyrics, generation);
                if (payload == null || !isCurrent(trackKey, generation)) return;
                CACHE.put(trackKey, payload);
                MAIN.post(() -> publish(trackKey, generation, payload, "fetch"));
            } catch (Throwable error) {
                log("LRCLIB fetch failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                IN_FLIGHT.remove(trackKey);
            }
        }, 500, TimeUnit.MILLISECONDS);
    }

    private static void replayCachedIfReady(MediaSession session, String reason) {
        if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
        SpotifySessionRegistry.Selection selection = REGISTRY.selectionFor(session);
        if (selection == null || !selection.track.hasSpotifyTrackId()) return;
        if (!selection.active || !SpotifySessionRegistry.isPlaybackStateValid(selection.playbackState)) return;
        String payload = CACHE.get(selection.track.key());
        if (payload == null) return;
        publishToSelection(selection.track.key(), GENERATION.get(), payload, selection, reason);
    }

    private static StockLyricInfo.TrackSnapshot snapshotCurrent(String trackKey, long generation) {
        synchronized (STATE_LOCK) {
            if (!isCurrent(trackKey, generation)) return null;
            return currentTrack;
        }
    }

    private static void publish(String trackKey, long generation, String payload, String reason) {
        if (!isCurrent(trackKey, generation)) return;
        SpotifySessionRegistry.Selection selection = REGISTRY.select(trackKey);
        if (selection == null) {
            log("publish pending; no unique main session: " + REGISTRY.describe());
            return;
        }
        publishToSelection(trackKey, generation, payload, selection, reason);
    }

    private static void publishToSelection(String trackKey, long generation, String payload,
                                           SpotifySessionRegistry.Selection selection, String reason) {
        if (!isCurrent(trackKey, generation)) return;
        if (selection == null || selection.session == null || selection.metadata == null) return;
        if (SpotifySessionRegistry.isCastTag(selection.tag)) return;
        if (selection.track.hasSpotifyTrackId() && !trackKey.equals(selection.track.key())) return;

        try {
            MediaMetadata patched = StockLyricInfo.withLyricInfo(selection.metadata, payload);
            int parcelBytes = StockLyricInfo.parcelSize(patched);
            if (parcelBytes <= 0) {
                log("publish rejected; parcel measurement failed");
                return;
            }
            if (parcelBytes > StockLyricInfo.MAX_PARCEL_BYTES) {
                log("publish rejected; parcel too large=" + parcelBytes);
                return;
            }

            MODULE_WRITE.set(Boolean.TRUE);
            selection.session.setMetadata(patched);
            REGISTRY.onHostMetadata(selection.session, patched);
            log("native lyricInfo committed: " + shortId(trackKey)
                    + " reason=" + reason
                    + " tag=" + selection.tag
                    + " active=" + selection.active
                    + " state=" + selection.playbackState
                    + " parcel=" + parcelBytes);
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
