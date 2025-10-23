Project IOT - Kotlin Android app (minimal)

How to import:
1. Open Android Studio -> Open an existing project -> select the folder 'IOT' created here.
2. If prompted, let Android Studio update Gradle files or download Gradle wrapper.
3. Connect your phone to the Wi-Fi network broadcast by ESP8266 (ESP8266_AP) or ensure the device can reach http://192.168.4.1.
4. Run the app.

Notes:
- The placeholder alert.mp3 is empty. Replace app/src/main/res/raw/alert.mp3 with a real sound file (mp3) if you want audio alerts.
- If your ESP serves the JSON at root path instead of /sensor, SensorClient uses baseUrl + '/sensor'. Adjust in MainActivity or SensorClient if needed.
