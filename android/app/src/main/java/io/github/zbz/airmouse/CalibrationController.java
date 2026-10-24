package io.github.zbz.airmouse;

import android.content.Context;

/**
 * Drives the three-stage calibration flow and produces a {@link SensorCalibration}.
 *
 * <ol>
 *   <li>Gyroscope: average stationary samples to find the bias.</li>
 *   <li>Accelerometer: capture six orientations ({@code ±g} on each axis) for offset/scale.</li>
 *   <li>Magnetometer: figure-8 motion tracking per-axis min/max for offset/scale.</li>
 * </ol>
 *
 * The UI calls {@link #begin()} to start, {@link #captureAccelPosition()} once per accelerometer
 * position, and {@link #finishMag()} when the magnetometer sweep looks complete. Sensor samples
 * arrive on the main thread, so callbacks are delivered there too.
 */
final class CalibrationController implements RawSensorReader.Listener {

    enum Stage {
        GYRO,
        ACCEL,
        MAG,
        DONE
    }

    interface Callback {
        /** A new stage/position is active and the UI should update its instructions. */
        void onStageChanged(Stage stage, AccelCalibrator.Position accelPosition);

        /** Progress for the active stage, 0..100. */
        void onProgress(int percent);

        /** Magnetometer sweep has covered enough range; the finish action can be enabled. */
        void onMagReady();

        void onComplete(SensorCalibration calibration);
    }

    private static final AccelCalibrator.Position[] ACCEL_ORDER = {
            AccelCalibrator.Position.Z_UP,
            AccelCalibrator.Position.Z_DOWN,
            AccelCalibrator.Position.X_UP,
            AccelCalibrator.Position.X_DOWN,
            AccelCalibrator.Position.Y_UP,
            AccelCalibrator.Position.Y_DOWN,
    };

    private final RawSensorReader reader;
    private final AirMouseConfig config;
    private final Callback callback;
    private final GyroCalibrator gyroCalibrator = new GyroCalibrator();
    private final AccelCalibrator accelCalibrator = new AccelCalibrator();
    private final MagCalibrator magCalibrator = new MagCalibrator();

    private Stage stage = Stage.GYRO;
    private int accelIndex;
    private boolean accelCollecting;
    private boolean magReadyNotified;

    CalibrationController(Context context, AirMouseConfig config, Callback callback) {
        this.config = config;
        this.callback = callback;
        this.reader = new RawSensorReader(context, this);
    }

    boolean hasRequiredSensors() {
        return reader.hasRequiredSensors();
    }

    void begin() {
        stage = Stage.GYRO;
        accelIndex = 0;
        accelCollecting = false;
        magReadyNotified = false;
        reader.start();
        callback.onStageChanged(Stage.GYRO, null);
        callback.onProgress(0);
    }

    void cancel() {
        reader.stop();
    }

    /** Begins sampling for the current accelerometer position. */
    void captureAccelPosition() {
        if (stage == Stage.ACCEL && !accelCollecting) {
            accelCollecting = true;
            callback.onProgress(0);
        }
    }

    void finishMag() {
        if (stage != Stage.MAG) {
            return;
        }
        stage = Stage.DONE;
        reader.stop();
        SensorCalibration calibration = new SensorCalibration(
                gyroCalibrator.bias(),
                accelCalibrator.offset(),
                accelCalibrator.scale(),
                magCalibrator.offset(),
                magCalibrator.scale()
        );
        callback.onComplete(calibration);
    }

    @Override
    public void onGyro(float[] values, long timestampNanos) {
        if (stage != Stage.GYRO) {
            return;
        }
        gyroCalibrator.addSample(values);
        int target = config.gyroCalibrationSamples;
        callback.onProgress(percent(gyroCalibrator.sampleCount(), target));
        if (gyroCalibrator.sampleCount() >= target) {
            stage = Stage.ACCEL;
            accelIndex = 0;
            accelCollecting = false;
            callback.onStageChanged(Stage.ACCEL, ACCEL_ORDER[accelIndex]);
            callback.onProgress(0);
        }
    }

    @Override
    public void onAccel(float[] values) {
        if (stage != Stage.ACCEL || !accelCollecting) {
            return;
        }
        accelCalibrator.addSample(values);
        int target = config.accelCalibrationSamplesPerPosition;
        callback.onProgress(percent(accelCalibrator.sampleCount(), target));
        if (accelCalibrator.sampleCount() >= target) {
            accelCalibrator.capturePosition(ACCEL_ORDER[accelIndex]);
            accelCollecting = false;
            accelIndex++;
            if (accelIndex >= ACCEL_ORDER.length) {
                stage = Stage.MAG;
                callback.onStageChanged(Stage.MAG, null);
                callback.onProgress(0);
            } else {
                callback.onStageChanged(Stage.ACCEL, ACCEL_ORDER[accelIndex]);
                callback.onProgress(0);
            }
        }
    }

    @Override
    public void onMag(float[] values) {
        if (stage != Stage.MAG) {
            return;
        }
        magCalibrator.addSample(values);
        int byCount = percent(magCalibrator.sampleCount(), config.magCalibrationSamples);
        int bySpan = percent((int) magCalibrator.maxSpan(), config.magCalibrationMinSpan);
        callback.onProgress(Math.min(byCount, bySpan));
        boolean ready = magCalibrator.sampleCount() >= config.magCalibrationSamples
                && magCalibrator.maxSpan() >= config.magCalibrationMinSpan;
        if (ready && !magReadyNotified) {
            magReadyNotified = true;
            callback.onMagReady();
        }
    }

    private static int percent(int value, int target) {
        if (target <= 0) {
            return 100;
        }
        return Math.min(100, (int) (100L * value / target));
    }
}
