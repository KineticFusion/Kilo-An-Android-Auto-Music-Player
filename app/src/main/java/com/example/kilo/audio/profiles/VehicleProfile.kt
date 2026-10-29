package com.example.kilo.audio.profiles

data class VehicleProfile(
    val name: String = "Car Concert",
    val soundProfileId: String = "car_concert",
    val cabinCompensationEnabled: Boolean = true,
    val maximumEqBoostDb: Float = 2.7f
)
