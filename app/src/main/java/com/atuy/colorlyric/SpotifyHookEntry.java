/* SPDX-License-Identifier: GPL-3.0-only */
package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "ColorLyric-SpotifyLyrics");
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
    private static volatile String committedTrackKey = "";
    private static volatile long committedGeneration = -1L;
    private static volatile boolean missingHeadersLogged;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SPOTIFY.equals(lpparam.packageName) || !SPOTIFY.equals(lpparam.processName)) return;
        log("loaded in Spotify main process; SystemUI not hooked");

        installMediaSessionHooks();
        installSpotifyHeaderHooks(lpparam.classLoader);
        log("Spotify hooks installed");
    }

    private static void installMediaSessionHooks() {
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
                publishCachedOnceIfReady(session, "playback-ready");
            }
        });

        XposedBridge.hookAllMethods(MediaSession.class, "setActive", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof MediaSession) || param.args.length == 0
                        || !(param.args[0] instanceof Boolean)) return;
                MediaSession session = (MediaSession) param.thisObject;
                REGISTRY.onActive(session, (Boolean) param.args[0]);
                publishCachedOnceIfReady(session, "active-ready");
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
    }

    private static void installSpotifyHeaderHooks(ClassLoader classLoader) {
        int installed = 0;
        installed += hookHeaderContainer(classLoader, "p.ot10");
        installed += hookHeaderContainer(classLoader, "okhttp3.Headers");
        installed += hookAddHeader(classLoader, "p.aj81");
        installed += hookAddHeader(classLoader, "org.chromium.net.UrlRequest$Builder");
        log("Spotify auth header hooks installed=" + installed);
    }

    private static int hookHeaderContainer(ClassLoader classLoader, String className) {
        try {
            Class<?> type = classLoader.loadClass(className);
            XposedBridge.hookAllConstructors(type, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    boolean becameReady = false;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            becameReady |= SpotifyHeaderStore.ingestPairs(arg);
                        }
                    }
                    becameReady |= captureStringArrayFields(param.thisObject);
                    if (becameReady) onHeadersReady();
                }
            });
            log("header container hook=" + className);
            return 1;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int hookAddHeader(ClassLoader classLoader, String className) {
        try {
            Class<?> type = classLoader.loadClass(className);
            int count = 0;
            for (Method method : type.getDeclaredMethods()) {
                if (!"addHeader".equals(method.getName()) || method.getParameterCount() != 2) continue;
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters[0] != String.class || parameters[1] != String.class) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String name = param.args[0] instanceof String ? (String) param.args[0] : null;
                        String value = param.args[1] instanceof String ? (String) param.args[1] : null;
                        if (SpotifyHeaderStore.ingest(name, value)) onHeadersReady();
                    }
                });
                count++;
            }
            if (count > 0) log("addHeader hook=" + className + " methods=" + count);
            return count;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean captureStringArrayFields(Object object) {
        if (object == null) return false;
        boolean becameReady = false;
        Class<?> type = object.getClass();
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType() != String[].class) continue;
            try {
                field.setAccessible(true);
                becameReady |= SpotifyHeaderStore.ingestPairs(field.get(object));
            } catch (Throwable ignored) {
            }
        }
        return becameReady;
    }

    private static void onHeadersReady() {
        missingHeadersLogged = false;
        log("Spotify auth headers ready keys=" + SpotifyHeaderStore.capturedKeys());
        scheduleCurrentFetch("headers-ready");
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
                markCommitted(track.key(), observation.generation);
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
                markCommitted(track.key(), observation.generation);
                log("cached official lyricInfo attached to host metadata: " + shortId(track.mediaId)
                        + " parcel=" + parcelBytes);
            }
            return;
        }

        if (!track.hasQueryIdentity()) {
            log("metadata incomplete: " + track.debugSummary());
            return;
        }

        scheduleFetch(track.key(), observation.generation, "metadata");
    }

    private static Observation observe(StockLyricInfo.TrackSnapshot incoming) {
        synchronized (STATE_LOCK) {
            boolean incomingHasId = incoming.hasSpotifyTrackId();
            if (incomingHasId && !incoming.mediaId.equals(currentTrackKey)) {
                currentTrackKey = incoming.mediaId;
                currentTrack = incoming;
                committedTrackKey = "";
                committedGeneration = -1L;
                missingHeadersLogged = false;
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

    private static void scheduleCurrentFetch(String reason) {
        StockLyricInfo.TrackSnapshot track;
        String trackKey;
        long generation;
        synchronized (STATE_LOCK) {
            track = currentTrack;
            trackKey = currentTrackKey;
            generation = GENERATION.get();
        }
        if (track == null || trackKey.isBlank() || !track.hasQueryIdentity()) return;
        scheduleFetch(trackKey, generation, reason);
    }

    private static void scheduleFetch(String trackKey, long generation, String reason) {
        if (!SpotifyHeaderStore.isReady()) {
            if (!missingHeadersLogged) {
                missingHeadersLogged = true;
                log("Spotify Color Lyrics waiting for auth headers keys="
                        + SpotifyHeaderStore.capturedKeys());
            }
            return;
        }
        if (!IN_FLIGHT.add(trackKey)) return;

        EXECUTOR.execute(() -> {
            try {
                StockLyricInfo.TrackSnapshot track = snapshotCurrent(trackKey, generation);
                if (track == null || CACHE.containsKey(trackKey)) return;

                SpotifyColorLyricsClient.Result result = SpotifyColorLyricsClient.fetch(
                        track, SpotifyHeaderStore.snapshot());
                log("Spotify Color Lyrics outcome=" + result.outcome
                        + " syncType=" + result.syncType
                        + " source=" + result.source
                        + " reason=" + reason
                        + " track=" + shortId(track.mediaId));

                if ("http-401".equals(result.outcome)) {
                    SpotifyHeaderStore.invalidateAuthorization();
                    missingHeadersLogged = false;
                    return;
                }
                if (!result.isSuccess()) return;

                String payload = StockLyricInfo.build(
                        track,
                        result.lineLyric,
                        result.rawLyric,
                        generation,
                        result.syncType,
                        result.source);
                if (payload == null || !isCurrent(trackKey, generation)) return;
                CACHE.put(trackKey, payload);
                publish(trackKey, generation, payload, "spotify-color-lyrics");
            } catch (Throwable error) {
                log("Spotify Color Lyrics fetch failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                IN_FLIGHT.remove(trackKey);
            }
        });
    }

    private static void publishCachedOnceIfReady(MediaSession session, String reason) {
        if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
        SpotifySessionRegistry.Selection selection = REGISTRY.selectionFor(session);
        if (selection == null || !selection.track.hasSpotifyTrackId()) return;
        if (!selection.active || !SpotifySessionRegistry.isPlaybackStateValid(selection.playbackState)) return;

        String trackKey = selection.track.key();
        long generation = GENERATION.get();
        if (!isCurrent(trackKey, generation) || isCommitted(trackKey, generation)) return;

        String payload = CACHE.get(trackKey);
        if (payload == null) return;
        publishToSelection(trackKey, generation, payload, selection, reason);
    }

    private static StockLyricInfo.TrackSnapshot snapshotCurrent(String trackKey, long generation) {
        synchronized (STATE_LOCK) {
            if (!isCurrent(trackKey, generation)) return null;
            return currentTrack;
        }
    }

    private static void publish(String trackKey, long generation, String payload, String reason) {
        if (!isCurrent(trackKey, generation) || isCommitted(trackKey, generation)) return;
        SpotifySessionRegistry.Selection selection = REGISTRY.select(trackKey);
        if (selection == null) {
            log("publish pending; no unique main session: " + REGISTRY.describe());
            return;
        }
        publishToSelection(trackKey, generation, payload, selection, reason);
    }

    private static void publishToSelection(String trackKey, long generation, String payload,
                                           SpotifySessionRegistry.Selection selection, String reason) {
        if (!isCurrent(trackKey, generation) || isCommitted(trackKey, generation)) return;
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
            markCommitted(trackKey, generation);
            log("official Spotify lyricInfo committed once: " + shortId(trackKey)
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

    private static boolean isCommitted(String trackKey, long generation) {
        return generation == committedGeneration && trackKey.equals(committedTrackKey);
    }

    private static void markCommitted(String trackKey, long generation) {
        committedTrackKey = trackKey;
        committedGeneration = generation;
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
