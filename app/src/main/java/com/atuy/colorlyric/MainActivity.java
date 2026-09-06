package com.atuy.colorlyric;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        int p = Math.round(24 * getResources().getDisplayMetrics().density);
        text.setPadding(p, p, p, p);
        text.setTextSize(16);
        text.setText(
                "ColorLyric 0.5 - Spotify Hook\n\n" +
                "LSPosed scope: com.spotify.music only\n\n" +
                "SystemUI is not hooked. This build augments Spotify's own MediaSession metadata " +
                "with the ColorOS/OPlus fields used by QQ Music.\n\n" +
                "For this experimental build, keep Spotify Lyric Provider enabled so lyricInfo is " +
                "available. ColorLyric normalizes that payload and adds the QQ-style OPlus ratingUri.\n\n" +
                "After changing scope, force-stop Spotify or reboot.\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(text);
    }
}
