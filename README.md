# Air Mouse — Android Motion Control

Turn an Android phone into a wireless laptop mouse. The phone combines motion sensors into an orientation estimate and sends movement, click, and scroll commands over UDP. A manual touchpad mode helps test connectivity without sensor input.

## Components

- `android/`: Java Android app, sensor calibration, orientation filtering, and controls.
- `laptop/`: Python receiver with Tkinter UI, QR connection details, and cursor control.
- `perfetto_analysis/`: tracing configuration and analysis helpers.
- [Original setup and calibration guide](docs/ORIGINAL_README.md): protocol, gesture behavior, calibration, and tuning.

This public source copy omits cached third-party web pages, device trace recordings, and generated output. The original development copy is preserved locally.

## Laptop setup

```sh
cd laptop
python -m pip install -r requirements.txt
python main.py
```

Keep the receiver window open. It displays the local IP address, port, and QR code. Configuration is in `laptop/config.yaml`.

## Android setup

Open `android/` in Android Studio with SDK 36 available. The app's minimum SDK is 29. Build with Android Studio or:

```sh
cd android
./gradlew assembleDebug
```

On Windows use `gradlew.bat assembleDebug`. Install on a physical phone with the accelerometer, gyroscope, and magnetometer required by sensor mode.

## Connect and calibrate

Connect both devices to the same trusted local network, scan the receiver's QR code or enter its address, and choose manual or sensor control. Sensor mode requires the gyro, accelerometer, and magnetometer calibration described in the original guide.

The prototype can control the host cursor. Keep the receiver restricted to the intended local network and stop it after use. No Android build or device interaction was performed during this publication update.

## Credits and source

This is a public portfolio copy of the shared project [ParsaMOBB/ZbZ-Air-Mouse](https://github.com/ParsaMOBB/ZbZ-Air-Mouse), prepared for Sobhan Alaeddini's account. Original authorship and existing license notices are retained. See [SOURCE.md](SOURCE.md) for the source revision and publication notes.
