package io.github.zbz.airmouse;

/**
 * Magnetometer calibration via the "figure-8" method.
 *
 * <p>The phone is waved through all orientations while per-axis minimum and maximum values are
 * tracked. The hard/soft-iron correction is {@code corrected = (raw - offset) / scale} where
 * {@code offset = (max + min) / 2} and {@code scale = (max - min) / 2}.
 */
final class MagCalibrator {
    private final float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
    private final float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
    private int count;

    void addSample(float[] values) {
        for (int i = 0; i < 3; i++) {
            if (values[i] < min[i]) {
                min[i] = values[i];
            }
            if (values[i] > max[i]) {
                max[i] = values[i];
            }
        }
        count++;
    }

    int sampleCount() {
        return count;
    }

    /** Largest per-axis span seen so far; lets the UI gauge how much sphere has been covered. */
    float maxSpan() {
        float span = 0f;
        for (int i = 0; i < 3; i++) {
            if (max[i] > min[i]) {
                span = Math.max(span, max[i] - min[i]);
            }
        }
        return span;
    }

    float[] offset() {
        float[] offset = new float[3];
        for (int i = 0; i < 3; i++) {
            offset[i] = (max[i] + min[i]) / 2f;
        }
        return offset;
    }

    float[] scale() {
        float[] scale = new float[3];
        for (int i = 0; i < 3; i++) {
            float half = (max[i] - min[i]) / 2f;
            scale[i] = half == 0f ? 1f : half;
        }
        return scale;
    }
}
