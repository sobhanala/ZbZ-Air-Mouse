package io.github.zbz.airmouse;

/**
 * Six-position accelerometer calibration.
 *
 * <p>The phone is held in six orientations so that each axis reads {@code +g} once and {@code -g}
 * once. For every axis we then build the linear correction {@code real = (raw - offset) / scale}
 * where {@code offset = (raw@+g + raw@-g) / 2} and {@code scale = (raw@+g - raw@-g) / (2g)}.
 */
final class AccelCalibrator {

    /** Position of the phone; the named axis points up (against gravity). */
    enum Position {
        X_UP(0, true),
        X_DOWN(0, false),
        Y_UP(1, true),
        Y_DOWN(1, false),
        Z_UP(2, true),
        Z_DOWN(2, false);

        final int axis;
        final boolean positive;

        Position(int axis, boolean positive) {
            this.axis = axis;
            this.positive = positive;
        }
    }

    private static final float GRAVITY = 9.80665f;

    private final double[] sums = new double[3];
    private final float[] positiveReading = new float[3];
    private final float[] negativeReading = new float[3];
    private final boolean[] positiveCaptured = new boolean[3];
    private final boolean[] negativeCaptured = new boolean[3];
    private int count;

    void addSample(float[] values) {
        sums[0] += values[0];
        sums[1] += values[1];
        sums[2] += values[2];
        count++;
    }

    int sampleCount() {
        return count;
    }

    /** Stores the averaged reading for the current position and clears the running sums. */
    void capturePosition(Position position) {
        if (count == 0) {
            return;
        }
        float axisAverage = (float) (sums[position.axis] / count);
        if (position.positive) {
            positiveReading[position.axis] = axisAverage;
            positiveCaptured[position.axis] = true;
        } else {
            negativeReading[position.axis] = axisAverage;
            negativeCaptured[position.axis] = true;
        }
        sums[0] = 0;
        sums[1] = 0;
        sums[2] = 0;
        count = 0;
    }

    boolean isComplete() {
        for (int i = 0; i < 3; i++) {
            if (!positiveCaptured[i] || !negativeCaptured[i]) {
                return false;
            }
        }
        return true;
    }

    float[] offset() {
        float[] offset = new float[3];
        for (int i = 0; i < 3; i++) {
            offset[i] = (positiveReading[i] + negativeReading[i]) / 2f;
        }
        return offset;
    }

    float[] scale() {
        float[] scale = new float[3];
        for (int i = 0; i < 3; i++) {
            float span = positiveReading[i] - negativeReading[i];
            scale[i] = span / (2f * GRAVITY);
            if (scale[i] == 0f) {
                scale[i] = 1f;
            }
        }
        return scale;
    }
}
