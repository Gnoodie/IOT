package com.example.iot

data class DataModel(
    val id: Int,
    val temperature: Double,
    val humidity: Double,
    val time: String,
    val warningLevel: String
)
