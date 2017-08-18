package com.epicgames.ue4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
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
    public static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);

    public static final byte COMTP_SENSOR_DATA = 1;
    public static final byte COMTP_SHAKING_STARTED = 2;
    public static final byte COMTP_SHAKING_STOPPED = 3;

    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final float LOW_PASS_FILTER = 0.8f;
    private static final float FROM_RADIANS_TO_DEGREES = 180.f / (float) Math.PI;
    public static final int LOW_SHAKING_SENSIVITY = 11;
    public static final int MEDIUM_SHAKING_SENSIVITY = 15;
    public static final int HIGH_SHAKING_SENSIVITY = 19;
    private static final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>(15));
    private static final Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private static final Acceleration acceleration = new Acceleration(0, 0, 0);
    private static WifiManager.WifiLock wifiLock;
    private static volatile boolean cancelInfiniteVibration;
    private static boolean newTouchThisSample;
    private static Touch touch = NO_TOUCH;
    private static boolean doVibrateWhileShaking = true;
    private static volatile boolean forceVibration;
    private static volatile DataOutputStream channelOutputStream;
    private final ShakingQueue queue = new ShakingQueue();
    private GoogleApiClient googleApiClient;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private long shakeVibrationStartTime;
    private static int shakingSensivity = LOW_SHAKING_SENSIVITY;

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

    public static DataOutputStream getChannelOutputStream() {
        return channelOutputStream;
    }

    public static boolean isNewTouchThisSample() {
        return newTouchThisSample;
    }

    private static void updateAcceleration(final float[] values) {
        accelerationWithGravity.x = LOW_PASS_FILTER * accelerationWithGravity.x + (1 - LOW_PASS_FILTER) * values[0];
        accelerationWithGravity.y = LOW_PASS_FILTER * accelerationWithGravity.y + (1 - LOW_PASS_FILTER) * values[1];
        accelerationWithGravity.z = LOW_PASS_FILTER * accelerationWithGravity.z + (1 - LOW_PASS_FILTER) * values[2];
        acceleration.x = values[0] - accelerationWithGravity.x;
        acceleration.y = values[1] - accelerationWithGravity.y;
        acceleration.z = values[2] - accelerationWithGravity.z;
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
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        vibrator.cancel();
        setCancelInfiniteVibration(true);
        Log.d(TAG, "paused");
        super.onPause();
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private void storeRotation(final float[] values) {
        final float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        final float[] orientation = new float[3];
        SensorManager.getOrientation(rotMat, orientation);
        final float[] rotation = new float[3];
        rotation[0] = orientation[0] * FROM_RADIANS_TO_DEGREES; //Yaw
        rotation[1] = orientation[1] * FROM_RADIANS_TO_DEGREES; //Pitch
        rotation[2] = orientation[2] * FROM_RADIANS_TO_DEGREES; //Roll
        rotations.add(new Rotation(rotation[0], rotation[1], rotation[2]));
    }
}
