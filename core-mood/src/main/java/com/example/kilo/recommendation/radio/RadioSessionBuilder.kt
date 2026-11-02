package com.example.kilo.recommendation.radio

import com.example.kilo.recommendation.session.SessionMode
import com.example.kilo.recommendation.session.SessionState

object RadioSessionBuilder {
    fun create(id:String,lockKey:String)=SessionState(id,SessionMode.RADIO,lockKey)
}
