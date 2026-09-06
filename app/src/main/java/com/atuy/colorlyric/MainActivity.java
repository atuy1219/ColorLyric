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
                "ColorLyric 0.5.0\n\n" +
                "Spotify-only Xposed module\n" +
                "Scope: com.spotify.music\n" +
                "SystemUI is not hooked.\n\n" +
                "SpotifyのMediaSessionにColorOS純正互換のlyricInfoを発行します。\n" +
                "既存のSpotify Lyric ProviderがlyricInfoを出している場合は、" +
                "QQ Musicと同じstock schemaへ正規化します。\n" +
                "Providerが無い場合はLRCLIBの同期歌詞を使用します。\n\n" +
                "LSPosedでSpotifyだけを作用域にして、Spotifyを強制停止後に起動してください。\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(view);
    }
}
