package io.github.zbz.airmouse;

import android.content.Context;
import android.os.Trace;

/**
 * Turns fused phone orientation into mouse movement and detects click / scroll gestures.
 *
 * <p>Neutral pose: the phone is held flat with its screen parallel to the keyboard (screen up,
 * top pointing toward the laptop). With that pose the device axes map to motion as follows:
 * <ul>
 *   <li>Heading (yaw about the vertical axis) sweeps the cursor left/right (X).</li>
 *   <li>Elevation (tilting the top up/down) moves the cursor up/down (Y).</li>
 *   <li>A twist about the long axis past a threshold is a left click.</li>
 *   <li>A fast translation along the long axis scrolls (forward = up, back = down).</li>
 * </ul>
 * Movement is emitted as relative {@code dx}/{@code dy} from the change in orientation, so the
 * cursor does not drift while the phone is held still.
 */
final class AirMouseOrientationEngine implements RawSensorReader.Listener {

    interface Listener {
        void onMove(float dx, float dy);

        void onClick(String button);

        void onScroll(int amount);
    }

    private static final float GRAVITY = 9.80665f;
    private static final float NS_TO_S = 1e-9f;
    private static final float RAD_TO_DEG = (float) (180.0 / Math.PI);

    private final RawSensorReader reader;
    private final MadgwickAhrs filter;
    private final SensorCalibration calibration;
    private final AirMouseConfig config;
    private final Listener listener;
    private final float gainX;
    private final float gainY;

    private final float[] accelRaw = new float[3];
    private final float[] accelCal = new float[3];
    private final float[] magRaw = new float[3];
    private final float[] magCal = new float[3];
    private final float[] gyroCal = new float[3];
    private final float[] gravityDir = new float[3];
    private boolean haveAccel;
    private boolean haveMag;

    private long lastGyroNanos;
    private boolean haveOrientation;
    private float lastHeadingRad;
    private float lastElevationRad;

    private boolean clickArmed = true;
    private long lastClickMillis;
    private long lastScrollMillis;

    AirMouseOrientationEngine(
            Context context,
            SensorCalibration calibration,
            AirMouseConfig config,
            int screenWidth,
            int screenHeight,
            Listener listener) {
        this.reader = new RawSensorReader(context, this);
        this.calibration = calibration;
        this.config = config;
        this.listener = listener;
        this.filter = new MadgwickAhrs(config.madgwickBeta);
        int width = screenWidth > 0 ? screenWidth : config.fallbackAspectWidth;
        int height = screenHeight > 0 ? screenHeight : config.fallbackAspectHeight;
        float gainScale = config.motionGainPercent / 100f;
        // A 180-degree (semicircle) sweep spans the full screen dimension.
        this.gainX = (width / 180f) * gainScale;
        this.gainY = (height / 180f) * gainScale;
    }

    boolean hasRequiredSensors() {
        return reader.hasRequiredSensors();
    }

    void start() {
        haveAccel = false;
        haveMag = false;
        haveOrientation = false;
        clickArmed = true;
        lastGyroNanos = 0;
        filter.reset();
        reader.start();
    }

    void stop() {
        reader.stop();
    }

    @Override
    public void onAccel(float[] values) {
        Trace.beginSection("airmouse.calibrate.accel");
        try {
            calibration.applyAccel(values, accelCal);
            accelRaw[0] = accelCal[0];
            accelRaw[1] = accelCal[1];
            accelRaw[2] = accelCal[2];
            haveAccel = true;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onMag(float[] values) {
        Trace.beginSection("airmouse.calibrate.mag");
        try {
            calibration.applyMag(values, magCal);
            magRaw[0] = magCal[0];
            magRaw[1] = magCal[1];
            magRaw[2] = magCal[2];
            haveMag = true;
        } finally {
            Trace.endSection();
        }
    }

    @Override
    public void onGyro(float[] values, long timestampNanos) {
        Trace.beginSection("airmouse.pipeline.gyro");
        try {
            if (!haveAccel) {
                return;
            }
            if (lastGyroNanos == 0) {
                lastGyroNanos = timestampNanos;
                return;
            }
            float dt = (timestampNanos - lastGyroNanos) * NS_TO_S;
            lastGyroNanos = timestampNanos;
            if (dt <= 0f || dt > 0.2f) {
                return;
            }

            Trace.beginSection("airmouse.calibrate.gyro");
            try {
                calibration.applyGyro(values, gyroCal);
            } finally {
                Trace.endSection();
            }

            float mx = haveMag ? magRaw[0] : 0f;
            float my = haveMag ? magRaw[1] : 0f;
            float mz = haveMag ? magRaw[2] : 0f;
            Trace.beginSection("airmouse.filter_update");
            try {
                filter.update(
                        gyroCal[0], gyroCal[1], gyroCal[2],
                        accelRaw[0], accelRaw[1], accelRaw[2],
                        mx, my, mz,
                        dt);
            } finally {
                Trace.endSection();
            }

            emitMovement();
            detectScroll();
            detectClick();
        } finally {
            Trace.endSection();
        }
    }

    private void emitMovement() {
        Trace.beginSection("airmouse.emit_move");
        try {
            float heading = filter.getYawRad();
            float elevation = filter.getRollRad();
            if (!haveOrientation) {
                haveOrientation = true;
                lastHeadingRad = heading;
                lastElevationRad = elevation;
                return;
            }
            float dHeading = normalizeAngle(heading - lastHeadingRad);
            float dElevation = normalizeAngle(elevation - lastElevationRad);
            lastHeadingRad = heading;
            lastElevationRad = elevation;

            float dx = dHeading * RAD_TO_DEG * gainX;
            float dy = -dElevation * RAD_TO_DEG * gainY;
            if (dx != 0f || dy != 0f) {
                listener.onMove(dx, dy);
            }
        } finally {
            Trace.endSection();
        }
    }

    private void detectClick() {
        Trace.beginSection("airmouse.detect_click");
        try {
            float twistDeg = filter.getPitchRad() * RAD_TO_DEG * config.clickTwistSign;
            long now = System.currentTimeMillis();
            if (clickArmed
                    && twistDeg >= config.clickTwistThresholdDeg
                    && now - lastClickMillis >= config.clickMinIntervalMs) {
                clickArmed = false;
                lastClickMillis = now;
                listener.onClick(AirMouseProtocol.BUTTON_LEFT);
            } else if (!clickArmed && twistDeg <= config.clickReleaseDeg) {
                clickArmed = true;
            }
        } finally {
            Trace.endSection();
        }
    }

    private void detectScroll() {
        Trace.beginSection("airmouse.detect_scroll");
        try {
            filter.getGravityDirection(gravityDir);
            // Linear acceleration along the long (Y) axis = measured minus gravity reaction.
            float linY = accelRaw[1] - gravityDir[1] * GRAVITY;
            if (Math.abs(linY) < config.scrollAccelThreshold) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastScrollMillis < config.scrollMinIntervalMs) {
                return;
            }
            lastScrollMillis = now;
            int amount = (linY * config.scrollDirectionSign) > 0f ? 1 : -1;
            listener.onScroll(amount);
        } finally {
            Trace.endSection();
        }
    }

    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) {
            angle -= 2f * (float) Math.PI;
        }
        while (angle < -Math.PI) {
            angle += 2f * (float) Math.PI;
        }
        return angle;
    }
}
