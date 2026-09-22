# Wio Terminal D51R USB Welcome Firmware

Arduino firmware for the Seeed Studio Wio Terminal D51R (ATSAMD51P19A).
On boot it shows **Waiting for FNOL** on the LCD, opens USB serial at
115200 baud, and accepts the commands `help`, `info`, `led on`, `led off`,
`reboot`, `ping`, plus the `wifi ...` commands described below. The three top
buttons emulate a phone call (FNOL = First Notice Of Loss).

## Audio capture, transcription and claim-advisor loop

```
Wio Terminal                         backend/ (Spring Boot, Java 25)            Azure AI Foundry
mic -> ADC1 16 kHz ---ring buffer--> WebSocket ws://host:8090/ws/audio  -->   gpt-4o-mini-transcribe (fi)
buttons C / A / B                    stores recordings/<session>/*.wav          transcript
                                     claim advisor (FnolCase)             <-->  gpt-5.4 + policy tools
                                       tools -> tahti-rest-app / pcpc-rest-app  (insurables, coverages, risks,
                                     LISÄKYSYMYKSET / KORVAUSRATKAISU -> log     claim types, terms, ontology)
```

- **C** starts a session: connects to the backend, sends `start`, then
  streams 16-bit PCM in 64 ms frames while the red **rec** screen runs.
- **A** sends `transcribe`: the backend cuts the audio received so far into
  `segment-NNN.wav`, transcribes it, and runs one **claim-advisor round**:
  hetu + loss date are extracted and validated, the caller's policy is
  discovered through the tool-set, and the verdict comes back to the Wio:
  - **LISÄKYSYMYKSIÄ: N** — the follow-up questions are in the server log
    (with the transcript so far); the first one is shown on the LCD. Ask
    the caller, then press **A** again — the answers join the same case.
  - **KORVAUSRATKAISU VALMIS** — say *"Kiitos, otamme teihin pian
    yhteyttä."* and press **B**.
- **B** sends `stop`: the backend writes `session.wav` and prints the
  **FNOL-YHTEENVETO** with status `VALMIS KORVAUSRATKAISUUN` or `KESKEN`
  (hanging up at any time yields `KESKEN`).

The wire contract is in [`docs/audio-ws-protocol.md`](docs/audio-ws-protocol.md);
backend setup and configuration in [`backend/README.md`](backend/README.md).

Firmware configuration: backend host/port live in `include/app_config.h`
(default `192.168.150.25:8090`) and can be overridden from the gitignored
`include/wifi_secrets.h`. The Wio connects to Wi-Fi at boot and again when C
is pressed if it is not connected; if the backend is unreachable the screen
shows **OFFLINE - audio not sent** and recording continues locally without
storage (there is no SD card fallback yet).

Capture details: ADC1 free-runs on `WIO_MIC` (PC30/AIN12) and TC4 latches a
sample every 62.5 µs into a 4096-sample lock-free ring; a 10 Hz DC blocker
and 2× software gain are applied in the ISR. `status` on the serial console
shows mic overruns, bytes streamed and dropped frames. This capture path has
been compiled and reviewed but **not yet verified on the device**; check the
level bar moves when you speak and inspect `segment-001.wav` on the server.

## Buttons (call emulation)

| Button | Action | Screen |
| --- | --- | --- |
| **C** (left) | Pick up the phone; start mic capture and WebSocket session | Red **rec** with elapsed `mm:ss`, level bar, backend status, last transcript / advisor verdict |
| **B** (middle) | Hang up; send `stop`, close session; server prints the FNOL summary | **Hang up** for 2 s, then back to **Waiting for FNOL** |
| **A** (right) | Send `transcribe` to the backend (transcription + one advisor round) | **Sending FNOL to transcription** for 5 s, then back to **rec**; `Analysoidaan FNOL...` until the verdict arrives |

Rules: C only works while idle, B only while recording. A works from idle or
recording; after the 5 s overlay it returns to where it came from, so a call
in progress resumes with its **rec** timer still counting from pick-up.
Presses during the 2 s / 5 s overlays are ignored. Every press and state change is also logged to
serial (`[button] A`, `[call] picked up - recording`, ...). Button handling
is non-blocking, so serial commands keep working during a call.

