# Metrolist+ notices

Metrolist+ is a personal fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist)
(GPL-3.0). This branch is based on the v14 Kotlin Multiplatform rewrite, at
`f54887bfc` on upstream `main`. The pre-v14 fork is kept under the
`plus-legacy` tag.

The Android app uses its own application ID (`com.metrolist.music.plus`) so it
installs next to upstream Metrolist, and the launcher icon from Metrolist
Desktop.

## What this fork adds

- "Don't translate" languages for AI lyrics translation, shared by every
  platform.
- Floating lyrics: an overlay window on Android and an always-on-top window on
  desktop.

## Code adapted from Metrolist Desktop and Metrolist+

Source: `~/projects/_sandbox/metrolist-desktop` (GPL-3.0) at `bda5f1f`, and the
pre-v14 Metrolist+ at `plus-legacy`. Metrolist Desktop in turn adapts
[lyrics-float](https://github.com/uzuki-P/lyrics-float) (GPL-3.0) and
Metrolist.

- Launcher icon from Metrolist Desktop's `assets/icon/metrolist-desktop.svg`.
- Desktop floating lyrics window helpers in
  `desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/floating/`:
  `FloatingLyricsWindow.kt` (from `floating/FloatingLyricsWindow.kt`),
  `FloatingLyricsChrome.kt` (from `floating/WindowChrome.kt`),
  `OverlayWindowSupport.kt` (from `platform/OverlayWindowSupport.kt`),
  `LinuxOverlayWindowSupport.kt` (from `platform/linux/LinuxOverlayWindowSupport.kt`,
  `LinuxClickThrough.kt` and the skip-taskbar part of `LinuxWindowMover.kt`) and
  `WindowsOverlayWindowSupport.kt` (from
  `platform/windows/WindowsOverlayWindowSupport.kt`).
