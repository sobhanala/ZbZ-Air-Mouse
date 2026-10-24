package io.github.zbz.airmouse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Immutable correction parameters for the three raw sensors.
 *
 * <p>Gyroscope: {@code real = raw - bias}.
 * Accelerometer and magnetometer: {@code real = (raw - offset) / scale}.
 */
final class SensorCalibration {
    private final float[] gyroBias;
    private final float[] accelOffset;
    private final float[] accelScale;
    private final float[] magOffset;
    private final float[] magScale;

    SensorCalibration(
            float[] gyroBias,
            float[] accelOffset,
            float[] accelScale,
            float[] magOffset,
            float[] magScale) {
        this.gyroBias = gyroBias.clone();
        this.accelOffset = accelOffset.clone();
        this.accelScale = accelScale.clone();
        this.magOffset = magOffset.clone();
        this.magScale = magScale.clone();
    }

    static SensorCalibration identity() {
        return new SensorCalibration(
                new float[]{0f, 0f, 0f},
                new float[]{0f, 0f, 0f},
                new float[]{1f, 1f, 1f},
                new float[]{0f, 0f, 0f},
                new float[]{1f, 1f, 1f}
        );
    }

    void applyGyro(float[] raw, float[] out) {
        for (int i = 0; i < 3; i++) {
            out[i] = raw[i] - gyroBias[i];
        }
    }

    void applyAccel(float[] raw, float[] out) {
        for (int i = 0; i < 3; i++) {
            out[i] = safeDivide(raw[i] - accelOffset[i], accelScale[i]);
        }
    }

    void applyMag(float[] raw, float[] out) {
        for (int i = 0; i < 3; i++) {
            out[i] = safeDivide(raw[i] - magOffset[i], magScale[i]);
        }
    }

    private static float safeDivide(float value, float scale) {
        if (scale == 0f) {
            return value;
        }
        return value / scale;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("gyroBias", floatsToJson(gyroBias));
        json.put("accelOffset", floatsToJson(accelOffset));
        json.put("accelScale", floatsToJson(accelScale));
        json.put("magOffset", floatsToJson(magOffset));
        json.put("magScale", floatsToJson(magScale));
        return json;
    }

    static SensorCalibration fromJson(JSONObject json) throws JSONException {
        return new SensorCalibration(
                floatsFromJson(json.getJSONArray("gyroBias")),
                floatsFromJson(json.getJSONArray("accelOffset")),
                floatsFromJson(json.getJSONArray("accelScale")),
                floatsFromJson(json.getJSONArray("magOffset")),
                floatsFromJson(json.getJSONArray("magScale"))
        );
    }

    private static JSONArray floatsToJson(float[] values) throws JSONException {
        JSONArray array = new JSONArray();
        for (float value : values) {
            array.put((double) value);
        }
        return array;
    }

    private static float[] floatsFromJson(JSONArray array) throws JSONException {
        float[] values = new float[3];
        for (int i = 0; i < 3; i++) {
            values[i] = (float) array.getDouble(i);
        }
        return values;
    }
}
