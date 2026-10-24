package io.github.zbz.airmouse;

/**
 * Madgwick AHRS sensor-fusion filter (gradient-descent orientation filter).
 *
 * <p>Java port of Sebastian Madgwick's MARG/IMU algorithm described in
 * {@code Materials/3- Madgwick Orientation Filter.html}. State is the orientation quaternion
 * {@code (q0..q3) = (w, x, y, z)}; {@code beta} trades gyro integration against accel/mag
 * correction. The sample period is supplied per update so it tracks the real sensor rate.
 */
final class MadgwickAhrs {
    private final float beta;
    private float q0 = 1f;
    private float q1 = 0f;
    private float q2 = 0f;
    private float q3 = 0f;

    MadgwickAhrs(float beta) {
        this.beta = beta;
    }

    void reset() {
        q0 = 1f;
        q1 = 0f;
        q2 = 0f;
        q3 = 0f;
    }

    void update(
            float gx, float gy, float gz,
            float ax, float ay, float az,
            float mx, float my, float mz,
            float dt) {
        if (mx == 0f && my == 0f && mz == 0f) {
            updateImu(gx, gy, gz, ax, ay, az, dt);
            return;
        }

        float qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
        float qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
        float qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        if (!(ax == 0f && ay == 0f && az == 0f)) {
            float recipNorm = invSqrt(ax * ax + ay * ay + az * az);
            ax *= recipNorm;
            ay *= recipNorm;
            az *= recipNorm;

            recipNorm = invSqrt(mx * mx + my * my + mz * mz);
            mx *= recipNorm;
            my *= recipNorm;
            mz *= recipNorm;

            float _2q0mx = 2f * q0 * mx;
            float _2q0my = 2f * q0 * my;
            float _2q0mz = 2f * q0 * mz;
            float _2q1mx = 2f * q1 * mx;
            float _2q0 = 2f * q0;
            float _2q1 = 2f * q1;
            float _2q2 = 2f * q2;
            float _2q3 = 2f * q3;
            float _2q0q2 = 2f * q0 * q2;
            float _2q2q3 = 2f * q2 * q3;
            float q0q0 = q0 * q0;
            float q0q1 = q0 * q1;
            float q0q2 = q0 * q2;
            float q0q3 = q0 * q3;
            float q1q1 = q1 * q1;
            float q1q2 = q1 * q2;
            float q1q3 = q1 * q3;
            float q2q2 = q2 * q2;
            float q2q3 = q2 * q3;
            float q3q3 = q3 * q3;

            float hx = mx * q0q0 - _2q0my * q3 + _2q0mz * q2 + mx * q1q1
                    + _2q1 * my * q2 + _2q1 * mz * q3 - mx * q2q2 - mx * q3q3;
            float hy = _2q0mx * q3 + my * q0q0 - _2q0mz * q1 + _2q1mx * q2
                    - my * q1q1 + my * q2q2 + _2q2 * mz * q3 - my * q3q3;
            float _2bx = (float) Math.sqrt(hx * hx + hy * hy);
            float _2bz = -_2q0mx * q2 + _2q0my * q1 + mz * q0q0 + _2q1mx * q3
                    - mz * q1q1 + _2q2 * my * q3 - mz * q2q2 + mz * q3q3;
            float _4bx = 2f * _2bx;
            float _4bz = 2f * _2bz;

            float s0 = -_2q2 * (2f * q1q3 - _2q0q2 - ax) + _2q1 * (2f * q0q1 + _2q2q3 - ay)
                    - _2bz * q2 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (-_2bx * q3 + _2bz * q1) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + _2bx * q2 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s1 = _2q3 * (2f * q1q3 - _2q0q2 - ax) + _2q0 * (2f * q0q1 + _2q2q3 - ay)
                    - 4f * q1 * (1f - 2f * q1q1 - 2f * q2q2 - az)
                    + _2bz * q3 * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (_2bx * q2 + _2bz * q0) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + (_2bx * q3 - _4bz * q1) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s2 = -_2q0 * (2f * q1q3 - _2q0q2 - ax) + _2q3 * (2f * q0q1 + _2q2q3 - ay)
                    - 4f * q2 * (1f - 2f * q1q1 - 2f * q2q2 - az)
                    + (-_4bx * q2 - _2bz * q0) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (_2bx * q1 + _2bz * q3) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + (_2bx * q0 - _4bz * q2) * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);
            float s3 = _2q1 * (2f * q1q3 - _2q0q2 - ax) + _2q2 * (2f * q0q1 + _2q2q3 - ay)
                    + (-_4bx * q3 + _2bz * q1) * (_2bx * (0.5f - q2q2 - q3q3) + _2bz * (q1q3 - q0q2) - mx)
                    + (-_2bx * q0 + _2bz * q2) * (_2bx * (q1q2 - q0q3) + _2bz * (q0q1 + q2q3) - my)
                    + _2bx * q1 * (_2bx * (q0q2 + q1q3) + _2bz * (0.5f - q1q1 - q2q2) - mz);

            float norm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
            s0 *= norm;
            s1 *= norm;
            s2 *= norm;
            s3 *= norm;

            qDot1 -= beta * s0;
            qDot2 -= beta * s1;
            qDot3 -= beta * s2;
            qDot4 -= beta * s3;
        }

