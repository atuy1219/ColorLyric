# ColorLyric

ColorLyric is an Xposed module for Spotify lyrics on ColorOS/OxygenOS.

## Scope

- `com.spotify.music`
- `com.android.systemui`

The SystemUI hook is intentionally minimal. It does **not** modify lyric parsing, rendering,
`LyricsRecyclerView`, notifications, media artwork or the stock immersive lyric UI.

## Data flow

```text
Spotify playback process
        ↓
Spotify auth header capture
        ↓
Spotify Color Lyrics API
        ↓
LINE_SYNCED / SYLLABLE_SYNCED decoder
        ↓
Spotify MediaSession / MediaMetadata["lyricInfo"]
        ↓
ColorOS / OxygenOS SystemUI
        ↓
stock Live Alert / lock-screen lyric UI
```

LRCLIB is not used.

## Lock-screen policy

On the tested OPlus ROM, Spotify lyrics already reach the stock `LyricsRecyclerView`, but OPlus
returns a disabled lyric package policy for `com.spotify.music` while QQ Music is enabled.

ColorLyric therefore hooks only the OPlus package-policy methods:

- `getLyricEntrance(String)`
- `getLyricEnable(String)` when present

For Spotify lookups only, the package argument is evaluated as `com.tencent.qqmusic`. No numeric
policy value such as `52` is hard-coded; the ROM's current QQ Music value is reused.

The implementation first tries the known OPlus media action selector class. If the class name differs,
it temporarily watches OPlus media class loading and hooks a class exposing the exact policy method
shape. The watcher is removed after both policy methods are found.

## Spotify lyric source

ColorLyric captures the headers needed by Spotify's Color Lyrics request path:

- `authorization`
- `client-token`
- `user-agent`
- `x-client-id`

Header values are never written to ColorLyric logs.

The current `spotify:track:` ID is requested from Spotify Color Lyrics. This keeps lyric timing aligned
with Spotify playback instead of a third-party LRC database.

Supported timing:

- `LINE_SYNCED`
- `SYLLABLE_SYNCED`

## Installation

1. Install ColorLyric.
2. In LSPosed, enable both `com.spotify.music` and `com.android.systemui`.
3. Disable the separate Spotify Lyric Provider when testing ColorLyric standalone.
4. Reboot the device.
5. Start Spotify and play a normal `spotify:track:` item.

Debug:

```sh
adb logcat -s ColorLyric
```

Expected SystemUI lines include:

```text
SystemUI minimal lyric-policy hook loading
hooked ...#getLyricEntrance
hooked ...#getLyricEnable
policy alias getLyricEntrance: Spotify -> QQ Music
policy alias getLyricEnable: Spotify -> QQ Music
```

Expected Spotify lines include:

```text
Spotify auth headers ready keys=authorization,client-token,user-agent,x-client-id
Spotify Color Lyrics outcome=ok syncType=LINE_SYNCED ...
official Spotify lyricInfo committed once: ........
```

## Research notes

QQ Music's stock OPlus metadata behavior and ColorOS Live Lyrics Bridge / Providers were consulted for
compatibility research. ColorLyric remains GPL-3.0-only.

## License

GNU General Public License v3.0 only (`GPL-3.0-only`).
