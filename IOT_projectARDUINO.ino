#include <ESP8266WiFi.h>
#include "DHT.h"

// ---------- Cấu hình chân ----------
#define DHTPIN D4
#define DHTTYPE DHT11
#define RELAY_PIN  D2
#define LED_CANHBAO D1   // LED cảnh báo mức 1
#define LED_BAODONG D0   // LED cảnh báo mức 2

// ---------- WiFi ----------
const char* ssid = "tungtung";        
const char* password = "ttung123";    

// ---------- Biến toàn cục ----------
DHT dht(DHTPIN, DHTTYPE);
WiFiServer server(80);

bool relayState = false;            
unsigned long canhBao2Start = 0;    // Thời điểm bắt đầu cảnh báo mức 2
bool canhBao3Active = false;        // Đã kích hoạt cảnh báo 3 hay chưa

// ---------- SETUP ----------
void setup() {
  Serial.begin(115200);
  pinMode(RELAY_PIN, OUTPUT);
  pinMode(LED_CANHBAO, OUTPUT);
  pinMode(LED_BAODONG, OUTPUT);

  digitalWrite(RELAY_PIN, HIGH); // Relay tắt mặc định
  digitalWrite(LED_CANHBAO, LOW);
  digitalWrite(LED_BAODONG, LOW);

  dht.begin();

  Serial.println("Dang ket noi WiFi...");
  WiFi.begin(ssid, password);
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println();
  Serial.println("WiFi da ket noi!");
  Serial.print("IP cua ESP8266: ");
  Serial.println(WiFi.localIP());

  server.begin();
}

// ---------- LOOP ----------
void loop() {
  float h = dht.readHumidity();
  float t = dht.readTemperature();

  if (!isnan(h) && !isnan(t)) {

    unsigned long currentTime = millis();

    // ================= XỬ LÝ CẢNH BÁO =================

    // --- Cảnh báo 3: nếu mức 2 kéo dài quá 3 phút ---
    if (canhBao3Active) {
      // Giữ mức 3
      digitalWrite(LED_CANHBAO, HIGH);
      digitalWrite(LED_BAODONG, HIGH);
      relayState = true;
      digitalWrite(RELAY_PIN, LOW);
      Serial.println("!!! CANH BAO MUC 3 - DUY TRI 3 PHUT MUC 2 !!!");
    }
    else if (t >= 8 && t <= 10) {
      // Mức 2: từ 8–10°C
      digitalWrite(LED_CANHBAO, LOW);
      digitalWrite(LED_BAODONG, HIGH);
      relayState = true;
      digitalWrite(RELAY_PIN, LOW);

      if (canhBao2Start == 0) {
        canhBao2Start = currentTime;
        Serial.println(">>> BAT CANH BAO MUC 2 <<<");
      }

      // Nếu giữ trên 8°C > 3 phút (180000ms) → cảnh báo 3
      if (currentTime - canhBao2Start >= 180000) {
        canhBao3Active = true;
        Serial.println("!!! CANH BAO MUC 3 DUOC KICH HOAT SAU 3 PHUT !!!");
      }
    }
    else if (t > 6 && t < 8) {
      // Mức 1: trên 6 nhưng dưới 8°C
      digitalWrite(LED_CANHBAO, HIGH);
      digitalWrite(LED_BAODONG, LOW);
      relayState = true;
      digitalWrite(RELAY_PIN, LOW);

      // Reset nếu hạ từ mức 2 xuống mức 1
      canhBao2Start = 0;
      canhBao3Active = false;
      Serial.println(">>> CANH BAO MUC 1 <<<");
    }
    else if (t <= 6) {
      // Bình thường: tắt hết
      digitalWrite(LED_CANHBAO, LOW);
      digitalWrite(LED_BAODONG, LOW);
      relayState = false;
      digitalWrite(RELAY_PIN, HIGH);

      canhBao2Start = 0;
      canhBao3Active = false;
      Serial.println(">>> BINH THUONG <<<");
    }
    else {
      // Trường hợp > 10°C — tự động coi là mức 3 luôn
      digitalWrite(LED_CANHBAO, HIGH);
      digitalWrite(LED_BAODONG, HIGH);
      relayState = true;
      digitalWrite(RELAY_PIN, LOW);
      Serial.println("!!! CANH BAO MUC 3 - VUOT 10 DO !!!");
    }

    // ================= LOG RA SERIAL =================
    Serial.print("Nhiet do: ");
    Serial.print(t);
    Serial.print(" *C | Do am: ");
    Serial.print(h);
    Serial.print(" % | Trang thai quat: ");
    Serial.println(relayState ? "BAT" : "TAT");

  } else {
    Serial.println("Loi doc cam bien DHT11!");
  }

  // ================= GỬI DỮ LIỆU QUA WIFI =================
  WiFiClient client = server.available();
  if (client) {
    String req = client.readStringUntil('\r');
    client.flush();

    String json = "{\"temperature\":" + String(dht.readTemperature(), 1) +
                  ",\"humidity\":" + String(dht.readHumidity(), 1) +
                  ",\"fanState\":\"" + (relayState ? "ON" : "OFF") + "\"}";
    client.println("HTTP/1.1 200 OK");
    client.println("Content-Type: application/json");
    client.println("Connection: close");
    client.println();
    client.println(json);
  }

  delay(1000);
}
