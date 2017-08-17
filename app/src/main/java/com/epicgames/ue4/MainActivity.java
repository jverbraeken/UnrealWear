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
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.DataOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
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
    public static final int MINIMUM_SHAKING_SENSIVITY = 11;
    public static final int MAX_VIBRATION_TIME = 99999;
    public static final int VIBRATION_DELAY = 650;
    public static final Lock sendChannelLock = new ReentrantLock();
    public static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);
    private static final String IP_ADDRESS = "192.168.178.29";
    private static final int PORT = 55056;
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final float LOW_PASS_FILTER = 0.8f;
    private static final InetAddress INET_ADDRESS;
    private static final float FROM_RADIANS_TO_DEGREES = 180.f / (float) Math.PI;
    private static WifiManager.WifiLock wifiLock;
    private static volatile boolean cancelInfiniteVibration;
    private static boolean newTouchThisSample;
    private static Touch touch = NO_TOUCH;
    private static boolean doVibrateWhileShaking = true;
    private static boolean forceVibration;
    private static volatile DataOutputStream channelOutputStream;

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (final UnknownHostException e) {
            e.printStackTrace();
        }
        INET_ADDRESS = tmp;
    }

    private final SampleQueue queue = new SampleQueue();
    private static final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>(15));
    private static final Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private static final Acceleration acceleration = new Acceleration(0, 0, 0);
    private GoogleApiClient googleApiClient;
    private Node node;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private DatagramSocket datagramSocket;
    private long vibrationStartTime;

    public static void keepWiFiOn(final Context context, final boolean on) {
        if (wifiLock == null) {
            final WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wm.setWifiEnabled(true);
                Log.d(TAG, "Wifi should now be enabled...");
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG);
                wifiLock.setReferenceCounted(true);
            }
        }
        if (wifiLock != null) {
            if (on) {
                wifiLock.acquire();
                Log.d(TAG, "Acquired WiFi lock");
            } else if (wifiLock.isHeld()) {
                wifiLock.release();
                Log.d(TAG, "Released WiFi lock");
            }
        }
    }

    /**
     * Returns true if the device is currently accelerating.
     */
    private static boolean isAccelerating(final SensorEvent event) {
        final float ax = event.values[0];
        final float ay = event.values[1];
        final float az = event.values[2];

        final double magnitudeSquared = ax * ax + ay * ay + az * az;
        return magnitudeSquared > MINIMUM_SHAKING_SENSIVITY * MINIMUM_SHAKING_SENSIVITY;
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

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        keepWiFiOn(this, true);

        final ImageView imageView = (ImageView) findViewById(R.id.imageView);
        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent motionEvent) {
                if (isNewTouchThisSample() && motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    touch = NO_TOUCH;
                    Log.d(TAG, "Touch not getting through: " + isNewTouchThisSample() + ", " + motionEvent.getAction());
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

        try {
            datagramSocket = new DatagramSocket(PORT);
            datagramSocket.setBroadcast(true);
        } catch (final SocketException e) {
            e.printStackTrace();
        }

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
                            MainActivity.this.node = node;
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
            final float[] rotation = new float[3];
            System.arraycopy(sensorEvent.values, 0, rotation, 0, 3);
            storeRotation(rotation);
        } else if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            accelerationWithGravity.x = LOW_PASS_FILTER * accelerationWithGravity.x + (1 - LOW_PASS_FILTER) * sensorEvent.values[0];
            accelerationWithGravity.y = LOW_PASS_FILTER * accelerationWithGravity.y + (1 - LOW_PASS_FILTER) * sensorEvent.values[1];
            accelerationWithGravity.z = LOW_PASS_FILTER * accelerationWithGravity.z + (1 - LOW_PASS_FILTER) * sensorEvent.values[2];
            getAcceleration().x = sensorEvent.values[0] - accelerationWithGravity.x;
            getAcceleration().y = sensorEvent.values[1] - accelerationWithGravity.y;
            getAcceleration().z = sensorEvent.values[2] - accelerationWithGravity.z;
            final boolean accelerating = isAccelerating(sensorEvent);
            final long timestamp = sensorEvent.timestamp;
            queue.add(timestamp, accelerating);
            if (queue.isShaking()) {
                queue.clear();
                Log.d(TAG, "Shaked!!!");
                if (!forceVibration && isDoVibrateWhileShaking()) {
                    if (System.currentTimeMillis() > vibrationStartTime + MAX_VIBRATION_TIME) {
                        vibrator.vibrate(MAX_VIBRATION_TIME);
                        vibrationStartTime = System.currentTimeMillis();
                    }
                    vibrationTimer.cancel();
                    vibrationTimer = new Timer("vibration timer");
                    vibrationTimer.schedule(new TimerTask() {
                        @Override
                        public void run() {
                            Log.d(TAG, "Cancelling");
                            vibrator.cancel();
                            vibrationStartTime = 0L;
                        }
                    }, VIBRATION_DELAY);
                }
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

    public static List<Rotation> getRotations() {
        return new ArrayList<>(rotations);
    }

    public static Acceleration getAcceleration() {
        return acceleration;
    }

    public static Touch getTouch() {
        return touch;
    }
}
