package com.epicgames.ue4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.activity.WearableActivity;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private static final String IP_ADDRESS = "192.168.178.29";
    private static final int PORT = 55056;
    private static final InetAddress INET_ADDRESS;
    private static final long SEND_TIME_THRESHOLD = 1000 / 20; // 20 times per 1000 millisecond (= 20 times per second)
    private static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (final UnknownHostException e) {
            e.printStackTrace();
        }
        INET_ADDRESS = tmp;
    }

    private final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>());
    private final List<Acceleration> accelerations = Collections.synchronizedList(new ArrayList<Acceleration>());
    private final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node node;
    private DataOutputStream channelOutputStream;
    private SensorManager mSensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private DatagramSocket datagramSocket;
    private boolean touchWasOnScreen;
    private boolean newTouchThisSample;
    private Touch touch = NO_TOUCH;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_main);
        this.setAmbientEnabled();

        this.mSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
        this.gyroscope = this.mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        this.accelerometer = this.mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        final ImageView imageView = (ImageView) this.findViewById(R.id.imageView);
        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent motionEvent) {
                MainActivity.this.touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), (byte) (MainActivity.this.touchWasOnScreen ? 1 : 0));
                MainActivity.this.newTouchThisSample = true;
                return true;
            }
        });

        this.mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        this.mGoogleApiClient.connect();

        try {
            this.datagramSocket = new DatagramSocket(PORT);
            this.datagramSocket.setBroadcast(true);
        } catch (final SocketException e) {
            e.printStackTrace();
        }

        final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
        service.scheduleAtFixedRate(new SendDataRunnable(), 0, SEND_TIME_THRESHOLD, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onConnected(@Nullable final Bundle bundle) {
        Wearable.NodeApi.getConnectedNodes(this.mGoogleApiClient)
                .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                                       @Override
                                       public void onResult(@NonNull final NodeApi.GetConnectedNodesResult r) {
                                           for (final Node node : r.getNodes()) {
                                               MainActivity.this.node = node;
                                               final Runnable task = new ChannelCreateRunnable();
                                               MainActivity.this.cachedThreadPool.execute(task);
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
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                final float[] rotation = new float[3];
                System.arraycopy(sensorEvent.values, 0, rotation, 0, 3);
                this.storeRotation(rotation);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                final float[] acceleration = new float[3];
                System.arraycopy(sensorEvent.values, 0, acceleration, 0, 3);
                this.storeAcceleration(acceleration);
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("NumericCastThatLosesPrecision")
    private void storeRotation(final float[] values) {
        final float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        final float[] orientation = new float[3];
        SensorManager.getOrientation(rotMat, orientation);
        final float[] rotation = new float[3];
        rotation[0] = orientation[0] * 180.0f / (float) Math.PI; //Yaw
        rotation[1] = orientation[1] * 180.0f / (float) Math.PI; //Pitch
        rotation[2] = orientation[2] * 180.0f / (float) Math.PI; //Roll
        this.rotations.add(new Rotation(rotation[0], rotation[1], rotation[2]));
    }

    private void storeAcceleration(final float[] values) {
        this.accelerations.add(new Acceleration(values[0], values[1], values[2]));
    }

    @Override
    public void onAccuracyChanged(final Sensor sensor, final int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        this.mGoogleApiClient.connect();
        Log.d(TAG, "resumed");
        this.mSensorManager.registerListener(this, this.gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        this.mSensorManager.registerListener(this, this.accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        this.mSensorManager.unregisterListener(this);
        Log.d(TAG, "paused");
        super.onPause();
    }

    private final class ChannelCreateRunnable implements Runnable {
        @Override
        public void run() {
            final ChannelApi.OpenChannelResult result = Wearable.ChannelApi.openChannel(MainActivity.this.mGoogleApiClient, MainActivity.this.node.getId(), "WEAR_ORIENTATION").await();
            MainActivity.this.channel = result.getChannel();
            MainActivity.this.channelOutputStream = new DataOutputStream(MainActivity.this.channel.getOutputStream(MainActivity.this.mGoogleApiClient).await().getOutputStream());
        }
    }

    private final class SendDataRunnable implements Runnable {
        @Override
        public void run() {
            if (!rotations.isEmpty() && !accelerations.isEmpty()) {
                final Rotation avgRotation = avgAndResetRotation();
                final Acceleration avgAcceleration = avgAndResetAcceleration();

                if (BuildConfig.DEBUG) {
                    Log.d(TAG, String.format("Rotation: %.2f, %.2f, %.2f - Acceleration: %.2f, %.2f, %.2f - Timestamp: %d", avgRotation.x, avgRotation.y, avgRotation.z, avgAcceleration.x, avgAcceleration.y, avgAcceleration.z, avgRotation.timestamp));
                }

                MainActivity.this.cachedThreadPool.execute(new UDPRunnable(avgRotation, avgAcceleration, MainActivity.this.touch));
                MainActivity.this.cachedThreadPool.execute(new ChannelRunnable(avgRotation, avgAcceleration, MainActivity.this.touch));

                if (MainActivity.this.newTouchThisSample) {
                    MainActivity.this.touchWasOnScreen = true;
                    MainActivity.this.newTouchThisSample = false;
                }
                if (MainActivity.this.touch.equals(NO_TOUCH)) {
                    MainActivity.this.touchWasOnScreen = false;
                }
                MainActivity.this.touch = NO_TOUCH;
            }
        }

        private Rotation avgAndResetRotation() {
            float x = 0;
            float y = 0;
            float z = 0;
            synchronized (rotations) {
                for (final Rotation rotation : rotations) {
                    x += rotation.x;
                    y += rotation.y;
                    z += rotation.z;
                }
                x /= rotations.size();
                y /= rotations.size();
                z /= rotations.size();
                final Rotation rotation = new Rotation(x, y, z, rotations.get(rotations.size()-1).timestamp);
                rotations.clear();
                return rotation;
            }
        }

        private Acceleration avgAndResetAcceleration() {
            float x = 0;
            float y = 0;
            float z = 0;
            synchronized (accelerations) {
                for (final Acceleration acceleration : accelerations) {
                    x += acceleration.x;
                    y += acceleration.y;
                    z += acceleration.z;
                }
                x /= accelerations.size();
                y /= accelerations.size();
                z /= accelerations.size();
                final Acceleration acceleration = new Acceleration(x, y, z, accelerations.get(accelerations.size()-1).timestamp);
                accelerations.clear();
                return acceleration;
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
                dataOutputStream.writeFloat(this.rotation.x);
                dataOutputStream.writeFloat(this.rotation.y);
                dataOutputStream.writeFloat(this.rotation.z);
                dataOutputStream.writeLong(this.rotation.timestamp);

                dataOutputStream.writeFloat(this.acceleration.x);
                dataOutputStream.writeFloat(this.acceleration.y);
                dataOutputStream.writeFloat(this.acceleration.z);
                dataOutputStream.writeLong(this.acceleration.timestamp);

                if (MainActivity.this.touchWasOnScreen && this.touch.equals(NO_TOUCH)) {
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeByte(2);
                } else {
                    dataOutputStream.writeFloat(this.touch.x);
                    dataOutputStream.writeFloat(this.touch.y);
                    dataOutputStream.writeByte(this.touch.state);
                }
                dataOutputStream.writeLong(this.touch.timestamp);

                final byte[] byteArray = byteArrayOutputStream.toByteArray();
                final byte[] bytes = ByteBuffer.allocate(byteArray.length).put(byteArray).array();
                MainActivity.this.datagramSocket.send(new DatagramPacket(bytes, byteArray.length, INET_ADDRESS, PORT));
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
                final DataOutputStream outputStream = MainActivity.this.channelOutputStream;
                if (outputStream != null) {
                    outputStream.writeFloat(this.rotation.x);
                    outputStream.writeFloat(this.rotation.y);
                    outputStream.writeFloat(this.rotation.z);
                    outputStream.writeLong(this.rotation.timestamp);
                    outputStream.writeFloat(this.acceleration.x);
                    outputStream.writeFloat(this.acceleration.y);
                    outputStream.writeFloat(this.acceleration.z);
                    outputStream.writeLong(this.acceleration.timestamp);
                    outputStream.writeFloat(this.touch.x);
                    outputStream.writeFloat(this.touch.y);
                    outputStream.writeByte(this.touch.state);
                    outputStream.writeLong(this.touch.timestamp);
                } else {
                    Log.i("Warning", "No channelOutputStream available");
                }
            } catch (final IOException e) {
            }
        }
    }
}
