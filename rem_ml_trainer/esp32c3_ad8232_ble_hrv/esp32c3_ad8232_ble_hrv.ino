/**
 * FlashAlarm - ESP32-C3 + AD8232 Real-Time ECG & True R-R Interval Detector
 * 
 * Hardware:
 *   - ESP32-C3 (e.g. ESP32-C3 SuperMini / DevKit)
 *   - AD8232 Heart Rate Monitor Analog Front-End
 * 
 * Wiring:
 *   - AD8232 3.3V  -> ESP32-C3 3V3
 *   - AD8232 GND   -> ESP32-C3 GND
 *   - AD8232 OUT   -> ESP32-C3 GPIO 1 (ADC1 Channel 1)
 *   - AD8232 LO+   -> ESP32-C3 GPIO 2 (Leads Off +)
 *   - AD8232 LO-   -> ESP32-C3 GPIO 3 (Leads Off -)
 *   - AD8232 SDN   -> ESP32-C3 3V3 (Keep enabled)
 * 
 * Protocol:
 *   - Standard Bluetooth SIG Heart Rate Service (0x180D)
 *   - Heart Rate Measurement Characteristic (0x2A37)
 *   - Flags 0x10 (Bit 4 = 1): Sends uint8 HR + 16-bit millisecond R-R intervals
 * 
 * Algorithm:
 *   - Real-time Pan-Tompkins QRS Detection (250 Hz sampling)
 *   - Bandpass filter (5-15 Hz) -> Derivative -> Squaring -> Moving Average Integrator -> Dual Adaptive Threshold
 */

#include <Arduino.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// Pin Configuration
#define PIN_ECG_IN      1   // GPIO 1 / ADC1_CH1
#define PIN_LEADS_OFF_P 2   // GPIO 2 / LO+
#define PIN_LEADS_OFF_M 3   // GPIO 3 / LO-

// Sampling configuration: 250 Hz -> 4000 microseconds per sample
#define FS_HZ           250
#define SAMPLE_INTERVAL_US 4000

// BLE Standard UUIDs (Heart Rate Service)
#define SERVICE_UUID_HEART_RATE        "0000180d-0000-1000-8000-00805f9b34fb"
#define CHAR_UUID_HEART_RATE_MEASURE   "00002a37-0000-1000-8000-00805f9b34fb"

BLEServer* pServer = nullptr;
BLECharacteristic* pHrCharacteristic = nullptr;
bool deviceConnected = false;
bool oldDeviceConnected = false;

// Pan-Tompkins Signal Processing Variables
// 1. Bandpass filter ring buffer (2nd order IIR Butterworth: 5-15 Hz @ 250Hz)
float x_hist[3] = {0.0f, 0.0f, 0.0f};
float y_hist[3] = {0.0f, 0.0f, 0.0f};

// IIR Coefficients (5-15 Hz bandpass at 250Hz sampling)
// Passband: ~5Hz to 15Hz
static const float b_coeff[3] = { 0.1159f, 0.0000f, -0.1159f };
static const float a_coeff[3] = { 1.0000f, -1.7226f, 0.7682f };

// 2. Derivative buffer (5-point derivative)
float deriv_buf[5] = {0.0f};
int deriv_idx = 0;

// 3. Moving Window Integrator buffer (window = ~150 ms = 38 samples)
#define MWI_WINDOW 38
float mwi_buf[MWI_WINDOW] = {0.0f};
int mwi_idx = 0;
float mwi_sum = 0.0f;

// 4. Adaptive Thresholding
float peak_signal = 50.0f;
float peak_noise = 10.0f;
float threshold = 20.0f;

// Timing & Beat Detection
unsigned long last_r_peak_time_ms = 0;
unsigned long sample_count = 0;
#define REFRACTORY_SAMPLES 50  // 200 ms @ 250Hz (prevents T-wave false triggers)
unsigned long samples_since_last_beat = 0;

class MyServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* pServer) {
        deviceConnected = true;
        Serial.println("[BLE] Central device connected!");
    }
    void onDisconnect(BLEServer* pServer) {
        deviceConnected = false;
        Serial.println("[BLE] Central device disconnected, restarting advertising...");
    }
};

void setupBle() {
    BLEDevice::init("FlashAlarm-ECG");
    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new MyServerCallbacks());

    BLEService *pService = pServer->createService(SERVICE_UUID_HEART_RATE);
    pHrCharacteristic = pService->createCharacteristic(
        CHAR_UUID_HEART_RATE_MEASURE,
        BLECharacteristic::PROPERTY_NOTIFY
    );
    pHrCharacteristic->addDescriptor(new BLE2902());

    pService->start();
    BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
    pAdvertising->addServiceUUID(SERVICE_UUID_HEART_RATE);
    pAdvertising->setScanResponse(true);
    pAdvertising->setMinPreferred(0x06);
    pAdvertising->setMinPreferred(0x12);
    BLEDevice::startAdvertising();
    Serial.println("[BLE] Heart Rate Service Advertising started.");
}

