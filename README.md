# ColorLyric

ColorLyric is a Spotify-only Xposed module for ColorOS/OxygenOS native lyrics.
It hooks `com.spotify.music` only. `com.android.systemui` is not in scope.

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
```

LRCLIB is no longer used.

## Spotify lyric source

ColorLyric captures only the header names required by Spotify's own Color Lyrics request path:

- `authorization`
- `client-token`
- `user-agent`
- `x-client-id`

Header values are never written to ColorLyric logs.

Lyrics are requested from Spotify's Color Lyrics endpoint using the current `spotify:track:` ID.
This keeps the lyric timeline aligned with Spotify's own playback asset instead of relying on a
third-party LRC database.

ColorLyric supports:

- `LINE_SYNCED` line timing;
- `SYLLABLE_SYNCED` word/syllable timing when Spotify supplies syllable spans;
- one publication per track generation;
- replay only when Spotify itself replaces metadata and drops `lyricInfo`;
- non-Cast active MediaSession selection;
- stale-result rejection across rapid track changes.

## ColorOS metadata

The module publishes a standard ColorOS-compatible `lyricInfo` payload containing at least:

```json
{
  "songName": "...",
  "artist": "...",
  "songId": "spotify:track:...",
  "lyricType": 0,
  "lyric": "[00:01.000]...",
  "rawLyric": "[00:01.000]<00:01.000>...",
  "noLyric": false
}
```

For OPlus/QQ Music compatibility, the same MediaMetadata also receives:

- `android.media.metadata.LYRIC`
- `ratingUri` pointing back to the Spotify track
- `transLyric` / `txtLyric` empty compatibility fields

No second MediaSession is created.

## Lock-screen behavior

The player side now publishes all stock lyric data required by ColorOS and also adds the QQ Music
style compatibility metadata above.

Some OPlus SystemUI builds still apply a package-specific lyric-entrance policy. On such builds the
lyrics can already reach the stock `LyricsRecyclerView` while the immersive lock-screen lyric page
remains disabled for `com.spotify.music` (`lyricUiMode=false`). That final package gate lives in
SystemUI and cannot be overridden from a Spotify-only Xposed scope.

ColorLyric itself deliberately does **not** hook `com.android.systemui`. If the ROM blocks the lyric
entrance by package, a SystemUI compatibility layer such as ColorOS Live Lyrics Bridge is required
for that ROM. The Spotify lyric publication remains owned by ColorLyric.

## Installation

1. Install ColorLyric.
2. In LSPosed, enable only `com.spotify.music` for ColorLyric.
3. Do not add `com.android.systemui` to ColorLyric's scope.
4. Disable the separate Spotify Lyric Provider when testing ColorLyric standalone.
5. Force-stop Spotify and start it again.
6. Play a normal `spotify:track:` item.

Debug:

```sh
adb logcat -s ColorLyric
```

Expected bootstrap/fetch sequence:

```text
loaded in Spotify main process; SystemUI not hooked
header container hook=p.ot10
addHeader hook=p.aj81 methods=...
Spotify auth headers ready keys=authorization,client-token,user-agent,x-client-id
track=........ generation=1 ...
Spotify Color Lyrics outcome=ok syncType=LINE_SYNCED ...
official Spotify lyricInfo committed once: ........
```

The exact obfuscated Spotify header classes can change between Spotify versions. The current
implementation also tries the non-obfuscated OkHttp/Cronet class names and logs which hooks were
installed.

## Research notes

The QQ Music Android implementation was inspected to determine the stock OPlus metadata contract.
The public ColorOS Live Lyrics Providers Spotify implementation was consulted for architecture and
compatibility research. ColorLyric's Java hook/client implementation is maintained in this repository.

## License

GNU General Public License v3.0 only (`GPL-3.0-only`).
