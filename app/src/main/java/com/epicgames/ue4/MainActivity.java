package com.epicgames.ue4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Bundle;
import android.os.Vibrator;
import android.provider.ContactsContract;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.ImageView;

import com.epicgames.ue4.R.id;
import com.epicgames.ue4.R.layout;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.GoogleApiClient.Builder;
import com.google.android.gms.common.api.GoogleApiClient.ConnectionCallbacks;
import com.google.android.gms.common.api.GoogleApiClient.OnConnectionFailedListener;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.Channel.GetInputStreamResult;
import com.google.android.gms.wearable.Channel.GetOutputStreamResult;
import com.google.android.gms.wearable.ChannelApi.OpenChannelResult;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi.GetConnectedNodesResult;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@SuppressWarnings({"InstanceVariableOfConcreteClass", "ClassWithoutLogger", "PublicConstructor", "ClassWithTooManyFields", "StaticVariableOfConcreteClass", "PublicMethodWithoutLogging"})
public final class MainActivity extends WearableActivity implements SensorEventListener, ConnectionCallbacks, OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    public static final int MINIMUM_SHAKING_SENSIVITY = 11;
    public static final int MAX_VIBRATION_TIME = 99999;
    public static final int VIBRATION_DELAY = 650;
    private static final String IP_ADDRESS = "192.168.178.29";
    private static final int PORT = 55051;
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final float LOW_PASS_FILTER = 0.8f;
    private static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);
    private static final InetAddress INET_ADDRESS;
    private static final float FROM_RADIANS_TO_DEGREES = 180.f / (float) Math.PI;
    private static WifiLock wifiLock;

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
    private final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>(15));
    private final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    private final Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private final Acceleration acceleration = new Acceleration(0, 0, 0);
    private Channel channel;
    private GoogleApiClient googleApiClient;
    private Node node;
    private DataOutputStream channelOutputStream;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private DatagramSocket datagramSocket;
    private boolean newTouchThisSample;
    private Touch touch = NO_TOUCH;
    private long vibrationStartTime;
    private boolean doVibrateWhileShaking;
    private boolean forceVibration;

    public static void keepWiFiOn(final Context context, final boolean on) {
        if (wifiLock == null) {
            final WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
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

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layout.activity_main);
        setAmbientEnabled();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        keepWiFiOn(this, true);

        final ImageView imageView = (ImageView) findViewById(id.imageView);
        imageView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent motionEvent) {
                if (newTouchThisSample && motionEvent.getAction() == MotionEvent.ACTION_UP) {
                    touch = NO_TOUCH;
                    newTouchThisSample = false;
                    return true;
                }
                touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), (byte) (motionEvent.getAction() == MotionEvent.ACTION_UP ? 1 : 0));
                newTouchThisSample = true;
                return true;
            }
        });

        googleApiClient = new Builder(this)
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

        final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
        service.scheduleAtFixedRate(new SendDataRunnable(), 0, SEND_TIME_THRESHOLD, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onStart() {
        super.onStart();

        googleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable final Bundle bundle) {
        Wearable.NodeApi.getConnectedNodes(googleApiClient)
                .setResultCallback(new ResultCallback<GetConnectedNodesResult>() {
                                       @Override
                                       public void onResult(@NonNull GetConnectedNodesResult r) {
                                           for (final Node node : r.getNodes()) {
                                               MainActivity.this.node = node;
                                               cachedThreadPool.execute(new ChannelCreateRunnable());
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
            acceleration.x = sensorEvent.values[0] - accelerationWithGravity.x;
            acceleration.y = sensorEvent.values[1] - accelerationWithGravity.y;
            acceleration.z = sensorEvent.values[2] - accelerationWithGravity.z;
            final boolean accelerating = isAccelerating(sensorEvent);
            final long timestamp = sensorEvent.timestamp;
            queue.add(timestamp, accelerating);
            if (queue.isShaking()) {
                queue.clear();
                Log.d(TAG, "Shaked!!!");
                if (!forceVibration && doVibrateWhileShaking) {
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

    private final class ChannelCreateRunnable implements Runnable {
        @Override
        public void run() {
            Wearable.ChannelApi.openChannel(googleApiClient, node.getId(), "WEAR_ORIENTATION").setResultCallback(new ResultCallback<OpenChannelResult>() {
                @Override
                public void onResult(@NonNull final OpenChannelResult openChannelResult) {
                    Log.d(TAG, "channel found");
                    channel = openChannelResult.getChannel();

                    if (channel == null) {
                        Log.e(TAG, "Couldn't open a channel");
                        try {
                            Thread.sleep(500);
                        } catch (final InterruptedException e) {
                            Log.wtf(TAG, "ChannelCreateRunnable was interrupted...");
                            e.printStackTrace();
                        }
                        cachedThreadPool.execute(new ChannelCreateRunnable());
                    } else {
                        Log.v(TAG, "Can open a channel");
                        channel.getInputStream(googleApiClient).setResultCallback(new ResultCallback<GetInputStreamResult>() {
                            @Override
                            public void onResult(@NonNull final GetInputStreamResult inputStreamResult) {
                                Log.d(TAG, "Creating DataInputStream");

                                final DataInputStream channelInputStream = new DataInputStream(inputStreamResult.getInputStream());
                                cachedThreadPool.execute(new ReceiveDataRunnable(channelInputStream));

                            }
                        });
                        channel.getOutputStream(googleApiClient).setResultCallback(new ResultCallback<GetOutputStreamResult>() {
                            @Override
                            public void onResult(@NonNull final GetOutputStreamResult outputStreamResult) {
                                Log.d(TAG, "Creating DataOutputStream");

                                channelOutputStream = new DataOutputStream(outputStreamResult.getOutputStream());
                            }
                        });
                    }
                }
            });
        }
    }

    private final class SendDataRunnable implements Runnable {
        @Override
        public void run() {
            if (!rotations.isEmpty()) {
                final Rotation avgRotation = avgAndResetRotation();

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, String.format("Rotation: %.2f, %.2f, %.2f - Acceleration: %.2f, %.2f, %.2f - Timestamp: %d", avgRotation.x, avgRotation.y, avgRotation.z, acceleration.x, acceleration.y, acceleration.z, avgRotation.timestamp));
                }

                synchronized (MainActivity.this) {
                    if (newTouchThisSample) {
                        newTouchThisSample = false;
                    }

                    cachedThreadPool.execute(new UDPRunnable(avgRotation, acceleration, touch));
                    cachedThreadPool.execute(new ChannelRunnable(avgRotation, acceleration, touch));

                    touch = NO_TOUCH;
                }
            }
        }

        private Rotation avgAndResetRotation() {
            synchronized (rotations) {
                float x = 0;
                float y = 0;
                float z = 0;
                for (final Rotation rotation : rotations) {
                    x += rotation.x;
                    y += rotation.y;
                    z += rotation.z;
                }
                x /= rotations.size();
                y /= rotations.size();
                z /= rotations.size();
                Rotation rotation = new Rotation(x, y, z, rotations.get(rotations.size() - 1).timestamp);
                rotations.clear();
                return rotation;
            }
        }
    }

    private final class UDPRunnable implements Runnable {
        private final Rotation rotation;
        private final Acceleration acceleration;
        private final Touch touch;

        UDPRunnable(final Rotation rotation, final Acceleration acceleration, final Touch touch) {
            this.rotation = rotation;
            this.acceleration = acceleration;
            this.touch = touch;
        }

        @Override
        public void run() {
            try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                 DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream)) {
                dataOutputStream.writeFloat(rotation.x);
                dataOutputStream.writeFloat(rotation.y);
                dataOutputStream.writeFloat(rotation.z);
                dataOutputStream.writeLong(rotation.timestamp);

                dataOutputStream.writeFloat(acceleration.x);
                dataOutputStream.writeFloat(acceleration.y);
                dataOutputStream.writeFloat(acceleration.z);
                dataOutputStream.writeLong(acceleration.timestamp);

                if (touch.equals(NO_TOUCH)) {
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeByte(2);
                } else {
                    dataOutputStream.writeFloat(touch.x);
                    dataOutputStream.writeFloat(touch.y);
                    dataOutputStream.writeByte(touch.state);
                }
                dataOutputStream.writeLong(touch.timestamp);

                final byte[] byteArray = byteArrayOutputStream.toByteArray();
                final byte[] bytes = ByteBuffer.allocate(byteArray.length).put(byteArray).array();
                datagramSocket.send(new DatagramPacket(bytes, byteArray.length, INET_ADDRESS, PORT));
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final class ChannelRunnable implements Runnable {
        private final Rotation rotation;
        private final Acceleration acceleration;
        private final Touch touch;

        ChannelRunnable(final Rotation rotation, final Acceleration acceleration, final Touch touch) {
            this.rotation = rotation;
            this.acceleration = acceleration;
            this.touch = touch;
        }

        @Override
        public void run() {
            try {
                final DataOutputStream outputStream = channelOutputStream;
                if (outputStream != null) {
                    outputStream.writeFloat(rotation.x);
                    outputStream.writeFloat(rotation.y);
                    outputStream.writeFloat(rotation.z);
                    outputStream.writeLong(rotation.timestamp);
                    outputStream.writeFloat(acceleration.x);
                    outputStream.writeFloat(acceleration.y);
                    outputStream.writeFloat(acceleration.z);
                    outputStream.writeLong(acceleration.timestamp);
                    if (touch.equals(NO_TOUCH)) {
                        outputStream.writeFloat(-1);
                        outputStream.writeFloat(-1);
                        outputStream.writeByte(2);
                    } else {
                        outputStream.writeFloat(touch.x);
                        outputStream.writeFloat(touch.y);
                        outputStream.writeByte(touch.state);
                    }
                    outputStream.writeLong(touch.timestamp);
                    outputStream.flush();
                } else {
                    Log.i("Warning", "No channelOutputStream available");
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
    }


    private final class ReceiveDataRunnable implements Runnable {
        private static final byte COM_REQUEST_VIBRATION = 1;
        private static final byte COM_FIXED_TIME = 2;
        private static final byte COM_INFINITY = 3;
        private static final byte COM_STOP_VIBRATION = 4;
        private final DataInputStream dataInputStream;

        ReceiveDataRunnable(final DataInputStream dataInputStream) {
            this.dataInputStream = dataInputStream;
        }

        @Override
        public void run() {
            try {
                final byte request = dataInputStream.readByte();
                if (request == COM_REQUEST_VIBRATION) {
                    final byte duration = dataInputStream.readByte();
                    if (duration == COM_FIXED_TIME) {
                        vibrator.vibrate(dataInputStream.readInt());
                    } else if (duration == COM_INFINITY) {
                        forceVibration = true;
                        vibrator.vibrate(Long.MAX_VALUE);
                    }
                } else if (request == COM_STOP_VIBRATION) {
                    forceVibration = false;
                    vibrator.cancel();
                }
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }
    }
}
