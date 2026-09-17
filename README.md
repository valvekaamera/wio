# Wio Terminal D51R USB Welcome Firmware

Arduino firmware for the Seeed Studio Wio Terminal D51R (ATSAMD51P19A).
On USB plug-in it shows a status screen on the LCD, opens USB serial at
115200 baud, prints a heartbeat every second, and accepts the commands
`help`, `info`, `led on`, `led off`, `reboot`, plus the `wifi ...` commands
described below.

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

Once `wifi connect` prints an IP, `ping <that-ip>` from your laptop for an
end-to-end check.

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