void setup() {
    Serial.begin(115200);
    delay(500);
    Serial.println("==========================================");
    Serial.println("  FlashAlarm ESP32-C3 True-HRV ECG Sensor");
    Serial.println("==========================================");

    pinMode(PIN_LEADS_OFF_P, INPUT);
    pinMode(PIN_LEADS_OFF_M, INPUT);
    analogSetPinAttenuation(PIN_ECG_IN, ADC_11db); // 0V ~ 3.1V dynamic range

    setupBle();
}

void loop() {
    static unsigned long next_sample_us = 0;
    unsigned long now_us = micros();

    // Check leads off status
    bool leads_off = (digitalRead(PIN_LEADS_OFF_P) == HIGH || digitalRead(PIN_LEADS_OFF_M) == HIGH);
    if (leads_off) {
        // Electrodes detached or bad skin contact
        delay(50);
        return;
    }

    // 250 Hz sampling loop (every 4000 microseconds)
    if ((long)(now_us - next_sample_us) >= 0) {
        next_sample_us = now_us + SAMPLE_INTERVAL_US;
        sample_count++;
        samples_since_last_beat++;

        // Read raw ADC (12-bit: 0 - 4095)
        int raw_adc = analogRead(PIN_ECG_IN);

        // Step 1: Bandpass Filter (2nd Order IIR Butterworth 5-15 Hz)
        x_hist[2] = x_hist[1];
        x_hist[1] = x_hist[0];
        x_hist[0] = (float)raw_adc;

        float filtered = b_coeff[0] * x_hist[0] + b_coeff[1] * x_hist[1] + b_coeff[2] * x_hist[2]
                         - a_coeff[1] * y_hist[1] - a_coeff[2] * y_hist[2];
        y_hist[2] = y_hist[1];
        y_hist[1] = filtered;

        // Step 2: 5-point Derivative Filter: y[n] = (2x[n] + x[n-1] - x[n-3] - 2x[n-4]) / 8
        deriv_buf[deriv_idx] = filtered;
        int i0 = deriv_idx;
        int i1 = (deriv_idx + 4) % 5;
        int i3 = (deriv_idx + 2) % 5;
        int i4 = (deriv_idx + 1) % 5;
        float deriv = (2.0f * deriv_buf[i0] + deriv_buf[i1] - deriv_buf[i3] - 2.0f * deriv_buf[i4]) / 8.0f;
        deriv_idx = (deriv_idx + 1) % 5;

        // Step 3: Squaring
        float squared = deriv * deriv;

        // Step 4: Moving Window Integrator (MWI)
        mwi_sum -= mwi_buf[mwi_idx];
        mwi_buf[mwi_idx] = squared;
        mwi_sum += squared;
        mwi_idx = (mwi_idx + 1) % MWI_WINDOW;
        float mwi_val = mwi_sum / MWI_WINDOW;

        // Step 5: Adaptive Dual-Threshold QRS Detection
        if (mwi_val > threshold && samples_since_last_beat > REFRACTORY_SAMPLES) {
            // R-peak confirmed!
            unsigned long current_time_ms = millis();
            unsigned long rr_ms = current_time_ms - last_r_peak_time_ms;

            // Update Signal Peak
            peak_signal = 0.125f * mwi_val + 0.875f * peak_signal;
            threshold = peak_noise + 0.25f * (peak_signal - peak_noise);

            if (last_r_peak_time_ms > 0 && rr_ms >= 300 && rr_ms <= 2000) {
                // Physiologically valid heart beat: 30 bpm to 200 bpm
                uint8_t hr_bpm = (uint8_t)(60000UL / rr_ms);

                // Print to Serial for debugging
                Serial.printf("[ECG] R-Peak! RR=%lu ms | HR=%d bpm\n", rr_ms, hr_bpm);

                // Transmit via BLE if connected
                if (deviceConnected && pHrCharacteristic != nullptr) {
                    // Standard BLE Heart Rate Measurement Format (0x2A37):
                    // Byte 0: Flags (0x10 = UINT8 HR, RR-Intervals present)
                    // Byte 1: HR BPM
                    // Byte 2-3: RR Interval (1/1024 seconds as per BLE SIG specification)
                    uint16_t rr_ble_units = (uint16_t)((rr_ms * 1024UL) / 1000UL);
                    uint8_t packet[4];
                    packet[0] = 0x10; // Bit 4 = 1
                    packet[1] = hr_bpm;
                    packet[2] = (uint8_t)(rr_ble_units & 0xFF);
                    packet[3] = (uint8_t)((rr_ble_units >> 8) & 0xFF);

                    pHrCharacteristic->setValue(packet, 4);
                    pHrCharacteristic->notify();
                }
            }

            last_r_peak_time_ms = current_time_ms;
            samples_since_last_beat = 0;
        } else {
            // Noise floor adaptation
            peak_noise = 0.125f * mwi_val + 0.875f * peak_noise;
            threshold = peak_noise + 0.25f * (peak_signal - peak_noise);
        }
    }

    // Handle BLE reconnection state
    if (!deviceConnected && oldDeviceConnected) {
        delay(200);
        pServer->startAdvertising();
        oldDeviceConnected = deviceConnected;
    }
    if (deviceConnected && !oldDeviceConnected) {
        oldDeviceConnected = deviceConnected;
    }
}
