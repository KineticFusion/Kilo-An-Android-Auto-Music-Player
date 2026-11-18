# Changelog

All notable changes to KILO are documented here.

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

---

## [3.5.0] — 2026-01-15 — "Unified Audio Engine"

### Added
- **Native Crossfade Engine** — C++/NDK mixer with Oboe 1.9.3
  - Equal-power curves (`cos/sin`) for constant loudness
  - Configurable duration: 0–12s (Off, 1s, 2s, 3s, 5s, 8s, 10s, 12s)
  - Automix EQ sweep: HPF 20Hz→350Hz outgoing + LPF incoming
  - Soft clipping via `tanh()` preventing digital clipping
  - Lock-free SPSC ring buffers with atomic indices
  - 16KB ELF page alignment for Android 15+ (`-Wl,-z,max-page-size=16384`)
- **Source Mode Selection** — Settings → Music Discovery
  - Both (Hybrid): Spotify metadata + YouTube Music audio
  - Spotify Only: Spotify catalog (requires Premium)
  - YouTube Music Only: YT Music catalog
- **Native Spotify Browsing** (Yuma-style)
  - Home: New Releases, Featured Playlists, Categories grid
  - Library: Your Spotify Playlists with expand/collapse
  - Search: Unified Spotify + YT Music results
- **Spotify Authentication Overhaul**
  - `sp_dc` cookie encrypted via Tink AES-256-GCM + Android Keystore
  - Auto token refresh via `open.spotify.com/get_access_token`
  - 15s expiry buffer, persisted to encrypted SharedPreferences
- **GraphQL Persisted Queries with Auto-Recovery**
  - HashProvider: In-memory + SharedPreferences caching
  - HashDiscovery: Scrapes `open.spotify.com` JS bundles
  - HashRefresher: Auto-invalidation/retry on `PersistedQueryNotFound`
- **Lyrics Translation** — Any language → English (MyMemory API, free)
- **16KB Page Alignment** — NDK r27, C++20, modern packaging
- **Crossfade Duration Setting Fix** — Reactive StateFlow, persistent

### Changed
- **Audio Pipeline** — Native C++ mixer replaces dual-ExoPlayer approach
- **NDK** — Upgraded to r27 with C++20, `-O3 -ffast-math`
- **Oboe** — 1.9.3 via Prefab (`find_package(oboe REQUIRED CONFIG)`)
- **Media3** — 1.6.0 with `DynamicForwardingPlayer` wrapper
- **Kotlin** — 2.2.10, Compose BOM 2026.02.01
- **AGP** — 9.4.1, compileSdk 37, targetSdk 37
- **Architecture** — `HybridSourceRegistry` as central provider registry
- **Spotify Module** — Complete rewrite: 20+ files, strict SoC

### Fixed
- Spotify login WebView blank screen → Cookie capture + in-app WebView
- Spotify playlists not loading → Token auto-refresh on all API calls
- Crossfade duration setting not persisting → StateFlow + preference listener
- 16KB page alignment warnings → NDK r27 + linker flags
- LazyListScope items receiver error → Extracted `SpotifyCategoriesGrid`
- Video preview black screen → Removed per user request
- Manual skip crossfade not working → Integrated into `BackgroundPlaybackCoordinator`
- "Fade out but no fade in" bug → Async wait for Deck B buffer >10% before native crossfade

### Removed
- Video preview functionality
- Legacy dual-ExoPlayer crossfade implementation
- `seekToNextWindow`/`seekToPreviousWindow` (Media3 API removed)

### Migration
- Existing users: Spotify login preserved (encrypted cookie + token)
- Crossfade settings: Migrated from `harmony_crossfade_duration_ms`
- Source mode: Defaults to "Both" — change in Settings
- No database migrations required

---

## [3.4.5] — 2025-11-20

### Added
- YouTube Music hybrid playback
- Lyrics display with time-sync
- 10-band equalizer (Media3)
- Android Auto support (Media3 Session)
- Sleep timer with fade-out
- Offline vault for downloaded tracks
- Radio browser with favorites

### Fixed
- Playback resume after phone call
- Queue persistence across restarts
- Memory leak in ImageLoader

---

## [3.4.0] — 2025-09-10

### Added
- Jetpack Compose Material3 UI rewrite
- Mood/genre personalization
- Discovery shelves (YouTube Shorts style)
- Playlist management (create, edit, reorder)
- Artist/album detail screens

### Changed
- Migrated from XML + ViewBinding to Compose
- StateFlow for all UI state
- Hilt → Manual DI

---

## [3.3.0] — 2025-06-15

### Added
- YouTube Music integration
- Basic crossfade (ExoPlayer-based)
- Search across sources
- Dark/light theme

---

## [3.2.0] — 2025-03-01

### Added
- Spotify metadata integration
- Basic playback controls
- Queue management

---

## [3.1.0] — 2024-12-01

### Added
- Initial release
- Local file playback
- Basic UI

---

## Legend
- **Added** for new features
- **Changed** for changes in existing functionality
- **Deprecated** for soon-to-be removed features
- **Removed** for now removed features
- **Fixed** for any bug fixes
- **Security** for vulnerability fixes