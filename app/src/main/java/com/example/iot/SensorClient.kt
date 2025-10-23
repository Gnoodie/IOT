package com.example.iot

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class SensorData(val temperature: Float, val humidity: Float, val alert: Boolean)

object SensorClient {
    suspend fun fetchData(baseUrl: String): SensorData? = withContext(Dispatchers.IO) {
        try {
            val url = URL(baseUrl + "/sensor")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.connect()
            val text = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val json = JSONObject(text)
            val t = json.getDouble("temperature").toFloat()
            val h = json.getDouble("humidity").toFloat()
            val alert = json.optBoolean("alert", t > 35 || h > 90)
            SensorData(t, h, alert)
        } catch (e: Exception) {
            null
        }
    }
}
