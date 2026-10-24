package io.github.zbz.airmouse;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Persists {@link SensorCalibration} across app launches via {@link SharedPreferences}. */
final class CalibrationStore {
    private static final String PREFS_NAME = "air_mouse_calibration";
    private static final String KEY_CALIBRATION = "calibration";

    private final SharedPreferences prefs;

    CalibrationStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    boolean hasCalibration() {
        return prefs.contains(KEY_CALIBRATION);
    }

    void save(SensorCalibration calibration) {
        try {
            prefs.edit().putString(KEY_CALIBRATION, calibration.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    SensorCalibration load() {
        String stored = prefs.getString(KEY_CALIBRATION, null);
        if (stored == null) {
            return SensorCalibration.identity();
        }
        try {
            return SensorCalibration.fromJson(new JSONObject(stored));
        } catch (JSONException exc) {
            return SensorCalibration.identity();
        }
    }

    void clear() {
        prefs.edit().remove(KEY_CALIBRATION).apply();
    }
}
