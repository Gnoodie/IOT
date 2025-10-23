package com.example.iot

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.animation.RotateAnimation
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var txtTemp: TextView
    private lateinit var txtHum: TextView
    private lateinit var txtWarning: TextView
    private lateinit var txtTableContent: TextView
    private lateinit var cardWarning: CardView
    private lateinit var cardTemp: CardView
    private lateinit var cardHum: CardView
    private lateinit var btnToggleAlert: MaterialButton
    private lateinit var btnUpdateChart: MaterialButton
    private lateinit var chart: LineChart
    private lateinit var progressBar: ProgressBar
    private lateinit var tempProgressBar: ProgressBar
    private lateinit var humProgressBar: ProgressBar
    private lateinit var dbHelper: DatabaseHelper

    private val espUrl = "http://192.168.185.169/" // ⚠️ Thay IP thật của bạn
    private val REQUEST_NOTIFICATION_PERMISSION = 100
    private var lastWarningLevel = "UNINITIALIZED"
    private var lastTemp = 0.0
    private var lastHum = 0.0
    private var lastChartData: List<DataModel> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "Starting onCreate")
        try {
            setContentView(R.layout.activity_main)
            Log.d("MainActivity", "setContentView completed")

            txtTemp = findViewById(R.id.txtTemp)
            txtHum = findViewById(R.id.txtHum)
            txtWarning = findViewById(R.id.txtWarning)
            txtTableContent = findViewById(R.id.txtTableContent)
            cardWarning = findViewById(R.id.cardWarning)
            cardTemp = findViewById(R.id.cardTemp)
            cardHum = findViewById(R.id.cardHum)
            btnToggleAlert = findViewById(R.id.btnToggleAlert)
            btnUpdateChart = findViewById(R.id.btnUpdateChart)
            chart = findViewById(R.id.chart)
            progressBar = findViewById(R.id.progressBar)
            tempProgressBar = findViewById(R.id.tempProgressBar)
            humProgressBar = findViewById(R.id.humProgressBar)
            Log.d("MainActivity", "Views initialized")

            // Kích hoạt cuộn cho nội dung bảng
            txtTableContent.movementMethod = ScrollingMovementMethod()

            // Cấu hình biểu đồ
            chart.description.isEnabled = false
            chart.setTouchEnabled(true)
            chart.isDragEnabled = true
            chart.setScaleEnabled(true)
            chart.setPinchZoom(true)
            chart.legend.textColor = Color.BLACK
            chart.axisLeft.textColor = Color.BLACK
            chart.axisRight.textColor = Color.BLACK
            chart.xAxis.textColor = Color.BLACK
            chart.xAxis.setDrawGridLines(false)
            chart.axisLeft.setDrawGridLines(false)
            chart.axisRight.setDrawGridLines(false)
            chart.setNoDataText("Đang tải dữ liệu biểu đồ...")
            chart.setNoDataTextColor(Color.GRAY)
            Log.d("MainActivity", "Chart configured")

            dbHelper = DatabaseHelper(this)
            Log.d("MainActivity", "DatabaseHelper initialized")

            // Đặt màu nền mặc định cho warningLayout
            findViewById<LinearLayout>(R.id.warningLayout).background = resources.getDrawable(R.drawable.gradient_green, null)

            // Reset thời gian đếm "NGƯNG CẤP PHÁT" khi khởi động ứng dụng
            val sharedPrefs = getSharedPreferences("iot_prefs", MODE_PRIVATE)
            sharedPrefs.edit().putLong("high_temp_start", 0L).apply()
            Log.d("MainActivity", "Reset high_temp_start in SharedPreferences")

            // Xử lý nút bật/tắt thông báo - Bỏ toggle, luôn bật
            btnToggleAlert.text = "THÔNG BÁO LUÔN BẬT"
            btnToggleAlert.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.green_button))
            btnToggleAlert.setTextColor(ContextCompat.getColor(this, android.R.color.white))

            // Debug dữ liệu SQLite
            btnToggleAlert.setOnLongClickListener {
                val history = dbHelper.getAllData()
                txtTableContent.text = history.joinToString("\n") { "Time: ${it.time}, Temp: ${it.temperature}°C, Hum: ${it.humidity}%, Level: ${it.warningLevel}" }
                true
            }

            // Xử lý nút cập nhật biểu đồ
            btnUpdateChart.setOnClickListener {
                updateChart()
                Toast.makeText(this, "Đã cập nhật biểu đồ!", Toast.LENGTH_SHORT).show()
            }
            Log.d("MainActivity", "Button listeners set")

            // Yêu cầu quyền
            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC)
                }
            }
            // Thêm quyền VIBRATE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.VIBRATE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.VIBRATE)
            }

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_NOTIFICATION_PERMISSION)
            } else {
                startMonitoringService()
            }
            Log.d("MainActivity", "Permissions checked")

            // 🔁 Cập nhật giao diện mỗi 2 giây
            lifecycleScope.launch {
                Log.d("MainActivity", "Starting fetchData coroutine")
                while (true) {
                    fetchDataFromESP()
                    delay(2000)
                }
            }

            // 💾 Lưu dữ liệu mỗi 60 giây
            lifecycleScope.launch {
                Log.d("MainActivity", "Starting saveData coroutine")
                while (true) {
                    delay(60000)
                    saveDataToDB()
                }
            }
            Log.d("MainActivity", "onCreate completed")
        } catch (e: Exception) {
            Log.e("MainActivity", "Crash in onCreate: ${e.message}", e)
            Toast.makeText(this, "Lỗi khởi tạo: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startMonitoringService() {
        Log.d("MainActivity", "Starting MonitoringService")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Cần quyền thông báo để chạy dịch vụ!", Toast.LENGTH_LONG).show()
            return
        }
        val serviceIntent = Intent(this, MonitoringService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun fetchDataFromESP() = withContext(Dispatchers.IO) {
        try {
            Log.d("MainActivity", "Attempting to fetch data using SensorClient at $espUrl")
            val sensorData = SensorClient.fetchData(espUrl.replace("/sensor", ""))
            withContext(Dispatchers.Main) {
                progressBar.visibility = android.view.View.GONE
                if (sensorData != null) {
                    lastTemp = sensorData.temperature.toDouble()
                    lastHum = sensorData.humidity.toDouble()

                    val sharedPrefs = getSharedPreferences("iot_prefs", MODE_PRIVATE)
                    var highTempStartTime = sharedPrefs.getLong("high_temp_start", 0L)
                    var currentWarningLevel = "SAFE"
                    var isExtendedAlert = false

                    if (lastTemp >= 8.0) {
                        if (highTempStartTime == 0L) {
                            highTempStartTime = System.currentTimeMillis()
                            sharedPrefs.edit().putLong("high_temp_start", highTempStartTime).apply()
                        }
                        val duration = System.currentTimeMillis() - highTempStartTime
                        currentWarningLevel = "ALERT"
                        if (duration >= 180000) { // 3 phút
                            isExtendedAlert = true
                        }
                    } else if (lastTemp >= 6.0 && lastTemp < 8.0) {
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

                    // Sử dụng Locale.US và escape % để tránh lỗi định dạng
                    txtTemp.text = String.format(Locale.US, "%.1f °C", lastTemp)
                    txtHum.text = String.format(Locale.US, "%.1f %%", lastHum)
                    tempProgressBar.progress = lastTemp.toInt()
                    humProgressBar.progress = lastHum.toInt()

                    Log.d("MainActivity", "Temperature: $lastTemp°C, Humidity: $lastHum%, Alert: ${sensorData.alert}, Current Warning Level: $currentWarningLevel, Last Warning Level: $lastWarningLevel")

                    val newDrawable = when (currentWarningLevel) {
                        "ALERT" -> resources.getDrawable(R.drawable.gradient_red, null)
                        "WARNING" -> resources.getDrawable(R.drawable.gradient_orange, null)
                        else -> resources.getDrawable(R.drawable.gradient_green, null)
                    }

                    val warningLayout = findViewById<LinearLayout>(R.id.warningLayout)
                    if (currentWarningLevel != lastWarningLevel) {
                        val rotate = RotateAnimation(
                            0f, 360f,
                            RotateAnimation.RELATIVE_TO_SELF, 0.5f,
                            RotateAnimation.RELATIVE_TO_SELF, 0.5f
                        )
                        rotate.duration = 500
                        cardWarning.startAnimation(rotate)
                        val transition = TransitionDrawable(arrayOf(
                            warningLayout.background ?: resources.getDrawable(R.drawable.gradient_green, null),
                            newDrawable
                        ))
                        warningLayout.background = transition
                        transition.startTransition(500)
                        Log.d("MainActivity", "Applying transition to $currentWarningLevel")
                    } else {
                        warningLayout.background = newDrawable
                        Log.d("MainActivity", "Setting direct background for $currentWarningLevel")
                    }

                    when (currentWarningLevel) {
                        "ALERT" -> {
                            if (isExtendedAlert) {
                                txtWarning.text = "NGƯNG CẤP PHÁT"
                                txtTableContent.text = "Vắc xin cần được đưa về cho cơ quan cấp phát kiểm tra"
                            } else {
                                txtWarning.text = "BÁO ĐỘNG"
                                txtTableContent.text = "BÁO ĐỘNG: Vaccine cần được kiểm tra lại trước khi sử dụng!\n" +
                                        "- Làm mát tủ lạnh ngay.\n" +
                                        "- Chuyển vắc xin sang tủ dự phòng.\n" +
                                        "- Liên hệ kỹ thuật viên.\n" +
                                        "- Theo dõi vắc xin."
                            }
                            txtTableContent.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                        }
                        "WARNING" -> {
                            txtWarning.text = "CẢNH BÁO"
                            txtTableContent.text = "CẢNH BÁO: Chú ý đến nhiệt độ!\n" +
                                    "- Kiểm tra cửa tủ lạnh.\n" +
                                    "- Tránh mở tủ thường xuyên.\n" +
                                    "- Theo dõi nhiệt độ liên tục.\n" +
                                    "- Sẵn sàng kế hoạch xử lý."
                            txtTableContent.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_dark))
                        }
                        "SAFE" -> {
                            txtWarning.text = "AN TOÀN"
                            txtTableContent.text = String.format(
                                Locale.US,
                                "Tình trạng bảo quản vắc xin:\n" +
                                        "- Nhiệt độ: %.1f°C (2-8°C là an toàn).\n" +
                                        "- Độ ẩm: %.1f%%.\n" +
                                        "- Vắc xin an toàn, tiếp tục theo dõi.",
                                lastTemp, lastHum
                            )
                            txtTableContent.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.black))
                        }
                    }

                    txtWarning.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    lastWarningLevel = currentWarningLevel
                    // Không gọi updateChart() ở đây
                } else {
                    Log.e("MainActivity", "SensorClient returned null")
                    txtWarning.text = "LỖI KẾT NỐI"
                    findViewById<LinearLayout>(R.id.warningLayout).background = resources.getDrawable(R.drawable.gradient_red, null)
                    txtWarning.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                    txtTableContent.text = "LỖI: Không lấy được dữ liệu từ ESP8266!\n" +
                            "- Kiểm tra Wi-Fi.\n" +
                            "- Kiểm tra thiết bị ESP8266."
                    txtTableContent.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                    // Sử dụng dữ liệu cũ nếu có
                    if (lastTemp != 0.0 && lastHum != 0.0) {
                        txtTemp.text = String.format(Locale.US, "%.1f °C", lastTemp)
                        txtHum.text = String.format(Locale.US, "%.1f %%", lastHum)
                        tempProgressBar.progress = lastTemp.toInt()
                        humProgressBar.progress = lastHum.toInt()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Unexpected error in fetchDataFromESP: ${e.message}", e)
            withContext(Dispatchers.Main) {
                progressBar.visibility = android.view.View.GONE
                txtWarning.text = "LỖI KẾT NỐI"
                findViewById<LinearLayout>(R.id.warningLayout).background = resources.getDrawable(R.drawable.gradient_red, null)
                txtWarning.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
                txtTableContent.text = "LỖI: Không kết nối được ESP8266!\n" +
                        "- Kiểm tra Wi-Fi.\n" +
                        "- Kiểm tra thiết bị ESP8266.\n" +
                        "- Lỗi chi tiết: ${e.message}"
                txtTableContent.setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.holo_red_dark))
                // Sử dụng dữ liệu cũ nếu có
                if (lastTemp != 0.0 && lastHum != 0.0) {
                    txtTemp.text = String.format(Locale.US, "%.1f °C", lastTemp)
                    txtHum.text = String.format(Locale.US, "%.1f %%", lastHum)
                    tempProgressBar.progress = lastTemp.toInt()
                    humProgressBar.progress = lastHum.toInt()
                }
            }
        }
    }

    private fun updateChart() {
        Log.d("MainActivity", "Updating chart")
        try {
            val history = dbHelper.getAllData().takeLast(10) // Lấy 10 dữ liệu gần nhất
            Log.d("MainActivity", "Chart data: $history")

            if (history.isEmpty()) {
                Log.d("MainActivity", "No data available for chart")
                chart.setNoDataText("Không có dữ liệu để hiển thị biểu đồ")
                chart.invalidate()
                return
            }

            // Kiểm tra xem dữ liệu có thay đổi không
            val hasNewData = history != lastChartData
            lastChartData = history

            val tempEntries = mutableListOf<Entry>()
            val humEntries = mutableListOf<Entry>()

            history.forEachIndexed { index, data ->
                tempEntries.add(Entry(index.toFloat(), data.temperature.toFloat()))
                humEntries.add(Entry(index.toFloat(), data.humidity.toFloat()))
            }

            val tempDataSet = LineDataSet(tempEntries, "Nhiệt độ (°C)").apply {
                color = Color.parseColor("#FF5722")
                valueTextColor = Color.BLACK
                lineWidth = 2.5f
                setDrawValues(false)
                setDrawCircles(false)
                setDrawFilled(true)
                fillColor = Color.parseColor("#33FF5722")
                setDrawHighlightIndicators(true)
            }

            val humDataSet = LineDataSet(humEntries, "Độ ẩm (%)").apply {
                color = Color.parseColor("#2196F3")
                valueTextColor = Color.BLACK
                lineWidth = 2.5f
                setDrawValues(false)
                setDrawCircles(false)
                setDrawFilled(true)
                fillColor = Color.parseColor("#332196F3")
                setDrawHighlightIndicators(true)
            }

            val lineData = LineData(tempDataSet, humDataSet)
            chart.data = lineData

            // Chỉ chạy animation nếu có dữ liệu mới
            if (hasNewData) {
                chart.animateXY(1000, 1000)
                Log.d("MainActivity", "New chart data detected, running animation")
            } else {
                Log.d("MainActivity", "No new chart data, skipping animation")
            }

            chart.invalidate()
            Log.d("MainActivity", "Chart updated successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error updating chart: ${e.message}", e)
            chart.setNoDataText("Lỗi khi cập nhật biểu đồ: ${e.message}")
            chart.invalidate()
        }
    }

    private fun saveDataToDB() {
        Log.d("MainActivity", "Saving data to DB")
        try {
            if (lastTemp == 0.0 && lastHum == 0.0) {
                Toast.makeText(this, "Chưa có dữ liệu để lưu!", Toast.LENGTH_SHORT).show()
                return
            }
            val timeNow = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date())
            dbHelper.insertData(lastTemp, lastHum, timeNow, lastWarningLevel)
            Toast.makeText(this, "Đã lưu dữ liệu: $lastTemp°C, $lastHum%", Toast.LENGTH_SHORT).show()
            Log.d("MainActivity", "Data saved to DB: $lastTemp°C, $lastHum%, Level: $lastWarningLevel")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error saving data to DB: ${e.message}", e)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                startMonitoringService()
            } else {
                Toast.makeText(this, "Cần quyền thông báo, rung và foreground service để hiển thị trạng thái!", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        Log.d("MainActivity", "Destroying MainActivity")
        super.onDestroy()
        val serviceIntent = Intent(this, MonitoringService::class.java)
        stopService(serviceIntent)
    }
}