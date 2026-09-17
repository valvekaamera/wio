# Wio Terminal D51R USB Welcome Firmware

Arduino firmware for the Seeed Studio Wio Terminal D51R (ATSAMD51P19A).
On USB plug-in it shows a status screen on the LCD, opens USB serial at
115200 baud, prints a heartbeat every second, and accepts the commands
`help`, `info`, `led on`, `led off`, `reboot`.

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
