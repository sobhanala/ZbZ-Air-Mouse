# Air Mouse

Turn an Android phone into a wireless mouse for a laptop. The phone reads its motion
sensors, fuses them into an orientation, and sends mouse movement / click / scroll
commands to a laptop over UDP. A manual touchpad mode is also included for testing the
full command path without sensors.

## Components

- `android/` — Android phone app (Java, API 29+). Connection, manual control, sensor
  calibration and sensor-based ("Air Mouse") control.
- `laptop/` — Python laptop server (Tkinter UI + `pyautogui`). Shows a QR code and
  connection info, receives UDP packets, and drives the OS cursor.
- `Materials/` — project statement and supporting course materials (filter references,
  sensor docs).

## Protocol overview

JSON over UDP. Phone → laptop: `hello`, `move` (`dx`/`dy`, no ACK), `click`/`scroll`
(ACK required), `disconnect`. Laptop → phone: `hello_ack` (includes `screenWidth`,
`screenHeight`, `serverSensitivity`), per-message `ack`, and `cursor` (`x`/`y`, the
current absolute cursor position) so the phone can draw where the pointer is.

## Laptop setup

```bash
cd laptop
pip install -r requirements.txt
python main.py
```

The window shows the laptop IP, port, and a QR code. Keep it open while connecting.
Tunables live in `laptop/config.yaml` (e.g. `cursorUpdateIntervalMillis` throttles how
often the cursor position is sent back to the phone; `logMovePackets` logs incoming moves).

## Android setup

Open `android/` in Android Studio (or build with Gradle: `./gradlew assembleDebug`) and
install on a **physical** device — sensor mode needs a real accelerometer, gyroscope, and
magnetometer. No special sensor permission is required (sensors run at the game rate).

## Connecting

- **QR:** tap *Scan QR Code* and scan the code shown by the laptop app.
- **Manual:** enter the laptop IP and port (default `5000`) and tap *Connect*.

After connecting, choose a mode:

### Manual Control

A touchpad area for movement plus left/right click and scroll up/down buttons. Useful for
verifying connectivity independent of the sensors.

### Air Mouse (Sensor)

Hold the phone flat with the screen up and the top edge pointing toward the laptop.

- Sweep left/right (rotate in the horizontal plane) → cursor moves left/right.
- Tip the top of the phone up/down → cursor moves up/down.
- Twist the phone about its long axis past a threshold → left click (the on-screen square
  blinks).
- Push the phone quickly forward/back along its long axis → scroll up/down (the square
  becomes a triangle pointing in the scroll direction). Slow motion is ignored.

The screen rectangle and cursor square in the UI reflect the actual cursor position
reported back by the laptop.

## Calibration

Sensor mode requires a one-time calibration (saved on the device and reused on later
launches; you can *Recalibrate* any time). Three stages:

1. **Gyroscope** — lay the phone still on a flat surface; samples are averaged to remove
   bias.
2. **Accelerometer** — hold the phone in six orientations (each axis pointing up and down
   in turn) and tap *Capture Position*; offset and scale are computed per axis.
3. **Magnetometer** — move the phone through a figure-8 in the air, rotating through all
   directions, until enough range is covered, then tap *Finish*. Per-axis min/max give the
   hard/soft-iron offset and scale.

Orientation is then estimated with a from-scratch Madgwick AHRS filter (gyro + accel +
mag), so the cursor stays stable and does not drift while the phone is held still.

## Tuning

Sensor/gesture constants are Android resources in
`android/app/src/main/res/values/integers.xml` (filter `beta`, motion gain, click twist
threshold, scroll acceleration threshold, calibration sample counts, etc.). If a gesture
or axis feels reversed, the `*_sign` integers flip its direction.

## Troubleshooting

- **No cursor movement:** confirm the laptop app is running and shows *Connected*; check
  the phone and laptop are on the same network and the port matches.
- **Cursor drifts when still:** recalibrate (especially the gyroscope and magnetometer
  stages).
- **Clicks/scrolls too easy/hard to trigger:** adjust `click_twist_threshold_deg` /
  `scroll_accel_threshold` in `integers.xml`.
- **Sensor mode unavailable:** the device is missing a required sensor; use Manual Control.
