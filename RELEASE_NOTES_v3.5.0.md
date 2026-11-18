# KILO v3.5.0 — "Unified Audio Engine" Release Notes

## 🎯 Major Highlights

This is a **foundational release** that rewrites the audio pipeline with a native C++ crossfade engine, adds true native Spotify browsing (Yuma-style), implements source mode selection, and fixes Spotify authentication/playlist loading.

---

## 🎵 Native Crossfade Engine (BitChord-Style)

**Complete rewrite of crossfade using native C++ via NDK/JNI with Oboe 1.9.3**

| Feature | Implementation |
|---------|----------------|
| **Equal-Power Curve** | `gainA = cos(t × π/2)`, `gainB = sin(t × π/2)` — constant loudness during blend |
| **Configurable Duration** | 0–12s (Off, 1s, 2s, 3s, 5s, 8s, 10s, 12s) via settings |
| **Manual Skip Crossfade** | Applies to Next/Previous track buttons, not just auto-transitions |
| **Automix EQ Sweep** | High-pass filter sweep on outgoing track + low-pass on incoming |
| **Soft Clipping** | `tanh()` preventing digital clipping at blend peaks |
| **Lock-Free Audio** | SPSC ring buffers with atomic indices, zero allocations in hot path |
| **16KB Page Aligned** | `-Wl,-z,max-page-size=16384` + Oboe 1.9.3 for Android 15+ |

**Files:** `app/src/main/cpp/native_mixer.cpp`, `jni_bridge.cpp`, `ring_buffer.h`, `biquad_filter.h`, `NativeCrossfadeController.kt`, `CrossfadeOrchestrator.kt`, `PlayerHolder.kt`

---

## 🎧 Source Mode Selection

Choose your music source globally from Settings → Music Discovery:

| Mode | Behavior |
|------|----------|
| **Both (Hybrid)** | Spotify metadata + YouTube Music audio (default) |
| **Spotify Only** | Spotify catalog only — requires Premium for playback |
| **YouTube Music Only** | YouTube Music catalog only |

Persisted in `SharedPreferences`, filtered at `HybridSourceRegistry.getOrderedActiveProviders()` level.

---

## 🎵 Native Spotify Browsing (Yuma-Style)

Full Spotify catalog browsing **inside the app** — no external browser needed:

### Home Screen Sections
- **New Releases** — Latest albums/tracks from Spotify's new releases endpoint
- **Featured Playlists** — Curated editorial playlists
- **Browse Categories** — Genres & Moods grid (Pop, Rock, Hip-Hop, Electronic, etc.)
- **Category Playlists** — Tap a category → see its playlists → tap to expand tracks

### Library Screen
- **Your Spotify Playlists** — Full list with track counts, covers, expand-to-view-tracks

### Implementation
- `SpotifyApiClient`: New endpoints (`getNewReleases`, `getFeaturedPlaylists`, `getCategories`, `getCategoryPlaylists`, `getArtistAlbums`, `getArtistTopTracks`)
- `SpotifySourceProvider`: `fetchNewReleases()`, `fetchFeaturedPlaylists()`, `fetchCategories()`, `fetchCategoryPlaylists()`
- `MainViewModel`: `loadSpotifyNewReleases()`, `loadSpotifyFeaturedPlaylists()`, `loadSpotifyCategories()`, `loadSpotifyCategoryPlaylists()`, `clearSpotifyCategory()`

---

## 🔐 Spotify Authentication & Token Refresh Fixed

**Root cause:** Cached access tokens expired silently, causing blank playlist loads.

**Solution:** Added `HybridSourceRegistry.ensureValidSpotifyWebToken(context)` that:
- Checks token expiry with 15s buffer
- Auto-refreshes via `https://open.spotify.com/get_access_token` using stored `sp_dc` cookie
- Persists new token + expiry back to encrypted SharedPreferences
- All Spotify API calls (`getUserPlaylists`, `getPlaylistTracks`, `getTrack`, `getAlbum`, `getArtist`, `getNewReleases`, `getFeaturedPlaylists`, `getCategories`, `getCategoryPlaylists`, `getArtistAlbums`, `getArtistTopTracks`) now use this method

