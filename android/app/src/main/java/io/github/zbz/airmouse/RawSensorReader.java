package io.github.zbz.airmouse;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Trace;

/**
 * Registers the raw (non-fused, non-pre-calibrated) motion sensors and forwards their values.
 *
 * <p>Uses {@code TYPE_ACCELEROMETER}, {@code TYPE_GYROSCOPE_UNCALIBRATED} and
 * {@code TYPE_MAGNETIC_FIELD_UNCALIBRATED} so that bias/offset/scale correction is done by our
 * own calibration rather than by Android's fused/pre-calibrated sensors. Sampled at
 * {@code SENSOR_DELAY_GAME} (~50 Hz), which needs no high-sampling-rate permission.
 */
final class RawSensorReader implements SensorEventListener {

    interface Listener {
        void onAccel(float[] values);

        void onGyro(float[] values, long timestampNanos);

        void onMag(float[] values);
    }

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Sensor gyroscope;
    private final Sensor magnetometer;
    private final Listener listener;
    private final float[] accelValues = new float[3];
    private final float[] gyroValues = new float[3];
    private final float[] magValues = new float[3];
    private boolean registered;

    RawSensorReader(Context context, Listener listener) {
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
        this.accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        this.gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE_UNCALIBRATED);
        this.magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
    }

    boolean hasRequiredSensors() {
        return accelerometer != null && gyroscope != null && magnetometer != null;
    }

    void start() {
        if (registered) {
            return;
        }
        registered = true;
        register(accelerometer);
        register(gyroscope);
        register(magnetometer);
    }

    void stop() {
        if (!registered) {
            return;
        }
        registered = false;
        sensorManager.unregisterListener(this);
    }

    private void register(Sensor sensor) {
        if (sensor != null) {
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                Trace.beginSection("airmouse.sensor.accel");
                try {
                    copy(event.values, accelValues);
                    listener.onAccel(accelValues);
                } finally {
                    Trace.endSection();
                }
                break;
            case Sensor.TYPE_GYROSCOPE_UNCALIBRATED:
                Trace.beginSection("airmouse.sensor.gyro");
                try {
                    copy(event.values, gyroValues);
                    listener.onGyro(gyroValues, event.timestamp);
                } finally {
                    Trace.endSection();
                }
                break;
            case Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED:
                Trace.beginSection("airmouse.sensor.mag");
                try {
                    copy(event.values, magValues);
                    listener.onMag(magValues);
                } finally {
                    Trace.endSection();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Raw sensors are intentionally used; accuracy hints are ignored.
    }

    private static void copy(float[] source, float[] dest) {
        dest[0] = source[0];
        dest[1] = source[1];
        dest[2] = source[2];
    }
}
