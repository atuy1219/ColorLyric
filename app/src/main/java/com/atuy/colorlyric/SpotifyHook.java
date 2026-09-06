package com.atuy.colorlyric;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class SpotifyHook implements IXposedHookLoadPackage {
    private static final String TAG = "ColorLyric";
    private static final String SPOTIFY = "com.spotify.music";
    private static final String KEY_LYRIC_INFO = "lyricInfo";
    private static final String KEY_RATING_URI = "ratingUri";
    private static final String OPLUS_API_PROP = "ro.build.version.oplus.api";

    private static final ThreadLocal<Boolean> MODULE_WRITE =
            ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final AtomicBoolean HOOK_LOGGED = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!SPOTIFY.equals(lpparam.packageName) || !SPOTIFY.equals(lpparam.processName)) {
            return;
        }

        log("Loaded in Spotify process; SystemUI scope is not used");
        log("OPlus API=" + readOplusApi());

        XposedBridge.hookAllMethods(MediaSession.class, "setMetadata", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
                if (param.args.length == 0 || !(param.args[0] instanceof MediaMetadata)) return;

                MediaMetadata incoming = (MediaMetadata) param.args[0];
                MediaMetadata normalized = normalizeIfLyricReady(incoming);
                if (normalized != incoming) {
                    param.args[0] = normalized;
                    log("Normalized incoming Spotify metadata: lyricInfo + QQ/OPlus compatibility fields");
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(MODULE_WRITE.get())) return;
                if (!(param.thisObject instanceof MediaSession)) return;

                MediaSession session = (MediaSession) param.thisObject;
                MediaMetadata live;
                try {
                    live = session.getController().getMetadata();
                } catch (Throwable ignored) {
                    return;
                }
                MediaMetadata normalized = normalizeIfLyricReady(live);
                if (normalized == live) return;

                try {
                    MODULE_WRITE.set(Boolean.TRUE);
                    session.setMetadata(normalized);
                    log("Replayed Spotify MediaSession metadata after lyricInfo became available");
                } catch (Throwable t) {
                    log("Metadata replay failed: " + t);
                } finally {
                    MODULE_WRITE.set(Boolean.FALSE);
                }
            }
        });

        if (HOOK_LOGGED.compareAndSet(false, true)) {
            log("Hooked android.media.session.MediaSession#setMetadata in Spotify only");
        }
    }

    private static MediaMetadata normalizeIfLyricReady(MediaMetadata metadata) {
        if (metadata == null) return metadata;
        String lyricInfo;
        try {
            lyricInfo = metadata.getString(KEY_LYRIC_INFO);
        } catch (Throwable ignored) {
            return metadata;
        }
        if (TextUtils.isEmpty(lyricInfo)) return metadata;

        String normalizedLyricInfo = normalizeLyricInfo(lyricInfo);
        String ratingUri = safeGetString(metadata, KEY_RATING_URI);
        String qqStyleRatingUri = TextUtils.isEmpty(ratingUri)
                ? buildRatingUri(metadata, normalizedLyricInfo)
                : ratingUri;

        boolean lyricChanged = !lyricInfo.equals(normalizedLyricInfo);
        boolean ratingChanged = TextUtils.isEmpty(ratingUri) && !TextUtils.isEmpty(qqStyleRatingUri);
        if (!lyricChanged && !ratingChanged) return metadata;

        MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata);
        if (lyricChanged) builder.putString(KEY_LYRIC_INFO, normalizedLyricInfo);
        if (ratingChanged) builder.putString(KEY_RATING_URI, qqStyleRatingUri);
        return builder.build();
    }

    private static String normalizeLyricInfo(String source) {
        try {
            JSONObject json = new JSONObject(source);
            if (!json.has("id")) json.put("id", 0);
            if (!json.has("lyricType")) json.put("lyricType", 0);
            if (!json.has("noLyric")) json.put("noLyric", false);
            if (!json.has("transLyric")) {
                String translation = json.optString("translationLyric", "");
                json.put("transLyric", translation);
            }
            if (!json.has("txtLyric")) {
                json.put("txtLyric", json.optString("lyric", ""));
            }
            return json.toString();
        } catch (Throwable ignored) {
            return source;
        }
    }

    private static String buildRatingUri(MediaMetadata metadata, String lyricInfo) {
        try {
            String mediaId = safeGetString(metadata, MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (TextUtils.isEmpty(mediaId)) {
                JSONObject json = new JSONObject(lyricInfo);
                mediaId = json.optString("songId", "");
            }
            if (TextUtils.isEmpty(mediaId)) return "spotify:";
            if (mediaId.startsWith("spotify:")) return mediaId;
            return new Uri.Builder()
                    .scheme("spotify")
                    .authority("track")
                    .appendPath(mediaId)
                    .build()
                    .toString();
        } catch (Throwable ignored) {
            return "spotify:";
        }
    }

    private static String safeGetString(MediaMetadata metadata, String key) {
        try {
            return metadata.getString(key);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int readOplusApi() {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getDeclaredMethod("get", String.class, String.class);
            get.setAccessible(true);
            String value = (String) get.invoke(null, OPLUS_API_PROP, "0");
            return Integer.parseInt(value);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
        android.util.Log.i(TAG, message);
    }
}
