# KILO Roadmap

> **Status Legend**: 🟢 Planned · 🟡 In Progress · ✅ Done · 🔴 Blocked

---

## v3.6 — "Lossless & Smart" (Q1 2026)

| Feature | Status | Details |
|---------|--------|---------|
| **Qobuz/FLAC via QBDX** | 🟢 | Qobuz API integration for lossless streaming; QBDX for DRM-free FLAC download |
| **Daily Mixes / Discovery Weekly** | 🟢 | Auto-generate from `PlayedSongHistory` + Spotify seed tracks |
| **Cross-source Smart Queue** | 🟢 | Unified queue with manual match override (Spotify ↔ YT Music ↔ Local) |
| **Equalizer DSP in Native Mixer** | 🟢 | Parametric EQ (10-band), limiter, compressor in C++ mixer |
| **Gapless playback detection** | 🟢 | Use track metadata + audio analysis for seamless album transitions |
| **Improved Automix transition** | 🟢 | Better intro/outro detection via spectral analysis |

### Technical Debt
- [ ] Migrate remaining `PlayerHolder` logic to `NativeCrossfadeController`
- [ ] Add unit tests for `TrackMatcher` (Jaro-Winkler)
- [ ] Benchmark native mixer CPU/memory on low-end devices
- [ ] Document JNI bridge API

---

## v3.7 — "Local & Social" (Q2 2026)

| Feature | Status | Details |
|---------|--------|---------|
| **Local file scanning** | 🟢 | MediaStore integration; FLAC/MP3/OGG/ALAC support |
| **Last.fm scrobbling** | 🟢 | Now playing + history sync; "Love" track support |
| **Chromecast support** | 🟢 | Cast to speakers/TV via Media3 Cast SDK |
| **Home screen widget** | 🟢 | Glance-based: play/pause, next, artwork, progress |
| **Playlist import/export** | 🟢 | JSON + M3U support; backup to Drive |
| **Sleep timer improvements** | 🟢 | Fade-out + crossfade to silence |

### Platform
- [ ] Wear OS companion app (tiles + complications)
- [ ] Android TV / Google TV layout
- [ ] Tablet / foldable optimized layouts

---

## v3.8 — "Intelligence" (Q3 2026)

| Feature | Status | Details |
|---------|--------|---------|
| **AI-powered recommendations** | 🟢 | On-device ML (TensorFlow Lite) for mood/activity based picks |
| **Smart shuffle** | 🟢 | Avoid same artist/album clustering; respect "don't play" |
| **Mood/activity presets** | 🟢 | Workout, Focus, Chill, Party — auto-generated queues |
| **Listening insights** | 🟢 | Stats: top artists, genres, time of day, discovery rate |

---

## v4.0 — "Multi-Account & Collaboration" (Q4 2026)

| Feature | Status | Details |
|---------|--------|---------|
| **Multi-account Spotify** | 🟢 | Switch accounts in-app; per-account libraries |
| **Playlist collaboration** | 🟢 | Shared queue editing via invite links |
| **Family mixing** | 🟢 | Merge multiple users' libraries for group sessions |
| **Social features** | 🟢 | Friend activity, shared listening sessions |

---

## 🏗 Infrastructure & Quality

| Area | Target |
|------|--------|
| **Test coverage** | >80% domain layer; >60% overall |
| **CI/CD** | GitHub Actions: build, test, sign, release |
| **Performance** | <2s cold start; <100ms crossfade trigger latency |
| **Accessibility** | TalkBack + Switch Access full support |
| **Localization** | EN, ES, FR, DE, JA, KO, ZH (crowdsourced) |

---

## 💡 Ideas & Experiments (Backlog)

- **Visualizer** — Native shader-based waveform/particle visualizer
- **Karaoke mode** — Synced lyrics highlighting + vocal removal (Demucs)
- **DJ mode** — BPM detection, beat grid, manual crossfade curves
- **Podcast support** — RSS + Spotify podcasts + chapters
- **Offline sync** — Smart download (Wi-Fi only, storage budget)
- **Themes** — User-customizable color schemes + dynamic theming
- **Plugin system** — Loadable source providers (SoundCloud, Bandcamp, etc.)

---

## 📅 Release Cadence

| Version | Target | Type |
|---------|--------|------|
| v3.6 | Q1 2026 | Minor (features) |
| v3.7 | Q2 2026 | Minor (features) |
| v3.8 | Q3 2026 | Minor (features) |
| v4.0 | Q4 2026 | Major (breaking) |

> **Note**: Dates are estimates. Educational project — priorities shift based on learning goals.

---

## 🗳 Community Input

Want to influence the roadmap? 
- 📝 [Open a feature request](https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/issues/new?template=feature_request.md)
- 💬 [Join discussions](https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/discussions)
- 🐛 [Report bugs](https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/issues/new?template=bug_report.md)

---

<div align="center">

**KILO — Building the music player we want to use.**

</div>