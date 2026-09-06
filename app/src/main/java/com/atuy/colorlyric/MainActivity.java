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
                "ColorLyric 0.4 - Native Unlocker\n\n" +
                "LSPosed scope: com.android.systemui\n\n" +
                "This build does not draw lyrics. It unlocks the stock OPlus lyric entrance " +
                "for Spotify and lets SystemUI render its native lock-screen lyric page.\n\n" +
                "Required: Spotify lyricInfo Provider.\n\n" +
                "After enabling the module, restart SystemUI or reboot.\n\n" +
                "Debug: adb logcat -s ColorLyric"
        );
        setContentView(text);
    }
}