---

## 🌍 Lyrics Translation (Any Language → English)

- **MyMemory Translation API** (free, no key required)
- Toggle in Settings → Lyrics & Karaoke → "Lyrics translation"
- Automatically detects source language, translates to English
- Cached locally for offline viewing

---

## 📱 16KB ELF Page Alignment (Android 15+ Ready)

| Component | Version/Flag |
|-----------|--------------|
| NDK | r27 (`27.0.12077973`) |
| Linker Flag | `-Wl,-z,max-page-size=16384` (in both `build.gradle.kts` and `CMakeLists.txt`) |
| Packaging | `packaging.jniLibs.useLegacyPackaging = false` (modern zip-aligned) |
| Oboe | 1.9.3 (built-in 16KB support) |
| AGP | 9.4.1 |

---

## ⚙️ Crossfade Duration Setting Fixed

- Added `crossfadeDurationMs` to `AppStateModel` (persisted)
- New `setCrossfadeDuration(durationMs)` in `MainViewModel`:
  - Updates SharedPreferences
  - Updates UI state reactively
  - Propagates to native engine via `PlayerHolder.updateCrossfadeConfig()` if active
- Settings UI reads from `state.crossfadeDurationMs` (reactive, not stale local value)
- Preference listener keeps UI in sync across app restarts

---

## 🎨 UI/UX Improvements

- **Animated category grid** on Home (LazyVerticalGrid with `SpotifyCategoriesGrid` composable)
- **Smooth tab/category switching** transitions
- **Spotify green accent** theming for native sections
- **Expand/collapse playlists** with animated track list
- **Source mode dropdown** in Settings with live filtering

---

## 🛠 Technical Changes

| Area | Change |
|------|--------|
| **Audio Pipeline** | Native C++ mixer replaces dual-ExoPlayer approach |
| **NDK** | Upgraded to r27 with C++20, `-O3 -ffast-math` |
| **Oboe** | 1.9.3 via Prefab (`find_package(oboe REQUIRED CONFIG)`) |
| **Media3** | 1.6.0 with `DynamicForwardingPlayer` wrapper |
| **Kotlin** | 2.2.10, Compose BOM 2026.02.01 |
| **AGP** | 9.4.1, compileSdk 37, targetSdk 37 |
| **Architecture** | `HybridSourceRegistry` as central provider registry with source mode filtering |

---

## 🐛 Bug Fixes

- ✅ Spotify login WebView blank screen → Fixed cookie capture + in-app WebView
- ✅ Spotify playlists not loading → Fixed token auto-refresh
- ✅ Crossfade duration setting not persisting → Added state field + preference listener
- ✅ 16KB page alignment warnings → NDK r27 + linker flags + modern packaging
- ✅ LazyListScope items receiver error → Extracted `SpotifyCategoriesGrid` composable
- ✅ Video preview black screen → Removed per user request
- ✅ Manual skip crossfade not working → Integrated into `BackgroundPlaybackCoordinator.next()/previous()`

---

## 📦 APK

- **File:** `Kilo-3.5.0.apk` (34 MB)
- **Architectures:** arm64-v8a, armeabi-v7a
- **Min SDK:** 24 (Android 7.0)
- **Target SDK:** 37 (Android 15)

---

## 🔄 Migration Notes

- **Existing users:** Spotify login preserved (cookie + token in encrypted prefs)
- **Crossfade settings:** Migrated from old `harmony_crossfade_duration_ms` key
- **Source mode:** Defaults to "Both" — change in Settings if desired
- **No database migrations required**

---

## 🙏 Credits

- **BitChord** — Crossfade algorithm reference (equal-power curve, automix EQ)
- **Yuma Player** — Native Spotify browsing UX inspiration
- **Oboe Team** — Low-latency audio library with 16KB support
- **MyMemory API** — Free translation service

---

**Full changelog:** See git history for commit-level details.