## Wi-Fi

Wi-Fi on the Wio Terminal runs on a separate RTL8720 co-processor that talks
to the SAMD51 over eRPC. Two things must be in place before it works:

1. **RTL8720 firmware ≥ 2.0.2** (2.1.x current). This is a one-time host-side
   step using Seeed's tool; it rewrites the co-processor, not this firmware:

   ```bash
   git clone https://github.com/Seeed-Studio/ambd_flash_tool.git /tmp/ambd_flash_tool
   cd /tmp/ambd_flash_tool
   python3 ambd_flash_tool.py erase
   python3 ambd_flash_tool.py flash
   ```

   Afterwards the LCD shows "Burn RTL8720 fw"; that is normal. Flash this
   project again to get back to the app.

2. **Local credentials.** Copy the template and fill in your network:

   ```bash
   cp include/wifi_secrets.h.example include/wifi_secrets.h
   ```

   `include/wifi_secrets.h` is gitignored. Never put the SSID/password in
   `main.cpp` or commit them.

Then verify over the 115200 serial monitor:

| Command | What it proves |
| --- | --- |
| `wifi version` | SAMD51 ↔ RTL8720 link works and firmware is compatible. If this hangs, redo step 1. |
| `wifi scan` | Radio works; your AP should appear with an RSSI. |
| `wifi connect` | Joins the AP from `wifi_secrets.h` and prints IP/gateway/RSSI. |
| `wifi connect <ssid> <password>` | Same, with explicit credentials (case is preserved). |
| `wifi status` | Current state, IP and RSSI. |
| `wifi disconnect` | Leave the AP. |
| `ping` | ICMP ping to the fixed LAN host `192.168.150.25` (4 packets); proves the Wio can reach other machines. |
| `ping <ip>` | Same, to another address. |
| `status` | Call state, mic ring/overruns, stream state and bytes sent, Wi-Fi. |

Once `wifi connect` prints an IP, run `ping` on the Wio and `ping <wio-ip>`
from your laptop for a check in both directions. The fixed target lives in
`kPingTarget` in `src/main.cpp`.

## Connect and flash next time

Docker is **not** required. It is only a workaround if you cannot open
`/dev/ttyACM*` because you are missing `dialout` (or equivalent) access.
With group membership in place, flash from your own shell.

### One-time Linux setup

```bash
sudo usermod -aG dialout "$USER"
```

Then **log out and log back in** (or reboot). A new terminal in the same
session is not enough. Until you do that, you can activate the group in one
shell with `newgrp dialout` and run flash commands in **that** shell.

Confirm:

```bash
groups   # must include dialout
```

Optional, but helps when the board re-enumerates in bootloader mode:

```bash
curl -fsSL https://raw.githubusercontent.com/platformio/platformio-core/develop/platformio/assets/system/99-platformio-udev.rules | sudo tee /etc/udev/rules.d/99-platformio-udev.rules
sudo udevadm control --reload-rules
sudo udevadm trigger
```

Install PlatformIO if needed: `pip install platformio` (keep `~/.local/bin` on
your `PATH`).

### Every time you want to use the board

1. Plug the Wio Terminal in over USB and turn it on.
2. Check that it appears as a serial device, usually `/dev/ttyACM0` or
   `/dev/ttyACM1`:

   ```bash
   ls -l /dev/ttyACM*
   ```

   The device should be owned by group `dialout` and you should be able to
   open it without `sudo`.
3. From the repo root:

   ```bash
   ./scripts/flash.sh
   ```

   This builds, uploads, then opens the serial monitor at **115200 baud**.
   Stop the monitor with Ctrl+C.

   Manual equivalent:

   ```bash
   pio run -t upload
   pio device monitor -b 115200
   ```

If upload fails (`No device found` on `ttyACM*`):

1. Quickly slide the power switch **DOWN twice**.
2. Wait for the blue LED to pulse (UF2 bootloader).
3. Run `./scripts/flash.sh` again.

Do not leave a serial monitor open on the port while uploading; it will
block the upload.
