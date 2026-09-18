#include "mic_capture.h"

#include "wiring_private.h"  // pinPeripheral

namespace {

constexpr uint32_t kTimerClockHz = 48000000UL;        // GCLK1 in the Seeed SAMD51 core
constexpr uint8_t kMicAdcChannel = 12;                 // WIO_MIC -> ADC1 AIN12 (variant.cpp)
constexpr int kBitsToS16Shift = 4;                     // 12-bit ADC -> 16-bit sample
constexpr int32_t kSoftwareGain = 2;                   // extra gain; the mic is fairly quiet
constexpr int kDcShift = 8;                            // DC tracker: alpha = 1/256 -> ~10 Hz corner

Adc* const adc = ADC1;
constexpr size_t kMask = mic::kRingSamples - 1;

volatile int16_t ring[mic::kRingSamples];
volatile uint32_t head = 0;      // written by ISR only
volatile uint32_t tail = 0;      // written by main loop only
volatile uint32_t overrunCount = 0;
volatile uint16_t peakAbs = 0;
volatile bool running = false;
int32_t dcEstimate = 2048 << kDcShift;                 // fixed point, starts at mid-scale

inline void syncEnable() { while (adc->SYNCBUSY.bit.ENABLE) {} }

void configureAdc() {
  pinPeripheral(WIO_MIC, PIO_ANALOG);

  adc->CTRLA.bit.ENABLE = 0;
  syncEnable();
  adc->REFCTRL.reg = ADC_REFCTRL_REFSEL_INTVCC1;                // VDDANA (3.3 V) full scale
  while (adc->SYNCBUSY.bit.REFCTRL) {}
  adc->INPUTCTRL.reg = ADC_INPUTCTRL_MUXNEG_GND | ADC_INPUTCTRL_MUXPOS(kMicAdcChannel);
  while (adc->SYNCBUSY.bit.INPUTCTRL) {}
  adc->CTRLB.reg = ADC_CTRLB_RESSEL_12BIT | ADC_CTRLB_FREERUN;
  while (adc->SYNCBUSY.bit.CTRLB) {}
  adc->SAMPCTRL.reg = ADC_SAMPCTRL_SAMPLEN(5);
  while (adc->SYNCBUSY.bit.SAMPCTRL) {}
  adc->AVGCTRL.reg = ADC_AVGCTRL_SAMPLENUM_1 | ADC_AVGCTRL_ADJRES(0);
  while (adc->SYNCBUSY.bit.AVGCTRL) {}
}

void configureTimer() {
  GCLK->PCHCTRL[TC4_GCLK_ID].reg = GCLK_PCHCTRL_GEN_GCLK1 | GCLK_PCHCTRL_CHEN;
  while (!(GCLK->PCHCTRL[TC4_GCLK_ID].reg & GCLK_PCHCTRL_CHEN)) {}
  MCLK->APBCMASK.bit.TC4_ = 1;

  TC4->COUNT16.CTRLA.bit.ENABLE = 0;
  while (TC4->COUNT16.SYNCBUSY.bit.ENABLE) {}
  TC4->COUNT16.CTRLA.bit.SWRST = 1;
  while (TC4->COUNT16.SYNCBUSY.bit.SWRST) {}

  TC4->COUNT16.CTRLA.reg = TC_CTRLA_MODE_COUNT16 | TC_CTRLA_PRESCALER_DIV1 | TC_CTRLA_PRESCSYNC_GCLK;
  TC4->COUNT16.WAVE.reg = TC_WAVE_WAVEGEN_MFRQ;                 // period = CC0
  TC4->COUNT16.CC[0].reg = static_cast<uint16_t>(kTimerClockHz / mic::kSampleRate - 1);  // 2999
  while (TC4->COUNT16.SYNCBUSY.bit.CC0) {}
  TC4->COUNT16.INTFLAG.reg = TC_INTFLAG_MC0;
  TC4->COUNT16.INTENSET.reg = TC_INTENSET_MC0;

  NVIC_DisableIRQ(TC4_IRQn);
  NVIC_ClearPendingIRQ(TC4_IRQn);
  NVIC_SetPriority(TC4_IRQn, 2);
  NVIC_EnableIRQ(TC4_IRQn);
}

}  // namespace

void mic::begin() {
  configureAdc();
  configureTimer();
}

void mic::start() {
  if (running) return;
  head = tail = 0;
  overrunCount = 0;
  peakAbs = 0;
  dcEstimate = 2048 << kDcShift;

  adc->CTRLA.bit.ENABLE = 1;
  syncEnable();
  adc->SWTRIG.bit.START = 1;
  while (adc->SYNCBUSY.bit.SWTRIG) {}
  // Let the free-running converter settle before the first latch.
  while (!adc->INTFLAG.bit.RESRDY) {}
  (void)adc->RESULT.reg;

  TC4->COUNT16.COUNT.reg = 0;
  while (TC4->COUNT16.SYNCBUSY.bit.COUNT) {}
  running = true;
  TC4->COUNT16.CTRLA.bit.ENABLE = 1;
  while (TC4->COUNT16.SYNCBUSY.bit.ENABLE) {}
}

void mic::stop() {
  if (!running) return;
  TC4->COUNT16.CTRLA.bit.ENABLE = 0;
  while (TC4->COUNT16.SYNCBUSY.bit.ENABLE) {}
  running = false;
  adc->CTRLA.bit.ENABLE = 0;
  syncEnable();
}

bool mic::isRunning() { return running; }

size_t mic::available() { return (head - tail) & kMask; }

size_t mic::read(int16_t* dst, size_t maxSamples) {
  size_t n = 0;
  while (n < maxSamples) {
    const uint32_t t = tail;
    if (t == head) break;
    dst[n++] = ring[t];
    tail = (t + 1) & kMask;
  }
  return n;
}

uint32_t mic::overruns() { return overrunCount; }

uint16_t mic::takePeak() {
  const uint16_t p = peakAbs;
  peakAbs = 0;
  return p;
}

extern "C" void TC4_Handler() {
  if (!(TC4->COUNT16.INTFLAG.reg & TC_INTFLAG_MC0)) return;
  TC4->COUNT16.INTFLAG.reg = TC_INTFLAG_MC0;
  if (!running) return;

  const int32_t raw = adc->RESULT.reg & 0x0FFF;
  dcEstimate += ((raw << kDcShift) - dcEstimate) >> kDcShift;
  int32_t s = (raw - (dcEstimate >> kDcShift)) << kBitsToS16Shift;
  s *= kSoftwareGain;
  if (s > 32767) s = 32767;
  if (s < -32768) s = -32768;

  const uint32_t next = (head + 1) & kMask;
  if (next == tail) {
    overrunCount++;
    return;
  }
  ring[head] = static_cast<int16_t>(s);
  head = next;

  const uint16_t a = static_cast<uint16_t>(s < 0 ? -s : s);
  if (a > peakAbs) peakAbs = a;
}
