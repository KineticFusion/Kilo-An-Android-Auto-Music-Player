# Contributing to KILO

Thank you for your interest in contributing! KILO is an educational project exploring modern Android music player architecture.

## Quick Links
- [Bug Report](.github/ISSUE_TEMPLATE/bug_report.md)
- [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md)
- [Pull Request Template](.github/PULL_REQUEST_TEMPLATE.md)
- [Roadmap](ROADMAP.md)

## Getting Started

### Prerequisites
- Android Studio **Ladybug | 2024.2.1+**
- JDK **21** (required for AGP 9.4+)
- NDK **r27** (27.0.12077973)
- Android SDK **37** (Android 15)

### Setup
```bash
# 1. Fork the repository
git clone https://github.com/YOUR_USERNAME/Kilo-An-Android-Auto-Music-Player.git
cd Kilo-An-Android-Auto-Music-Player

# 2. Add upstream remote
git remote add upstream https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player.git

# 3. Create feature branch
git checkout -b feature/your-feature-name

# 4. Open in Android Studio and build
./gradlew assembleDebug
```

## Project Structure

```
Kilo/
├── app/                          # Main app module
│   ├── src/main/
│   │   ├── java/com/example/kilo/
│   │   │   ├── playback/         # Audio playback (crossfade, Media3)
│   │   │   ├── spotify/          # Spotify integration (modular)
│   │   │   │   ├── auth/         # Authentication & TOTP
│   │   │   │   ├── net/          # GraphQL/REST clients
│   │   │   │   ├── models/       # DTOs & domain models
│   │   │   │   ├── parser/       # JSON parsing
│   │   │   │   ├── mapping/      # DTO → Domain mapping
│   │   │   │   └── matching/     # Track matching (Jaro-Winkler)
│   │   │   ├── streaming/        # YouTube Music / local sources
│   │   │   ├── ui/               # Compose screens
│   │   │   └── viewmodel/        # MVVM state
│   │   ├── cpp/                  # Native C++ mixer (Oboe)
│   │   └── res/                  # Resources
├── core-streaming/               # Shared streaming logic
├── docs/                         # GitHub Pages site
├── .github/                      # CI, issue templates, dependabot
└── ROADMAP.md                    # Feature roadmap
```

## Development Guidelines

### Code Style
- **Kotlin**: Follow [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- **Compose**: Use Material3 components, avoid deprecated APIs
- **Architecture**: MVVM + Clean Architecture, CQRS-lite for Spotify
- **State**: `StateFlow` for reactive UI, avoid `LiveData`
- **DI**: Manual dependency injection (no Dagger/Hilt)

### Commits
```
type(scope): description

[optional body]

[optional footer]
```

Types: `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `chore`, `build`, `ci`

Scopes: `playback`, `spotify`, `ui`, `streaming`, `native`, `deps`

Examples:
```
feat(spotify): add native playlist browsing
fix(playback): resolve crossfade race condition
refactor(native): optimize ring buffer allocation
```

### Pull Requests
1. Keep PRs focused — one feature/fix per PR
2. Write clear descriptions with context
3. Include screenshots for UI changes
4. Ensure CI passes (lint, tests, build)
5. Update documentation if needed

### Testing
```bash
# Unit tests
./gradlew testDebugUnitTest

# Lint
./gradlew lintDebug

# Full build
./gradlew assembleDebug
```

## Areas for Contribution

### 🟢 Good First Issues
- Add missing translations (strings.xml)
- Improve accessibility (content descriptions, TalkBack)
- Add unit tests for `TrackMatcher`, `SpotifyJsonParser`
- Document public APIs with KDoc

### 🟡 Medium Complexity
- Implement local file scanning (MediaStore)
- Add Last.fm scrobbling
- Create home screen widget (Glance)
- Improve Automix transition detection

### 🔴 Advanced
- Native DSP in C++ mixer (EQ, compressor, limiter)
- Qobuz/FLAC integration via QBDX
- Wear OS companion app
- AI-powered recommendations (TensorFlow Lite)

## Native Development (C++)

The crossfade engine is in `app/src/main/cpp/`:
- `native_mixer.cpp` — Oboe audio output, crossfade logic
- `ring_buffer.h` — Lock-free SPSC ring buffer
- `biquad_filter.h` — HPF/LPF for Automix EQ
- `jni_bridge.cpp` — JNI bindings

Build: `./gradlew assembleDebug` (CMake auto-invoked)

## Spotify Integration

Modular architecture in `app/src/main/java/com/example/kilo/spotify/`:
- **Entry point**: `Spotify` facade class
- **Auth**: `SpotifyAuth` + `SpotifyTotpGenerator`
- **GraphQL**: `SpotifyGraphqlClient` + `SpotifyHashProvider/Discovery/Refresher`
- **Models**: `SpotifyEntities` + `SpotifyDtos`
- **Mapping**: `SpotifyMapper` (DTO → Domain)
- **Matching**: `TrackMatcher` (Jaro-Winkler)

## Code Review Checklist

- [ ] Solves the stated problem
- [ ] Follows project architecture
- [ ] No breaking changes without migration
- [ ] Thread safety (coroutines, StateFlow)
- [ ] Memory leaks (Context, listeners, coroutines)
- [ ] Performance (avoid allocations in hot paths)
- [ ] Accessibility support
- [ ] Tests added/updated

## Community

- **Discussions**: [GitHub Discussions](https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/discussions)
- **Issues**: [GitHub Issues](https://github.com/KineticFusion/Kilo-An-Android-Auto-Music-Player/issues)
- **Discord**: (not available — use Discussions)

## License

By contributing, you agree that your contributions will be licensed under [GNU GPL v3.0](LICENSE).

---

> **Remember**: KILO is an educational project. Code quality, learning, and experimentation are the goals — not production perfection.