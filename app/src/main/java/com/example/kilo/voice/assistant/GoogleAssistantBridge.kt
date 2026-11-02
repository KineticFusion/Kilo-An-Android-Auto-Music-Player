package com.example.kilo.voice.assistant

import com.example.kilo.voice.domain.VoiceExecution

/** Entry point used by MainActivity for App Actions and Android media-search intents. */
object GoogleAssistantBridge {
    suspend fun fulfill(
        request: AssistantRequest,
        router: AssistantVoiceRouter
    ): VoiceExecution = router.route(request)
}
