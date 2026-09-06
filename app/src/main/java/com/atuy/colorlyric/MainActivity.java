package com.atuy.colorlyric;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView view = new TextView(this);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        view.setPadding(padding, padding, padding, padding);
        view.setTextSize(16);
        view.setText(
                "ColorLyric 1.0.0\n\n" +
                "Spotify-only Xposed module\n" +
                "Scope: com.spotify.music\n" +
                "SystemUI is not hooked.\n\n" +
                "Spotify内部の認証ヘッダーを取得し、Spotify公式Color Lyricsから" +
                "同期歌詞を取得してMediaSessionへColorOS互換lyricInfoを発行します。\n" +
                "LINE_SYNCED / SYLLABLE_SYNCEDに対応します。\n\n" +
                "ロック画面互換のため、lyricInfoに加えてandroid.media.metadata.LYRICと" +
                "OPlus向けratingUriも同じMediaSessionへ追加します。\n\n" +
                "LSPosedでSpotifyだけを作用域にして、Spotifyを強制停止後に起動してください。\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(view);
    }
}
