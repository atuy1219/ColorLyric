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
                "ColorLyric 1.1.0\n\n" +
                "Scope: com.spotify.music + com.android.systemui\n\n" +
                "Spotify側では公式Color Lyricsを取得してMediaSessionへColorOS互換lyricInfoを発行します。\n" +
                "SystemUI側では歌詞描画やLyricsRecyclerViewには触れず、" +
                "SpotifyのgetLyricEntrance / getLyricEnableだけをQQ Musicと同じポリシーで評価します。\n\n" +
                "LSPosedではSpotifyとシステムUIの2つを作用域にしてください。\n" +
                "適用後は端末再起動を推奨します。\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(view);
    }
}
