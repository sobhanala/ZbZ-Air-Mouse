package io.github.zbz.airmouse;

/** Estimates gyroscope bias by averaging samples taken while the phone is stationary. */
final class GyroCalibrator {
    private final double[] sums = new double[3];
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

    void reset() {
        sums[0] = 0;
        sums[1] = 0;
        sums[2] = 0;
        count = 0;
    }

    float[] bias() {
        if (count == 0) {
            return new float[]{0f, 0f, 0f};
        }
        return new float[]{
                (float) (sums[0] / count),
                (float) (sums[1] / count),
                (float) (sums[2] / count)
        };
    }
}
