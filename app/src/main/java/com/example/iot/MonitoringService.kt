package com.example.iot

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*

class MonitoringService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Tạo Notification Channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel cho foreground service (IMPORTANCE_LOW)
            val foregroundChannel = NotificationChannel(
                "monitoring_channel",
                "Monitoring",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Channel for IoT monitoring service"
            }
            // Channel riêng cho thông báo cảnh báo (IMPORTANCE_HIGH để hỗ trợ rung)
            val alertChannel = NotificationChannel(
                "alert_channel",
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Channel for IoT alert notifications"
                enableVibration(true) // Bật rung cho channel
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(foregroundChannel)
            notificationManager.createNotificationChannel(alertChannel)
            Log.d("MonitoringService", "Notification channels created")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("MonitoringService", "Starting MonitoringService")

        // Tạo thông báo foreground
        val foregroundNotification = NotificationCompat.Builder(this, "monitoring_channel")
            .setContentTitle("Monitoring Service")
            .setContentText("Đang giám sát...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            startForeground(1, foregroundNotification)
            Log.d("MonitoringService", "Foreground service started successfully")
        } catch (e: Exception) {
            Log.e("MonitoringService", "Failed to start foreground service: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        // Lấy dữ liệu từ ESP8266
        scope.launch {
            while (true) {
                try {
                    val sensorData = SensorClient.fetchData("http://192.168.185.169/")
                    if (sensorData != null) {
                        val temp = sensorData.temperature.toDouble()
                        val hum = sensorData.humidity.toDouble()
                        Log.d("MonitoringService", "Temperature: $temp°C, Humidity: $hum%")

                        val sharedPrefs = getSharedPreferences("iot_prefs", MODE_PRIVATE)
                        var highTempStartTime = sharedPrefs.getLong("high_temp_start", 0L)
                        var currentWarningLevel = "SAFE"
                        var isExtendedAlert = false

                        if (temp >= 8.0) {
                            if (highTempStartTime == 0L) {
                                highTempStartTime = System.currentTimeMillis()
                                sharedPrefs.edit().putLong("high_temp_start", highTempStartTime).apply()
                            }
                            val duration = System.currentTimeMillis() - highTempStartTime
                            currentWarningLevel = "ALERT"
                            if (duration >= 180000) { // 3 phút
                                isExtendedAlert = true
                            }
                        } else if (temp >= 6.0 && temp < 8.0) {
                            currentWarningLevel = "WARNING"
                            if (highTempStartTime != 0L) {
                                sharedPrefs.edit().putLong("high_temp_start", 0L).apply()
                                highTempStartTime = 0L
                            }
                        } else {
                            currentWarningLevel = "SAFE"
                            if (highTempStartTime != 0L) {
                                sharedPrefs.edit().putLong("high_temp_start", 0L).apply()
                                highTempStartTime = 0L
                            }
                        }

                        sendNotification(temp, hum, currentWarningLevel, isExtendedAlert)
                    } else {
                        Log.e("MonitoringService", "Failed to fetch data from ESP8266")
                    }
                } catch (e: Exception) {
                    Log.e("MonitoringService", "Error in MonitoringService loop: ${e.message}", e)
                }
                delay(30000) // Mỗi 30 giây
            }
        }
        return START_STICKY
    }

    private fun sendNotification(temp: Double, hum: Double, warningLevel: String, isExtendedAlert: Boolean) {
        // Kiểm tra quyền POST_NOTIFICATIONS
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                Log.w("MonitoringService", "POST_NOTIFICATIONS permission not granted, skipping notification")
                return
            }
        }

        // Kiểm tra quyền VIBRATE
        val hasVibratePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
        if (!hasVibratePermission) {
            Log.w("MonitoringService", "VIBRATE permission not granted, sending notification without vibration")
        }

        try {
            // Chọn mẫu rung dựa trên warningLevel
            val vibrationPattern = if (hasVibratePermission) {
                when (warningLevel) {
                    "ALERT" -> longArrayOf(0, 1500, 50, 1500, 50) // Rung mạnh
                    "WARNING" -> longArrayOf(0, 800, 100, 800, 100, 800, 100) // Rung nhẹ
                    else -> longArrayOf(0) // Không rung cho SAFE
                }
            } else {
                longArrayOf(0) // Không rung nếu thiếu quyền
            }

            val title = if (warningLevel == "ALERT") {
                if (isExtendedAlert) "NGƯNG CẤP PHÁT: Nhiệt độ cao" else "BÁO ĐỘNG: Nhiệt độ cao"
            } else if (warningLevel == "WARNING") {
                "CẢNH BÁO: Nhiệt độ cao"
            } else {
                "AN TOÀN: Trạng thái bình thường"
            }
            val content = if (warningLevel == "ALERT") {
                if (isExtendedAlert) {
                    "Nhiệt độ: %.1f°C, Độ ẩm: %.1f%%. Vắc xin cần được đưa về cho cơ quan cấp phát kiểm tra!".format(temp, hum)
                } else {
                    "Nhiệt độ: %.1f°C, Độ ẩm: %.1f%%. Vaccine cần được kiểm tra lại trước khi sử dụng! Kiểm tra tủ lạnh ngay!".format(temp, hum)
                }
            } else if (warningLevel == "WARNING") {
                "Nhiệt độ: %.1f°C, Độ ẩm: %.1f%%. Chú ý đến nhiệt độ! Kiểm tra tủ lạnh ngay!".format(temp, hum)
            } else {
                "Nhiệt độ: %.1f°C, Độ ẩm: %.1f%%. Vắc xin an toàn, tiếp tục theo dõi.".format(temp, hum)
            }

            // Sử dụng channel riêng cho thông báo cảnh báo
            val notification = NotificationCompat.Builder(this, "alert_channel")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(vibrationPattern)
                .setAutoCancel(true) // Tự hủy khi nhấn
                .build()

            NotificationManagerCompat.from(this).notify(2, notification)
            Log.d("MonitoringService", "Notification sent for $warningLevel at $temp°C, vibration: ${vibrationPattern.joinToString()}")
        } catch (e: SecurityException) {
            Log.e("MonitoringService", "SecurityException when sending notification: ${e.message}", e)
        } catch (e: Exception) {
            Log.e("MonitoringService", "Error sending notification: ${e.message}", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d("MonitoringService", "Destroying MonitoringService")
        scope.cancel()
        super.onDestroy()
    }
}