# Spotify WebView Bridge Implementation for Kilo

## Architecture Overview (Based on Orpheon/Myspoti)

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         KILO APP (Kotlin)                                │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │  SpotifyApi      │  │  SpotifyAuth     │  │  SpotifyPlayback     │  │
│  │  (GraphQL/REST)  │  │  (WebView OAuth) │  │  Controller          │  │
│  └────────┬─────────┘  └────────┬─────────┘  └──────────┬───────────┘  │
│           │                     │                       │              │
│           ▼                     ▼                       ▼              │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │              SpotifyWebViewBridge (AndroidView)                  │  │
│  │  ┌──────────────────────────────────────────────────────────┐   │  │
│  │  │  WebView (open.spotify.com)                              │   │  │
│  │  │  - Loads Spotify Web Player                              │   │  │
│  │  │  - Injects bridge.js (Myspoti bridge)                   │   │  │
│  │  │  - Communicates via evaluateJavascript / postMessage    │   │  │
│  │  └──────────────────────────────────────────────────────────┘   │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│           │                     │                       │              │
│           ▼                     ▼                       ▼              │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────┐  │
│  │  Metadata:       │  │  Auth:           │  │  Playback:           │  │
│  │  Search, Library │  │  sp_dc cookie,   │  │  play, pause, seek,  │  │
│  │  Playlists, Home │  │  access token    │  │  queue, volume, etc. │  │
│  └──────────────────┘  └──────────────────┘  └──────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    HYBRID PLAYBACK (Existing)                            │
│  Spotify metadata → YouTube Music audio (HybridEngine)                  │
│  OR: Spotify Web Player audio (via WebView bridge)                      │
└─────────────────────────────────────────────────────────────────────────┘
```

## Key Components to Implement

### 1. SpotifyWebViewBridge.kt (Core Bridge)
- AndroidView with WebView loading open.spotify.com
- Injects `bridge.js` (from Myspoti) for player control
- Message channel: `evaluateJavascript` (host→page) + `postMessage` (page→host)
- Handles: ready, state, queue, result, error messages

### 2. SpotifyAuthManager.kt (WebView OAuth)
- Reuses `BitChordWebViewLoginDialog` pattern
- Loads `https://accounts.spotify.com/authorize` with PKCE
- Captures `sp_dc` cookie + access token on redirect
- Stores encrypted in `HybridSourceRegistry` (already has this!)

### 3. SpotifyApiClient.kt (GraphQL/REST)
- Uses `HybridSourceRegistry.getSpotifyWebSession()` for tokens
- Implements: search, library, playlists, home, podcasts, devices
- Uses Spotify's internal GraphQL operations (179 ops)

### 4. SpotifyPlaybackController.kt
- Controls WebView player: play, pause, seek, next, prev, queue, volume
- Syncs playback state to Kilo's ExoPlayer for notifications/Android Auto
- Handles free tier limitations (ads, no seek during ads)

### 5. SpotifySourceProvider.kt (AudioSourceProvider)
- Implements `AudioSourceProvider` interface
- Search uses SpotifyApiClient
- Resolve stream delegates to HybridEngine (YouTube Music audio) OR WebView playback

## Implementation Files

```
app/src/main/java/com/example/kilo/spotify/
├── SpotifyWebViewBridge.kt          # WebView + JS bridge
├── SpotifyAuthManager.kt            # WebView OAuth flow
├── SpotifyApiClient.kt              # GraphQL/REST API
├── SpotifyPlaybackController.kt     # Player control via bridge
├── SpotifySourceProvider.kt         # AudioSourceProvider impl
├── SpotifyModels.kt                 # Data models
├── bridge/
│   └── spotify-bridge.js            # Injected JS (from Myspoti)
└── ui/
    ├── SpotifyLoginDialog.kt        # WebView OAuth dialog
    ├── SpotifySourceSettings.kt     # Source priority/enable UI
    └── SpotifyPlayerOverlay.kt      # Playback controls when WebView active
```

## bridge.js (Injected into WebView)

Based on Myspoti's `player-bridge.js`:
- Finds React context tree to access Spotify's internal services
- Exposes `Myspoti.call(id, method, args)` for host→page
- Sends `postMessage` for page→host (ready, state, queue, result, error)
- Methods: player.play, player.pause, player.seek, player.next, player.previous, player.setShuffle, player.setRepeat, queue.add, queue.remove, queue.clear, volume.get, volume.set, library.contains, library.add, library.remove, token.get

## Integration Points with Existing Code

1. **HybridSourceRegistry** - Already has Spotify config, token storage, provider registration
2. **HybridEngine** - Already calls `SpotifyCatalogBridgeProvider` for search/home/playlists
3. **MainActivity** - Add Spotify login to setup wizard, source selector in settings
4. **VybeMediaService** - Handle Spotify playback for Android Auto/notifications
5. **PlayerHolder** - Resolve streams from Spotify via HybridEngine

## Free Tier Limitations (from Orpheon)

| Feature | Status |
|---------|--------|
| Jam | RESTRICTED (start fails) |
| Private Session | Not supported by Web Player |
| Friend Activity | Not implemented (empty list) |
| Device Transfer | Needs 2nd Connect device |
| Ads | Present, restrictions forbid skip/seek |

## Dependencies Needed

```kotlin
// build.gradle.kts (app module)
dependencies {
    // WebView
    implementation("androidx.webkit:webkit:1.14.0")
    
    // JSON parsing (already have org.json)
    // OKHttp (already have)
    
    // Coroutines (already have)
}
```

## Step-by-Step Implementation

### Phase 1: WebView Bridge Foundation (Week 1)
1. Create `spotify-bridge.js` asset (adapt from Myspoti)
2. Implement `SpotifyWebViewBridge` Composable
3. Test basic communication: ready, state messages

### Phase 2: Authentication (Week 1-2)
1. Implement `SpotifyAuthManager` with WebView OAuth
2. Integrate with `HybridSourceRegistry.saveSpotifyWebSession()`
3. Add Spotify login to setup wizard (step 1.5)

### Phase 3: API Client (Week 2)
1. Implement `SpotifyApiClient` with GraphQL operations
2. Connect to `SpotifyCatalogBridgeProvider` for search/home/playlists
3. Test metadata fetching

### Phase 4: Playback Control (Week 2-3)
1. Implement `SpotifyPlaybackController` 
2. Bridge player controls to WebView
3. Sync state to ExoPlayer for notifications/Android Auto

### Phase 5: AudioSourceProvider (Week 3)
1. Implement `SpotifySourceProvider` 
2. Register with `HybridSourceRegistry`
3. Enable hybrid search (Spotify metadata + YT audio)

### Phase 6: UI Integration (Week 3-4)
1. Source selector in settings
2. Spotify library/playlists in Library tab
3. Unified search results
4. Player overlay when WebView active

## Testing Strategy

1. **Unit Tests**: Bridge message parsing, token management, model conversion
2. **Integration Tests**: WebView communication (with test HTML page)
3. **Manual Tests**: 
   - Login flow with free/premium accounts
   - Search, library, playlists
   - Playback controls
   - Queue management
   - Cross-source queue (Spotify track → YT track)
   - Android Auto with Spotify source
   - Free tier ads handling

## Legal/Compliance

- Uses **official Spotify Web Player** (open.spotify.com)
- No reverse engineering of private APIs
- WebView loads official Spotify page
- Bridge only calls exposed React context services
- Complies with Spotify Developer Terms (using Web Player as intended)
- User must have Spotify account (free or premium)