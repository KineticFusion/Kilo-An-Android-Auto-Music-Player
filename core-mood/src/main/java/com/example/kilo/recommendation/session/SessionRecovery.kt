package com.example.kilo.recommendation.session

object SessionRecovery {
    fun recover(saved:SessionState?,lockKey:String):SessionState? =
        saved?.takeIf{it.lockKey==lockKey}?.let{SessionEvolution.advance(it)}
}
