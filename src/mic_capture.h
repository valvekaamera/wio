// 16 kHz / 16-bit mono capture from the Wio Terminal's analog microphone.
//
// ADC1 runs free on WIO_MIC (PC30, AIN12) at ~80 kS/s; TC4 fires at exactly
// 16 kHz and the ISR latches the latest ADC result into a lock-free ring
// buffer after DC removal and gain. The main loop drains the ring with read().
// No DMA is used so there is no channel contention with the rpcWiFi SPI link.
#pragma once
#include <Arduino.h>

namespace mic {

constexpr uint32_t kSampleRate = 16000;
constexpr size_t kRingSamples = 4096;   // 256 ms of audio; must be a power of two

void begin();                       // one-time peripheral setup (timer left stopped)
void start();                       // clear ring, start ADC + timer
void stop();                        // stop timer, disable ADC
bool isRunning();

size_t available();                 // samples ready to read
size_t read(int16_t* dst, size_t maxSamples);
uint32_t overruns();                // samples dropped because the ring was full
uint16_t takePeak();                // max |sample| since last call (VU meter), then reset

}  // namespace mic
