# ColorLyric 0.5 - Spotify Hook

ColorLyric is an experimental Xposed module for ColorOS/OxygenOS lock-screen lyrics.

This branch **does not hook `com.android.systemui`**. It hooks only Spotify (`com.spotify.music`) and augments Spotify's own MediaSession metadata to more closely match the ColorOS/OPlus metadata emitted by QQ Music.

## Why this version exists

QQ Music's ColorOS path was reverse-engineered from an official QQ Music APK. On OPlus API 37+ its `addDataForOplusSeedling` path adds two relevant metadata entries to the player's MediaSession:

- `lyricInfo`
- `ratingUri`

QQ Music's `action_lyric` PlaybackState custom action is only emitted on the HyperOS branch, so ColorLyric intentionally does **not** add that action on ColorOS/OxygenOS.

## Current experiment

For now, keep **Spotify Lyric Provider** enabled for Spotify. It supplies the timed `lyricInfo`. ColorLyric then, inside the Spotify process:

1. observes Spotify `MediaSession#setMetadata`;
2. waits until `lyricInfo` is present;
3. normalizes several QQ-compatible fields (`id`, `lyricType`, `noLyric`, `transLyric`, `txtLyric`);
4. adds `ratingUri` when Spotify does not already provide it;
5. republishes the metadata through the same Spotify-owned MediaSession when necessary.

This deliberately isolates the remaining native-UI compatibility difference before duplicating the provider's lyric-fetching stack.

## LSPosed scope

Select **only**:

```text
com.spotify.music
```

Do not select SystemUI.

After changing the scope, force-stop Spotify or reboot.

## Debug

```sh
adb logcat -s ColorLyric
```

Expected messages:

```text
Loaded in Spotify process; SystemUI scope is not used
Hooked android.media.session.MediaSession#setMetadata in Spotify only
Normalized incoming Spotify metadata: lyricInfo + QQ/OPlus compatibility fields
Replayed Spotify MediaSession metadata after lyricInfo became available
```

## Credits

The Spotify MediaSession/`lyricInfo` architecture was studied using `io.github.andrealtb.coloroslyrics.provider.spotify` from **ColorOS Live Lyrics Providers** by Andrea-lyz/Andrea-TB. ColorLyric's implementation in this repository is independent and focused on the QQ Music/OPlus metadata delta rather than copying the Provider implementation.

## License

GNU General Public License v3.0 (`GPL-3.0-only`).
