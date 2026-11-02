package com.example.kilo.discovery.social.blend

import com.example.kilo.companion.CompanionProfile
import com.example.kilo.discovery.TasteBlendResult

class SharedDNAEngine(private val calculator: CompatibilityCalculator = CompatibilityCalculator()) {
    fun merge(first: CompanionProfile, second: CompanionProfile): TasteBlendResult = calculator.calculate(first, second)
}
