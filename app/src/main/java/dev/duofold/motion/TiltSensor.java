
package dev.duofold.motion;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.view.WindowManager;

/** Relative pose model with automatic stable-pose recalibration. */
public final class TiltSensor implements SensorEventListener {

    public interface Listener {
        void onTilt(float progress, float forwardDegrees, float sideDegrees);
    }

    private static final long RECALIBRATION_DELAY_NS =
            10_000_000_000L;

    private static final float STABILITY_TOLERANCE =
            (float) Math.toRadians(1.5);

    private static final float DEAD_ZONE_DEGREES = 10.0f;

    private final SensorManager manager;
    private final WindowManager windows;
    private final Listener listener;
    private final float range;
    private final boolean includeForward;

    private final float[] device = new float[9];
    private final float[] screen = new float[9];
    private final float[] reference = new float[9];

    private Sensor attitude;
    private Sensor gyro;
    private Sensor acceleration;

    private boolean accelerationOnly;
    private boolean accelerationReady;
    private boolean calibrated;

    private float filteredForward;
    private float filteredSide;
    private float forwardRate;
    private float sideRate;

    private float accelerationX;
    private float accelerationY;
    private float accelerationZ;

    private float referenceAccelerationForward;
    private float referenceAccelerationSide;

    private long previous;
    private long rateTime;
    private long accelerationTime;

    private int lastRotation = -1;

    private long stableSince;
    private float stableAnchorForward;
    private float stableAnchorSide;
    private boolean stabilityTracking;

    public TiltSensor(
            Context context,
            float range,
            boolean includeForward,
            Listener listener
    ) {
        this.range = range;
        this.includeForward = includeForward;
        this.listener = listener;

        manager = context.getSystemService(SensorManager.class);
        windows = context.getSystemService(WindowManager.class);
    }

    public boolean start() {
        attitude = manager.getDefaultSensor(
                Sensor.TYPE_GAME_ROTATION_VECTOR
        );

        if (attitude == null) {
            attitude = manager.getDefaultSensor(
                    Sensor.TYPE_ROTATION_VECTOR
            );
        }

        if (attitude == null) {
            acceleration = manager.getDefaultSensor(
                    Sensor.TYPE_GRAVITY
            );

            if (acceleration == null) {
                acceleration = manager.getDefaultSensor(
                        Sensor.TYPE_ACCELEROMETER
                );
            }

            if (acceleration == null) {
                return false;
            }

            accelerationOnly = true;

            return manager.registerListener(
                    this,
                    acceleration,
                    16667
            );
        }

        boolean ok = manager.registerListener(
                this,
                attitude,
                8333
        );

        gyro = manager.getDefaultSensor(
                Sensor.TYPE_GYROSCOPE
        );

        if (ok && gyro != null) {
            manager.registerListener(
                    this,
                    gyro,
                    8333
            );
        }

        return ok;
    }

    public void calibrate() {
        calibrated = false;
        accelerationReady = false;
        stabilityTracking = false;

        filteredForward = 0;
        filteredSide = 0;
        previous = 0;
    }

    public void stop() {
        manager.unregisterListener(this);
        stabilityTracking = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int orientation = windows.getDefaultDisplay().getRotation();

        if (accelerationOnly && event.sensor == acceleration) {
            onAcceleration(event, orientation);
            return;
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            sideRate = switch (orientation) {
                case Surface.ROTATION_90 -> -event.values[0];
                case Surface.ROTATION_180 -> -event.values[1];
                case Surface.ROTATION_270 -> event.values[0];
                default -> event.values[1];
            };

            forwardRate = switch (orientation) {
                case Surface.ROTATION_90 -> event.values[1];
                case Surface.ROTATION_180 -> -event.values[0];
                case Surface.ROTATION_270 -> -event.values[1];
                default -> event.values[0];
            };

            rateTime = event.timestamp;
            return;
        }

        SensorManager.getRotationMatrixFromVector(
                device,
                event.values
        );

        int x = SensorManager.AXIS_X;
        int y = SensorManager.AXIS_Y;

        switch (orientation) {
            case Surface.ROTATION_90:
                x = SensorManager.AXIS_Y;
                y = SensorManager.AXIS_MINUS_X;
                break;

            case Surface.ROTATION_180:
                x = SensorManager.AXIS_MINUS_X;
                y = SensorManager.AXIS_MINUS_Y;
                break;

            case Surface.ROTATION_270:
                x = SensorManager.AXIS_MINUS_Y;
                y = SensorManager.AXIS_X;
                break;
        }

        SensorManager.remapCoordinateSystem(
                device,
                x,
                y,
                screen
        );

        if (!calibrated || orientation != lastRotation) {
            System.arraycopy(screen, 0, reference, 0, 9);

            calibrated = true;
            filteredForward = 0;
            filteredSide = 0;
            previous = event.timestamp;
            lastRotation = orientation;
            stabilityTracking = false;

            listener.onTilt(1, 0, 0);
            return;
        }

        float nx =
                reference[0] * screen[2]
                + reference[3] * screen[5]
                + reference[6] * screen[8];

        float nz =
                reference[2] * screen[2]
                + reference[5] * screen[5]
                + reference[8] * screen[8];

        float ny =
                reference[1] * screen[2]
                + reference[4] * screen[5]
                + reference[7] * screen[8];

        float side = (float) Math.atan2(nx, nz);
        float forward = -(float) Math.atan2(ny, nz);

        float[] measured = MotionAxes.resolve(
                side,
                forward,
                includeForward
        );

        float[] prediction =
                event.timestamp - rateTime < 100_000_000L
                ? MotionAxes.resolve(
                        sideRate * .04f,
                        forwardRate * .04f,
                        includeForward
                )
                : new float[]{0, 0};

        if (shouldAutoCalibrate(
                event.timestamp,
                measured[0],
                measured[1]
        )) {
            System.arraycopy(screen, 0, reference, 0, 9);

            filteredForward = 0;
            filteredSide = 0;
            previous = event.timestamp;

            listener.onTilt(1, 0, 0);
            return;
        }

        emit(
                event.timestamp,
                measured,
                prediction,
                .035f
        );
    }

