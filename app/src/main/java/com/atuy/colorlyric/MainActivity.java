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
                "ColorLyric 1.2.0\n\n" +
                "libxposed API 102\n" +
                "Scope: com.spotify.music + com.android.systemui\n\n" +
                "Spotify側では公式Color Lyricsを取得し、ColorOS互換lyricInfoを" +
                "Spotify自身のMediaSessionへ発行します。\n" +
                "SystemUI側では歌詞描画には触れず、SpotifyのgetLyricEntrance / " +
                "getLyricEnableだけをQQ Musicと同じポリシーで評価します。\n\n" +
                "曲切替時の古いHTTP取得は中断され、歌詞なし/404/403/429には" +
                "短期キャッシュを適用します。\n\n" +
                "LSPosedでSpotifyとシステムUIの2つを作用域にしてください。\n" +
                "適用後は端末再起動を推奨します。\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(view);
    }
}
