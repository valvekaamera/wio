# Wio Terminal D51R USB Welcome Firmware
Arduino firmware for the Seeed Studio Wio Terminal D51R (ATSAMD51P19A).
On USB plug-in it shows a status screen on the LCD, opens USB serial at
115200 baud, prints a heartbeat every second, and accepts the commands
`help`, `info`, `led on`, `led off`, `reboot`.
## Flash
1. Install PlatformIO: `pip install platformio`
2. Plug in the board, slide the power switch down twice quickly until the blue LED pulses
3. Run `./scripts/flash.sh` (builds, uploads, opens serial monitor)
Manual: `pio run -t upload` then `pio device monitor -b 115200`.
On Linux add yourself to the `dialout` group once: `sudo usermod -aG dialout $USER`.
