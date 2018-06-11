package com.epicgames.ue4;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import com.epicgames.ue4.runnables.SendSensorDataRunnable;
import com.epicgames.ue4.runnables.SendShakingStartedRunnable;
import com.epicgames.ue4.runnables.SendShakingStoppedRunnable;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings({"InstanceVariableOfConcreteClass", "ClassWithoutLogger", "PublicConstructor", "ClassWithTooManyFields", "StaticVariableOfConcreteClass", "PublicMethodWithoutLogging"})
public final class MainActivity extends Activity implements SensorEventListener {
    public static final String TAG = "Foo";
    public static final int MAX_VIBRATION_TIME = 99999;
    public static final int VIBRATION_DELAY = 650;
    public static final Lock sendData = new ReentrantLock();
    public static final Lock rotationsLock = new ReentrantLock();
    public static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);
    public static final byte COMTP_SENSOR_DATA = 1;
    public static final byte COMTP_SHAKING_STARTED = 2;
    public static final byte COMTP_SHAKING_STOPPED = 3;
    public static final int LOW_SHAKING_SENSIVITY = 11;
    public static final int MEDIUM_SHAKING_SENSIVITY = 15;
    public static final int HIGH_SHAKING_SENSIVITY = 19;
    public static final InetAddress INET_ADDRESS;
    public static final int PORT = 55056;
    private static final float[] X_VECTOR = {1, 0, 0};
    private static final float[] Y_VECTOR = {0, 1, 0};
    private static final float[] Z_VECTOR = {0, 0, 1};
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 25 times per 1000 millisecond (= 25 times per second)
    private static final float LOW_PASS_FILTER = 0.8f;
    private static final float FROM_RADIANS_TO_DEGREES = 180.f / (float) Math.PI;
    private static final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>(15));
    private static final Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private static final Acceleration acceleration = new Acceleration(0, 0, 0);
    private static final String IP_ADDRESS = "192.168.1.13";
    private static volatile boolean cancelInfiniteVibration;
    private static boolean newTouchThisSample;
    private static Touch touch = NO_TOUCH;
    private static boolean doVibrateWhileShaking = true;
    private static volatile boolean forceVibration;
    private static int shakingSensivity = HIGH_SHAKING_SENSIVITY;
    private static DatagramSocket datagramSocket;

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (final UnknownHostException e) {
            e.printStackTrace();
        }
        INET_ADDRESS = tmp;
    }

    private final ShakingQueue queue = new ShakingQueue();
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private long shakeVibrationStartTime;

    /**
     * Returns true if the device is currently accelerating.
     */
    private static boolean isAccelerating(final float[] values) {
        final float ax = values[0];
        final float ay = values[1];
        final float az = values[2];

        final double magnitudeSquared = ax * ax + ay * ay + az * az;
        return magnitudeSquared > shakingSensivity * shakingSensivity;
    }

    public static boolean isDoVibrateWhileShaking() {
        return doVibrateWhileShaking;
    }

    public static void setDoVibrateWhileShaking(boolean doVibrateWhileShaking) {
        MainActivity.doVibrateWhileShaking = doVibrateWhileShaking;
    }

    public static boolean isCancelInfiniteVibration() {
        return cancelInfiniteVibration;
    }

    public static void setCancelInfiniteVibration(boolean cancelInfiniteVibration) {
        MainActivity.cancelInfiniteVibration = cancelInfiniteVibration;
    }

    public static void setForceVibration(boolean forceVibration) {
        MainActivity.forceVibration = forceVibration;
    }

    public static void resetTouch() {
        touch = NO_TOUCH;
    }

    public static void resetNewTouchThisSample() {
        newTouchThisSample = false;
    }

    public static boolean isNewTouchThisSample() {
        return newTouchThisSample;
    }

    private static void updateAcceleration(final float[] values) {
        accelerationWithGravity.x = LOW_PASS_FILTER * accelerationWithGravity.x + (1 - LOW_PASS_FILTER) * values[0];
        accelerationWithGravity.y = LOW_PASS_FILTER * accelerationWithGravity.y + (1 - LOW_PASS_FILTER) * values[1];
        accelerationWithGravity.z = LOW_PASS_FILTER * accelerationWithGravity.z + (1 - LOW_PASS_FILTER) * values[2];

        acceleration.x = LOW_PASS_FILTER * acceleration.x + (1 - LOW_PASS_FILTER) * (values[0] - accelerationWithGravity.x);
        acceleration.y = LOW_PASS_FILTER * acceleration.y + (1 - LOW_PASS_FILTER) * (values[1] - accelerationWithGravity.y);
        acceleration.z = LOW_PASS_FILTER * acceleration.z + (1 - LOW_PASS_FILTER) * (values[2] - accelerationWithGravity.z);
    }

    public static List<Rotation> getRotations() {
        return new ArrayList<>(rotations);
    }

    public static Acceleration getAcceleration() {
        return acceleration;
    }

    public static Touch getTouch() {
        return touch;
    }

    public static void setShakingSensivity(int shakingSensivity) {
        MainActivity.shakingSensivity = shakingSensivity;
    }

    public static DatagramSocket getDatagramSocket() {
        return datagramSocket;
    }

    public static void resetRotations() {
        rotations.clear();
    }

    private static float[] multiply(final float[][] a, final float[] x) {
        final int m = a.length;
        final int n = a[0].length;
        if (x.length != n) {
            throw new RuntimeException("Illegal matrix dimensions.");
        }
        final float[] y = new float[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                y[i] += a[i][j] * x[j];
            }
        }
        return y;
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        final ImageView imageView = (ImageView) findViewById(R.id.imageView);
        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent motionEvent) {
                if (isNewTouchThisSample() && motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    touch = NO_TOUCH;
                    newTouchThisSample = false;
                    return true;
                } else if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    // Do nothing
                    return true;
                } else {
                    touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), (byte) (motionEvent.getAction() == MotionEvent.ACTION_UP ? 1 : 0));
                    Log.d(TAG, "Touch registered");
                    newTouchThisSample = true;
                    return true;
                }
            }
        });

        try {
            this.datagramSocket = new DatagramSocket(PORT);
            this.datagramSocket.setBroadcast(true);
        } catch (final SocketException e) {
            e.printStackTrace();
        }

        ThreadManager.scheduleAtFixedRate(new SendSensorDataRunnable(), SEND_TIME_THRESHOLD, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onSensorChanged(final SensorEvent sensorEvent) {
        final int sensorType = sensorEvent.sensor.getType();
        if (sensorType == Sensor.TYPE_ROTATION_VECTOR) {
            onGyroscopeChanged(sensorEvent.values);
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            onAccelerometerChanged(sensorEvent.values, sensorEvent.timestamp);
        }
    }

    private void onGyroscopeChanged(final float[] values) {
        final float[] rotation = new float[3];
        System.arraycopy(values, 0, rotation, 0, 3);
        storeRotation(rotation);
    }

    private void onAccelerometerChanged(final float[] values, final long timestamp) {
        updateAcceleration(values);
        checkForShaking(values, timestamp);
    }

    private void checkForShaking(final float[] values, final long timestamp) {
        final boolean accelerating = isAccelerating(values);
        queue.add(timestamp, accelerating);

        if (queue.isShaking()) {
            queue.clear();
            Log.d(TAG, "Shaked!!!");

            communicateShakingStarted();

            setShakeVibrationTimer();

            executeVibrationByShaking();
            shakeVibrationStartTime = System.currentTimeMillis();
        }
    }

    private void communicateShakingStarted() {
        if (shakeVibrationStartTime == 0L) {
            ThreadManager.execute(new SendShakingStartedRunnable(datagramSocket));
        }
    }

    private void setShakeVibrationTimer() {
        vibrationTimer.cancel();
        vibrationTimer = new Timer("Terminates vibration by shaking");
        vibrationTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Log.d(TAG, "Cancelling shaking");
                if (!forceVibration) {
                    vibrator.cancel();
                }
                shakeVibrationStartTime = 0L;
                ThreadManager.execute(new SendShakingStoppedRunnable(datagramSocket));
            }
        }, VIBRATION_DELAY);
    }

    private void executeVibrationByShaking() {
        if (!forceVibration && isDoVibrateWhileShaking()) {
            if (System.currentTimeMillis() > shakeVibrationStartTime + MAX_VIBRATION_TIME) {
                vibrator.vibrate(MAX_VIBRATION_TIME);
            }
        }
    }

    @Override
    public void onAccuracyChanged(final Sensor sensor, final int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "resumed");
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_UI);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        vibrator.cancel();
        setCancelInfiniteVibration(true);
        Log.d(TAG, "paused");
        super.onPause();
    }

    private float[] quaternion_mult(final float[] q, final float[] r) {
        return new float[]{
                r[0] * q[0] - r[1] * q[1] - r[2] * q[2] - r[3] * q[3],
                r[0] * q[1] + r[1] * q[0] - r[2] * q[3] + r[3] * q[2],
                r[0] * q[2] + r[1] * q[3] + r[2] * q[0] - r[3] * q[1],
                r[0] * q[3] - r[1] * q[2] + r[2] * q[1] + r[3] * q[0]};
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private void storeRotation(final float[] values) {
        final float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        final float[][] rotMat2 = new float[3][3];
        rotMat2[0][0] = rotMat[0];
        rotMat2[0][1] = rotMat[1];
        rotMat2[0][2] = rotMat[2];
        rotMat2[1][0] = rotMat[3];
        rotMat2[1][1] = rotMat[4];
        rotMat2[1][2] = rotMat[5];
        rotMat2[2][0] = rotMat[6];
        rotMat2[2][1] = rotMat[7];
        rotMat2[2][2] = rotMat[8];




        /*


        Get X-coordinate of X-vector
        If Z-coordinate of Z-vector is negative: Y-coordinate of X-vector *= -1
        Angle = atan2(x, y)


         */






        Log.d(TAG, String.format("rotMat2: %.2f, %.2f, %.2f",
                rotMat[0],
                rotMat[3],
                rotMat[6])
        );

        // 1

        final float[] transformedXVector = multiply(rotMat2, X_VECTOR);
        Log.d(TAG, String.format("transformedXVector: %.2f, %.2f, %.2f",
                transformedXVector[0],
                transformedXVector[1],
                transformedXVector[2])
        );
        final float[] transformedYVector = multiply(rotMat2, Y_VECTOR);
        Log.d(TAG, String.format("transformedYVector: %.2f, %.2f, %.2f",
                transformedYVector[0],
                transformedYVector[1],
                transformedYVector[2])
        );
        final float[] transformedZVector = multiply(rotMat2, Z_VECTOR);
        Log.d(TAG, String.format("transformedZVector: %.2f, %.2f, %.2f",
                transformedZVector[0],
                transformedZVector[1],
                transformedZVector[2])
        );

        //

        float transformedUpVectorXY_Angle = (float) Math.atan2(transformedZVector[0], transformedZVector[1]);
        Log.d(TAG, String.format("transformedUpVectorXY_Angle: %f", transformedUpVectorXY_Angle));
        final float angleCos = (float) Math.cos(transformedUpVectorXY_Angle);
        final float angleSin = (float) Math.sin(transformedUpVectorXY_Angle);
        final double[] compensatedTransformedUpVector = {
                transformedZVector[0] * angleCos - transformedZVector[1] * angleSin,
                transformedZVector[0] * angleSin + transformedZVector[1] * angleCos,
                transformedZVector[2]
        };
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedUpVectorLength = Math.sqrt(
                compensatedTransformedUpVector[1] * compensatedTransformedUpVector[1]
                        + compensatedTransformedUpVector[2] * compensatedTransformedUpVector[2]
        );
        final double[] normalizedCompensatedTransformedUpVector = {
                0,
                compensatedTransformedUpVector[1] / compensatedTransformedUpVectorLength,
                compensatedTransformedUpVector[2] / compensatedTransformedUpVectorLength
        };

        // 2

        Log.d(TAG, String.format("normalizedCompensatedTransformedUpVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedUpVector[0],
                normalizedCompensatedTransformedUpVector[1],
                normalizedCompensatedTransformedUpVector[2])
        );

        //

        final double transformedUpVectorZZ_Angle = Math.acos(transformedZVector[2] / Math.sqrt(transformedZVector[0] * transformedZVector[0] + transformedZVector[1] * transformedZVector[1] + transformedZVector[2] * transformedZVector[2]));
        Log.d(TAG, String.format("angle: %f", transformedUpVectorZZ_Angle));
        final float[] perpendicularTransformedUpVector = {
                -transformedZVector[1],
                transformedZVector[0],
                0
        };
        final double perpendicularTransformedUpVector_Length = Math.sqrt(perpendicularTransformedUpVector[0] * perpendicularTransformedUpVector[0] + perpendicularTransformedUpVector[1] * perpendicularTransformedUpVector[1]);
        perpendicularTransformedUpVector[0] /= perpendicularTransformedUpVector_Length;
        perpendicularTransformedUpVector[1] /= perpendicularTransformedUpVector_Length;
        Log.d(TAG, String.format("perpendicular: %.2f, %.2f, %.2f", perpendicularTransformedUpVector[0], perpendicularTransformedUpVector[1], perpendicularTransformedUpVector[2]));

        final float cosTheta = (float) Math.cos(-transformedUpVectorZZ_Angle);
        final float cosThetaInv = 1 - cosTheta;
        final float sinTheta = (float) Math.sin(-transformedUpVectorZZ_Angle);

        final float[] u = perpendicularTransformedUpVector;
        final float[] u2 = {u[0] * u[0], u[1] * u[1], u[2] * u[2]};

        final float[][] rotMat3 = {
                {cosTheta + u2[0] * cosThetaInv, u[0] * u[1] * cosThetaInv - u[2] * sinTheta, u[0] * u[2] * cosThetaInv + u[1] * sinTheta},
                {u[1] * u[0] * cosThetaInv + u[2] * sinTheta, cosTheta + u2[1] * cosThetaInv, u[1] * u[2] * cosThetaInv - u[0] * sinTheta},
                {u[2] * u[0] * cosThetaInv - u[1] * sinTheta, u[2] * u[1] * cosThetaInv + u[0] * sinTheta, cosTheta + u2[2] * cosThetaInv}
        };











        final float transformedSideVectorXY_Angle = (float) Math.atan2(transformedXVector[0], transformedXVector[1]);
        Log.d(TAG, String.format("transformedSideVectorXY_Angle: %f", transformedSideVectorXY_Angle));
        final float angleCos2 = (float) Math.cos(transformedSideVectorXY_Angle);
        final float angleSin2 = (float) Math.sin(transformedSideVectorXY_Angle);
        final double[] compensatedTransformedSideVector = {
                transformedXVector[0] * angleCos2 - transformedXVector[1] * angleSin2,
                transformedXVector[0] * angleSin2 + transformedXVector[1] * angleCos2,
                transformedXVector[2]
        };
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedSideVectorLength = Math.sqrt(
                compensatedTransformedSideVector[0] * compensatedTransformedSideVector[0] +
                        compensatedTransformedSideVector[1] * compensatedTransformedSideVector[1] +
                        compensatedTransformedSideVector[2] * compensatedTransformedSideVector[2]
        );
        final double[] normalizedCompensatedTransformedSideVector = {
                compensatedTransformedSideVector[0] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[1] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[2] / compensatedTransformedSideVectorLength
        };
        Log.d(TAG, String.format("normalizedCompensatedTransformedSideVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedSideVector[0],
                normalizedCompensatedTransformedSideVector[1],
                normalizedCompensatedTransformedSideVector[2])
        );
        /*final double[] compensatedTransformedSideVector = {
                transformedXVector[0] * angleCos2 - transformedXVector[1] * angleSin2,
                transformedXVector[0] * angleSin2 + transformedXVector[1] * angleCos2,
                transformedXVector[2]
        };
        Log.d(TAG, String.format("compensatedTransformedSideVector: %.2f, %.2f, %.2f",
                compensatedTransformedSideVector[0],
                compensatedTransformedSideVector[1],
                compensatedTransformedSideVector[2])
        );
        // compensatedVector[0] should always be 0 !!!
        final double compensatedTransformedSideVectorLength = Math.sqrt(
                compensatedTransformedSideVector[0] * compensatedTransformedSideVector[0]
                        + compensatedTransformedSideVector[2] * compensatedTransformedSideVector[2]
        );
        final double[] normalizedCompensatedTransformedSideVector = {
                0,
                compensatedTransformedSideVector[1] / compensatedTransformedSideVectorLength,
                compensatedTransformedSideVector[2] / compensatedTransformedSideVectorLength
        };
        Log.d(TAG, String.format("normalizedCompensatedTransformedSideVector: %.2f, %.2f, %.2f",
                normalizedCompensatedTransformedSideVector[0],
                normalizedCompensatedTransformedSideVector[1],
                normalizedCompensatedTransformedSideVector[2])
        );
        final float tmp = (float) Math.atan2(compensatedTransformedSideVector[0], compensatedTransformedSideVector[2]);
        Log.d(TAG, String.format("tmp: %.2f", tmp));
        final float[] flatTransformedSideVector = multiply(rotMat3, transformedXVector);
        final float finalRotation = (float) Math.atan2(normalizedCompensatedTransformedSideVector[1], normalizedCompensatedTransformedSideVector[2]);*/

        //float finalRotation = (float) Math.atan2(normalizedCompensatedTransformedSideVector[1], normalizedCompensatedTransformedSideVector[2]);

        float finalRotation;
        //if (transformedZVector[2] >= 0) {
            finalRotation = (float) Math.asin(transformedYVector[2]);
            // finalRotation = (float) Math.atan2(transformedYVector[0], transformedYVector[1]);
        //} else {
        //    finalRotation = (float) Math.atan2(transformedYVector[0], -transformedYVector[1]);
        //}

        float finalRotation2;
        if (transformedZVector[0] >= 0) {
            finalRotation2 = (float) Math.atan2(transformedYVector[1], transformedYVector[2]);
        } else {
            finalRotation2 = (float) Math.atan2(transformedYVector[1], -transformedYVector[2]);
        }

        float[] flatZVector = {transformedZVector[0], transformedZVector[1], 0};
        float flatZVectorLength = (float) Math.sqrt(flatZVector[0] * flatZVector[0] + flatZVector[1] * flatZVector[1]);
        flatZVector[0] /= flatZVectorLength;
        flatZVector[1] /= flatZVectorLength;
        float[] crossproduct = {-flatZVector[1], flatZVector[0], 0};
        Log.d(TAG, String.format("crossproduct: %.2f, %.2f, %.2f",
                crossproduct[0],
                crossproduct[1],
                crossproduct[2])
        );

        float dotProduct = transformedYVector[0] * crossproduct[0] + transformedYVector[1] * crossproduct[1] + transformedYVector[2] * crossproduct[2];
        Log.d(TAG, String.format("dotproduct: %.2f, %.2f, %.2f, %.2f",
                transformedYVector[0] * crossproduct[0],
                transformedYVector[1] * crossproduct[1],
                transformedYVector[2] * crossproduct[2],
                dotProduct)
        );

        Log.d(TAG, String.format("finalRotation: %.2f", ((float) Math.acos(dotProduct)) * Math.signum(finalRotation)));
        Log.d(TAG, String.format("finalRotation2: %.2f", finalRotation2));

        Log.d(TAG, String.format("finalVector: %.2f, %.2f, %.2f, %.2f",
                finalRotation,
                normalizedCompensatedTransformedUpVector[0],
                normalizedCompensatedTransformedUpVector[1],
                normalizedCompensatedTransformedUpVector[2])
        );

        /*Log.d(TAG, String.format("Rotation: %.2f, %.2f, %.2f || %.2f, %.2f, %.2f", rotationConjToZ2[1], rotationConjToZ2[2], rotationConjToZ2[3], rotation[0], rotation[1], rotation[2]));
        rotationsLock.lock();
        try {
            rotations.add(new Rotation(rotationConjToZ2[1], rotationConjToZ2[2], rotationConjToZ2[3]));
        } finally {
            rotationsLock.unlock();
        }*/
    }
}
