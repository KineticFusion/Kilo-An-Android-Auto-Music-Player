package com.example.kilo.voice.assistant

/** Stable actions used by shortcuts.xml and Android's media-search handoff. */
object AssistantCapabilityProvider {
    const val ACTION_PLAY = "com.example.kilo.action.ASSISTANT_PLAY"
    const val ACTION_SEARCH = "com.example.kilo.action.ASSISTANT_SEARCH"
    const val ACTION_AUDIO_SEARCH = "com.example.kilo.action.ASSISTANT_AUDIO_SEARCH"
    const val ACTION_PLAY_PLAYLIST = "com.example.kilo.action.ASSISTANT_PLAY_PLAYLIST"
    const val ACTION_OPEN_FEATURE = "com.example.kilo.action.ASSISTANT_OPEN_FEATURE"
    const val ACTION_MEDIA_PLAY_FROM_SEARCH = "android.media.action.MEDIA_PLAY_FROM_SEARCH"
    const val ACTION_ANDROID_SEARCH = "android.intent.action.SEARCH"

    const val EXTRA_QUERY = "query"
    const val EXTRA_SONG_QUERY = "songQuery"
    const val EXTRA_FEATURE = "feature"

    val declaredBuiltInIntents = setOf(
        "actions.intent.GET_THING",
        "actions.intent.OPEN_APP_FEATURE"
    )

    val declaredCustomIntents = setOf(
        "custom.actions.intent.START_AUDIO_SEARCH",
        "custom.actions.intent.PLAY_PLAYLIST"
    )
    val declaredMediaIntents = setOf(ACTION_MEDIA_PLAY_FROM_SEARCH)
}