        integrate(qDot1, qDot2, qDot3, qDot4, dt);
    }

    void updateImu(
            float gx, float gy, float gz,
            float ax, float ay, float az,
            float dt) {
        float qDot1 = 0.5f * (-q1 * gx - q2 * gy - q3 * gz);
        float qDot2 = 0.5f * (q0 * gx + q2 * gz - q3 * gy);
        float qDot3 = 0.5f * (q0 * gy - q1 * gz + q3 * gx);
        float qDot4 = 0.5f * (q0 * gz + q1 * gy - q2 * gx);

        if (!(ax == 0f && ay == 0f && az == 0f)) {
            float recipNorm = invSqrt(ax * ax + ay * ay + az * az);
            ax *= recipNorm;
            ay *= recipNorm;
            az *= recipNorm;

            float _2q0 = 2f * q0;
            float _2q1 = 2f * q1;
            float _2q2 = 2f * q2;
            float _2q3 = 2f * q3;
            float _4q0 = 4f * q0;
            float _4q1 = 4f * q1;
            float _4q2 = 4f * q2;
            float _8q1 = 8f * q1;
            float _8q2 = 8f * q2;
            float q0q0 = q0 * q0;
            float q1q1 = q1 * q1;
            float q2q2 = q2 * q2;
            float q3q3 = q3 * q3;

            float s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay;
            float s1 = _4q1 * q3q3 - _2q3 * ax + 4f * q0q0 * q1 - _2q0 * ay
                    - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az;
            float s2 = 4f * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay
                    - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az;
            float s3 = 4f * q1q1 * q3 - _2q1 * ax + 4f * q2q2 * q3 - _2q2 * ay;

            float norm = invSqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3);
            s0 *= norm;
            s1 *= norm;
            s2 *= norm;
            s3 *= norm;

            qDot1 -= beta * s0;
            qDot2 -= beta * s1;
            qDot3 -= beta * s2;
            qDot4 -= beta * s3;
        }

        integrate(qDot1, qDot2, qDot3, qDot4, dt);
    }

    private void integrate(float qDot1, float qDot2, float qDot3, float qDot4, float dt) {
        q0 += qDot1 * dt;
        q1 += qDot2 * dt;
        q2 += qDot3 * dt;
        q3 += qDot4 * dt;
        float recipNorm = invSqrt(q0 * q0 + q1 * q1 + q2 * q2 + q3 * q3);
        q0 *= recipNorm;
        q1 *= recipNorm;
        q2 *= recipNorm;
        q3 *= recipNorm;
    }

    /** Unit gravity direction expressed in the device frame, from the current orientation. */
    void getGravityDirection(float[] out) {
        out[0] = 2f * (q1 * q3 - q0 * q2);
        out[1] = 2f * (q0 * q1 + q2 * q3);
        out[2] = q0 * q0 - q1 * q1 - q2 * q2 + q3 * q3;
    }

    /** Rotation about the device X axis (radians). */
    float getRollRad() {
        return (float) Math.atan2(2f * (q0 * q1 + q2 * q3), 1f - 2f * (q1 * q1 + q2 * q2));
    }

    /** Rotation about the device Y axis (radians). */
    float getPitchRad() {
        float sinp = 2f * (q0 * q2 - q3 * q1);
        if (sinp > 1f) {
            sinp = 1f;
        } else if (sinp < -1f) {
            sinp = -1f;
        }
        return (float) Math.asin(sinp);
    }

    /** Rotation about the device Z axis / heading (radians). */
    float getYawRad() {
        return (float) Math.atan2(2f * (q0 * q3 + q1 * q2), 1f - 2f * (q2 * q2 + q3 * q3));
    }

    private static float invSqrt(float x) {
        if (x <= 0f) {
            return 0f;
        }
        return (float) (1.0 / Math.sqrt(x));
    }
}
