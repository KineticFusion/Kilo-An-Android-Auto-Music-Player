# Kilo 2.0.0

## What's new

- Added setup-time personalization for genres, global and regional artists, moods, discovery level, audio preference, and Android Auto.
- Added a Settings editor for changing the listener profile later.
- Updated Home and Android Auto discovery to use listening-context searches for moods and genres, with exact mood/genre title matches filtered out.
- Improved catalog discovery and limited familiar-song fallback to completed catalog scans.
- Applied audio-profile curves directly to the active playback session and kept the equalizer attached during video playback. The System Equalizer action falls back to KILO's equalizer when no separate system panel is available.
- Setup now resumes if closed before completion and stays complete on later launches. The first-update migration runs once and preserves downloaded audio files.

## Possible issue

- On some Android Auto devices, a track may briefly show “Couldn't load” or “Source error” before playback starts after retry. This may still occur on some sessions; please report the device and Android Auto version if it does.

## APK

`Kilo-2.0.0.apk` is the Android Studio debug build.
