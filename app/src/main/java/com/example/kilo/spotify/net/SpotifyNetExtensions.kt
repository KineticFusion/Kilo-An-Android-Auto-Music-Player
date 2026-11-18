package com.example.kilo.spotify.net

import com.example.kilo.spotify.SpotifyResult

// Extension functions for SpotifyResult to avoid cross-package resolution issues with sealed interfaces
inline fun <T, R> SpotifyResult<T>.mapResult(transform: (T) -> R): SpotifyResult<R> = when (this) {
    is SpotifyResult.Success -> SpotifyResult.Success(transform(value))
    is SpotifyResult.Failure -> this
}