    private void onAcceleration(
            SensorEvent event,
            int orientation
    ) {
        if (!accelerationReady) {
            accelerationX = event.values[0];
            accelerationY = event.values[1];
            accelerationZ = event.values[2];

            accelerationReady = true;
            accelerationTime = event.timestamp;
        } else {
            float dt = Math.max(
                    0,
                    Math.min(
                            .1f,
                            (event.timestamp - accelerationTime) / 1e9f
                    )
            );

            accelerationTime = event.timestamp;

            float response =
                    1 - (float) Math.exp(-dt / .08f);

            accelerationX += response
                    * (event.values[0] - accelerationX);

            accelerationY += response
                    * (event.values[1] - accelerationY);

            accelerationZ += response
                    * (event.values[2] - accelerationZ);
        }

        float[] angles = GravityTilt.angles(
                accelerationX,
                accelerationY,
                accelerationZ,
                orientation
        );

        if (!calibrated || orientation != lastRotation) {
            referenceAccelerationForward = angles[0];
            referenceAccelerationSide = angles[1];

            calibrated = true;
            filteredForward = 0;
            filteredSide = 0;
            previous = event.timestamp;
            lastRotation = orientation;
            stabilityTracking = false;

            listener.onTilt(1, 0, 0);
            return;
        }

        float forward = wrap(
                angles[0] - referenceAccelerationForward
        );

        float side = wrap(
                angles[1] - referenceAccelerationSide
        );

        float[] measured = MotionAxes.resolve(
                side,
                forward,
                includeForward
        );

        if (shouldAutoCalibrate(
                event.timestamp,
                measured[0],
                measured[1]
        )) {
            referenceAccelerationForward = angles[0];
            referenceAccelerationSide = angles[1];

            filteredForward = 0;
            filteredSide = 0;
            previous = event.timestamp;

            listener.onTilt(1, 0, 0);
            return;
        }

        emit(
                event.timestamp,
                measured,
                new float[]{0, 0},
                .08f
        );
    }

    private boolean shouldAutoCalibrate(
            long timestamp,
            float forward,
            float side
    ) {
        if (!stabilityTracking
                || Math.abs(wrap(forward - stableAnchorForward))
                        > STABILITY_TOLERANCE
                || Math.abs(wrap(side - stableAnchorSide))
                        > STABILITY_TOLERANCE) {

            stableAnchorForward = forward;
            stableAnchorSide = side;
            stableSince = timestamp;
            stabilityTracking = true;
            return false;
        }

        if (timestamp - stableSince >= RECALIBRATION_DELAY_NS) {
            stabilityTracking = false;
            return true;
        }

        return false;
    }

    private void emit(
            long timestamp,
            float[] measured,
            float[] prediction,
            float responseTime
    ) {
        float dt = Math.max(
                0,
                Math.min(
                        .1f,
                        (timestamp - previous) / 1e9f
                )
        );

        previous = timestamp;

        float response =
                1 - (float) Math.exp(-dt / responseTime);

        filteredForward += response * wrap(
                measured[0] + prediction[0] - filteredForward
        );

        filteredSide += response * wrap(
                measured[1] + prediction[1] - filteredSide
        );

        float limit = Math.max(
                15,
                Math.min(80, range)
        );

        float forwardDegrees =
                (float) Math.toDegrees(filteredForward);

        float sideDegrees =
                (float) Math.toDegrees(filteredSide);

        float magnitude = (float) Math.hypot(
                forwardDegrees,
                sideDegrees
        );

        if (magnitude > limit) {
            float scale = limit / magnitude;

            forwardDegrees *= scale;
            sideDegrees *= scale;
            magnitude = limit;
        }

        float effectiveMagnitude;

        if (magnitude <= DEAD_ZONE_DEGREES) {
            forwardDegrees = 0;
            sideDegrees = 0;
            effectiveMagnitude = 0;
        } else {
            effectiveMagnitude =
                    (magnitude - DEAD_ZONE_DEGREES) * limit
                    / (limit - DEAD_ZONE_DEGREES);

            float scale = effectiveMagnitude / magnitude;

            forwardDegrees *= scale;
            sideDegrees *= scale;
        }

        listener.onTilt(
                1 - effectiveMagnitude / limit,
                forwardDegrees,
                sideDegrees
        );
    }

    private static float wrap(float angle) {
        while (angle > Math.PI) {
            angle -= 2 * (float) Math.PI;
        }

        while (angle < -Math.PI) {
            angle += 2 * (float) Math.PI;
        }

        return angle;
    }

    @Override
    public void onAccuracyChanged(
            Sensor sensor,
            int accuracy
    ) {
        // No action required.
    }
}
