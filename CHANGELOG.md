# KILO 2.8.0

## What's new

- Removed the experimental Spotify catalog browsing and sign-in screens.
- Added regular public YouTube playlist search alongside YouTube Music community playlists and the connected user's saved playlists; selecting a playlist loads its tracks for playback.
- Added a synced current-line strip to the full-screen player; tapping it opens the existing full lyrics tab. Android Auto receives the active line as session subtitle metadata.
- Added a dedicated Artists destination, put followed artists first, and made moods, genres, and languages open their own matching song pages in Browse.
- Added optional DJ mode. It uses KILO's existing AutoMix transitions and can order upcoming tracks when saved BPM, key, and energy analysis is available. Automatic track analysis is not currently active, so unscored tracks keep their existing order.
- Added Search song recognition through AudD. The app shows a one-time disclosure before the Android microphone permission prompt, requests recording only after the user starts recognition, and encrypts the user-provided API token with Android Keystore.
- Added Drive Recap for Android Auto sessions. Naturally completed songs are collected, skipped tracks are omitted, and the listener can name and save the recap as a Library playlist.
- Added “Not for this mood” feedback to mood song pages. The choice is saved for that mood only and does not hide the song from other moods or globally.
- Kept the original Media3 player, media session, Android Auto browse and playback, queue, downloads, and source resolver as the active playback architecture.

---

# KILO 2.7.2

## What's new

- Restored personal Home category filters, with Quick Picks and Recently Played available alongside the selected shelf.
- Renamed the two discovery shelves to “Spotted, Not Started” and “First Encounters” so previously surfaced music is distinct from new finds.
- Mood and genre cards in Browse open dedicated matching song lists instead of routing through Search filters.
- Android Auto Home now includes the same personal discovery shelves before Quick Picks, and Library is focused on liked songs, playlists, and followed artists.
- Added a single Set My Mood action and kept Trip Builder near the top of Home. Radio is now a bottom navigation tab.
- Removed the automatic sign-in popup; sign-in is available from You, while Offline Vault remains accessible.
- Improved playback recovery so normal track transitions aren’t mistaken for early stream endings, and failed streams resume automatically when playback was active.
- Added a one-time update note for the Home and category navigation changes. It appears after setup and stays dismissed after acknowledgment.

---

# KILO 2.7.1 Hotfix

## What's fixed

- Fixed the Appearance navigation so Themes no longer opens the legacy settings destination.
- Grouped Themes and Player Styles together under Appearance, with working back navigation.

---

# KILO 2.5.5 Hotfix

## What's fixed

- Fixed update notices remaining after the downloaded version was installed.
- Update checks run in the background and show the same status on Home and in Settings.
- Downloading an update now opens Android's installer automatically after verification.
- Returning from Android's install permission settings resumes the installation flow.

---

# KILO 2.5.1

## What's new

- Reduced first-run setup to three steps: welcome, optional YouTube Music sign-in, and start listening.
- Deferred personalization, themes, audio preferences, downloads, and device music access until users choose to configure or use them.
- Preserved playback intent through audio-focus interruptions so stream-error recovery can resume automatically.
- Kept the playback Wi-Fi lock active during temporary buffering to help prevent background network stalls from interrupting a song.
- Kept the existing playback engine, stream cache, startup diagnostics, retry recovery, and Android Auto browse architecture.
- Limited the loading indicator to an initial track load or recovery; it no longer reappears for ordinary buffering during active playback.
- Updated the launcher icon with KILO's simplified gradient K mark.

---

# KILO 2.1.0

## What's new

- Added local listener profiles that can share one YouTube Music login while keeping each profile's genres, moods, followed artists, and discovery/audio preferences separate.
- Added profile creation and switching, with a fresh personalization flow for each new profile.
- Fixed personalization reset so it clears the active profile and opens the setup flow to choose new preferences.
- Added a confirmed Reset all app data action that clears Kilo's app data and returns to first-run setup.
- Migrated existing single-profile personalization into the active profile.
- Limited each profile to one selected mood and one selected genre, and made both Home discovery shelves follow the selected mood or genre.
- Added 25 individually previewed Android Auto browse layouts and a custom player accent with HEX color entry.
- Added XML-only profile preset import/export through the Android file picker, with a user-chosen save folder.
- Added follow controls to artist search, more curated setup artists, and like/unlike controls beside songs and in their options menu.

## Install

Download `Kilo-2.1.0.apk` from the release assets and install it on your Android device. Android may ask you to allow installs from the app you use to open it.

---

# KILO 1.5.5

## What's new

- Reworked stream resolution, caching, and recovery to improve playback and seeking.
- Added lyric provider fallback and settings to change provider priority.
- Added community playlist search.
- Added region-aware music discovery (automatic device region with manual region selection) and dedicated artist pages with album and song catalogs.
- Improved radio compatibility with HLS stations and added stream retry handling.
- Added an in-app equalizer using the audio bands and presets supported by the device.
- Added a Kilo V2 roadmap and a GitHub release checker with links to the release APK.
- Improved guest discovery relevance and metadata filtering, Android Auto, downloads, and playback stability.

## Install

Download `Kilo-1.5.5.apk` from the release assets and install it on your Android device. This release asset is a debug APK. Android may ask you to allow installs from the app you use to open it.
