package com.epicgames.ue4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.GestureDetector;
import android.widget.ImageView;

import com.epicgames.ue4.runnables.ChannelCreateRunnable;
import com.epicgames.ue4.runnables.ReceiveDataRunnable;
import com.epicgames.ue4.runnables.SendSensorDataRunnable;
import com.epicgames.ue4.runnables.SendShakingStartedRunnable;
import com.epicgames.ue4.runnables.SendShakingStoppedRunnable;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SuppressWarnings({"InstanceVariableOfConcreteClass", "ClassWithoutLogger", "PublicConstructor", "ClassWithTooManyFields", "StaticVariableOfConcreteClass", "PublicMethodWithoutLogging"})
public final class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "Foo";
    public static final int MAX_VIBRATION_TIME = 99999;
    public static final int VIBRATION_DELAY = 650;
    public static final Lock sendChannelLock = new ReentrantLock();
    public static final Lock rotationsLock = new ReentrantLock();
    public static final Touch NO_TOUCH = new Touch(-1, -1, Touch.STATE.DOWN);

    public static final byte COMTP_SENSOR_DATA = 1;
    public static final byte COMTP_SHAKING_STARTED = 2;
    public static final byte COMTP_SHAKING_STOPPED = 3;
    public static final int LOW_SHAKING_SENSIVITY = 11;
    public static final int MEDIUM_SHAKING_SENSIVITY = 15;
    public static final int HIGH_SHAKING_SENSIVITY = 19;
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final float LOW_PASS_FILTER = 0.8f;
    private static final List<float[]> rotations = Collections.synchronizedList(new ArrayList<float[]>(15));
    private static final float[] accelerationWithGravity = new float[] {0, 0, 0};
    private static final float[] acceleration = new float[] {0, 0, 0};
    private static volatile boolean cancelInfiniteVibration = false;
    private static volatile List<Touch> touch = new ArrayList<>();
    private static volatile boolean touchDown;
    private static boolean doVibrateWhileShaking = true;
    private static volatile boolean forceVibration;
    private static volatile DataOutputStream channelOutputStream;
    private static int shakingSensivity = HIGH_SHAKING_SENSIVITY;
    private final ShakingQueue queue = new ShakingQueue();
    private GoogleApiClient googleApiClient;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private long shakeVibrationStartTime;
    private float[] gravityArray = new float[3];

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

    public static void setDoVibrateWhileShaking(final boolean doVibrateWhileShaking) {
        MainActivity.doVibrateWhileShaking = doVibrateWhileShaking;
    }

    public static boolean isCancelInfiniteVibration() {
        return cancelInfiniteVibration;
    }

    public static void setCancelInfiniteVibration(final boolean cancelInfiniteVibration) {
        MainActivity.cancelInfiniteVibration = cancelInfiniteVibration;
    }

    public static void setForceVibration(final boolean forceVibration) {
        MainActivity.forceVibration = forceVibration;
    }

    public static void nextTouch() {
        if (!touch.isEmpty()) {
            touch.remove(0);
        }
    }

    public static DataOutputStream getChannelOutputStream() {
        return channelOutputStream;
    }

    private static void updateAcceleration(final float[] values) {
        accelerationWithGravity[0] = LOW_PASS_FILTER * accelerationWithGravity[0] + (1 - LOW_PASS_FILTER) * values[0];
        accelerationWithGravity[1] = LOW_PASS_FILTER * accelerationWithGravity[1] + (1 - LOW_PASS_FILTER) * values[1];
        accelerationWithGravity[2] = LOW_PASS_FILTER * accelerationWithGravity[2] + (1 - LOW_PASS_FILTER) * values[2];
        acceleration[0] = values[0] - accelerationWithGravity[0];
        acceleration[1] = values[1] - accelerationWithGravity[1];
        acceleration[2] = values[2] - accelerationWithGravity[2];
    }

    public static List<float[]> getRotations() {
        return new ArrayList<>(rotations);
    }

    public static float[] getAcceleration() {
        return acceleration;
    }

    public static Touch getTouch() {
        return touch.isEmpty() ? null : touch.get(0);
    }

    public static boolean isTouchDown() {
        return touchDown;
    }

    public static void setTouchDown(boolean isDown) {
        touchDown = isDown;
    }

    public static void setShakingSensivity(int shakingSensivity) {
        MainActivity.shakingSensivity = shakingSensivity;
    }

    public static void resetRotations() {
        rotations.clear();
    }

    public static void addTouch(final Touch touch) {
        if (MainActivity.touch.isEmpty() || MainActivity.touch.get(MainActivity.touch.size() - 1).state != Touch.STATE.HOLD) {
            MainActivity.touch.add(touch);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        final ImageView imageView = (ImageView) findViewById(R.id.imageView);

        final GestureDetector gestureDetector;

        imageView.setOnTouchListener(new TouchListener(this));

        googleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        ThreadManager.scheduleAtFixedRate(new SendSensorDataRunnable(), SEND_TIME_THRESHOLD, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onStart() {
        super.onStart();
        googleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable final Bundle bundle) {
        Wearable.NodeApi.getConnectedNodes(googleApiClient).setResultCallback(
                new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                    @Override
                    public void onResult(@NonNull final NodeApi.GetConnectedNodesResult r) {
                        for (final Node node : r.getNodes()) {
                            try {
                                final ChannelCreateRunnable.ChannelCreateRunnableResult result = (ChannelCreateRunnable.ChannelCreateRunnableResult) ThreadManager.submit(new ChannelCreateRunnable(googleApiClient, node)).get();
                                ThreadManager.execute(new ReceiveDataRunnable(vibrator, result.channelInputStream));
                                channelOutputStream = result.channelOutputStream;
                            } catch (final InterruptedException | ExecutionException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
        );
    }

    @Override
    public void onConnectionSuspended(final int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull final ConnectionResult connectionResult) {
    }

    @Override
    public void onSensorChanged(final SensorEvent sensorEvent) {
        final int sensorType = sensorEvent.sensor.getType();
        if (sensorType == Sensor.TYPE_ROTATION_VECTOR) {
            onGyroscopeChanged(sensorEvent.values);
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            onAccelerometerChanged(sensorEvent.values, sensorEvent.timestamp);
            Log.d(TAG, "Accelerometer: " + sensorEvent.values[0] + ", " + sensorEvent.values[1] + ", " + sensorEvent.values[2]);
            float alpha = 0.8f;
            gravityArray[0] = alpha * gravityArray[0] + (1 - alpha) * sensorEvent.values[0];
            gravityArray[1] = alpha * gravityArray[1] + (1 - alpha) * sensorEvent.values[1];
            gravityArray[2] = alpha * gravityArray[2] + (1 - alpha) * sensorEvent.values[2];
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
            ThreadManager.execute(new SendShakingStartedRunnable());
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
                ThreadManager.execute(new SendShakingStoppedRunnable());
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
        googleApiClient.connect();
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
                r[0] * q[1] + r[1] * q[0] + r[2] * q[3] - r[3] * q[2],
                r[0] * q[2] - r[1] * q[3] + r[2] * q[0] + r[3] * q[1],
                r[0] * q[3] + r[1] * q[2] - r[2] * q[1] + r[3] * q[0]};
    }

    @SuppressWarnings("NumericCastThatLosesPrecision | NonReproducibleMathCall")
    private void storeRotation(final float[] values) {
        final float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        Log.d(TAG, String.format("rotMat: %.2f, %.2f, %.2f | %.2f, %.2f, %.2f | %.2f, %.2f, %.2f",
                rotMat[0],
                rotMat[1],
                rotMat[2],
                rotMat[3],
                rotMat[4],
                rotMat[5],
                rotMat[6],
                rotMat[7],
                rotMat[8])
        );
        rotationsLock.lock();
        try {
            rotations.add(rotMat);
        } finally {
            rotationsLock.unlock();
        }
    }
}
