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
import com.google.android.gms.wearable.Channel.GetOutputStreamResult;
import com.google.android.gms.wearable.ChannelApi.OpenChannelResult;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi.GetConnectedNodesResult;
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

public final class MainActivity extends WearableActivity implements SensorEventListener, ConnectionCallbacks, OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private static final String IP_ADDRESS = "192.168.178.29";
    private static final int PORT = 55056;
    private static final InetAddress INET_ADDRESS;
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);
    private static final float ALPHA = 0.8f;

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        INET_ADDRESS = tmp;
    }

    private final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>());
    private Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private Acceleration acceleration = new Acceleration(0, 0, 0);
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
        setContentView(layout.activity_main);
        setAmbientEnabled();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        final ImageView imageView = (ImageView) findViewById(id.imageView);
        imageView.setOnTouchListener(new OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), (byte) (touchWasOnScreen ? 1 : 0));
                newTouchThisSample = true;
                return true;
            }
        });

        mGoogleApiClient = new Builder(this)
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

        ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
        service.scheduleAtFixedRate(new SendDataRunnable(), 0, SEND_TIME_THRESHOLD, TimeUnit.MILLISECONDS);
    }

    @Override
    protected void onStart() {
        super.onStart();

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                .setResultCallback(new ResultCallback<GetConnectedNodesResult>() {
                                       @Override
                                       public void onResult(@NonNull GetConnectedNodesResult r) {
                                           for (Node node : r.getNodes()) {
                                               MainActivity.this.node = node;
                                               Runnable task = new ChannelCreateRunnable();
                                               cachedThreadPool.execute(task);
                                           }
                                       }
                                   }
                );
    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {
        switch (sensorEvent.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                float[] rotation = new float[3];
                System.arraycopy(sensorEvent.values, 0, rotation, 0, 3);
                storeRotation(rotation);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                accelerationWithGravity.x = ALPHA * accelerationWithGravity.x + (1 - ALPHA) * sensorEvent.values[0];
                accelerationWithGravity.y = ALPHA * accelerationWithGravity.y + (1 - ALPHA) * sensorEvent.values[1];
                accelerationWithGravity.z = ALPHA * accelerationWithGravity.z + (1 - ALPHA) * sensorEvent.values[2];
                acceleration.x = sensorEvent.values[0] - accelerationWithGravity.x;
                acceleration.y = sensorEvent.values[1] - accelerationWithGravity.y;
                acceleration.z = sensorEvent.values[2] - accelerationWithGravity.z;
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
        rotations.add(new Rotation(rotation[0], rotation[1], rotation[2]));
    }

    @Override
    public void onAccuracyChanged(final Sensor sensor, final int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        mGoogleApiClient.connect();
        Log.d(TAG, "resumed");
        mSensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        mSensorManager.unregisterListener(this);
        Log.d(TAG, "paused");
        super.onPause();
    }

    private final class ChannelCreateRunnable implements Runnable {
        @Override
        public void run() {
            Wearable.ChannelApi.openChannel(mGoogleApiClient, node.getId(), "WEAR_ORIENTATION").setResultCallback(new ResultCallback<OpenChannelResult>() {
                @Override
                public void onResult(@NonNull OpenChannelResult openChannelResult) {
                    Log.d(TAG, "channel found");
                    channel = openChannelResult.getChannel();
                    channel.getOutputStream(mGoogleApiClient).setResultCallback(new ResultCallback<GetOutputStreamResult>() {
                        @Override
                        public void onResult(@NonNull GetOutputStreamResult getOutputStreamResult) {
                            Log.d(TAG, "sendMessageToDevice: onResult: onResult: onResult");

                            channelOutputStream = new DataOutputStream(getOutputStreamResult.getOutputStream());
                        }
                    });
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

                cachedThreadPool.execute(new UDPRunnable(avgRotation, acceleration, touch));
                cachedThreadPool.execute(new ChannelRunnable(avgRotation, acceleration, touch));

                if (newTouchThisSample) {
                    touchWasOnScreen = true;
                    newTouchThisSample = false;
                }
                if (touch.equals(NO_TOUCH)) {
                    touchWasOnScreen = false;
                }
                touch = NO_TOUCH;
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
                Rotation rotation = new Rotation(x, y, z, rotations.get(rotations.size()-1).timestamp);
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

                if (touchWasOnScreen && touch.equals(NO_TOUCH)) {
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeFloat(-1);
                    dataOutputStream.writeByte(2);
                } else {
                    dataOutputStream.writeFloat(touch.x);
                    dataOutputStream.writeFloat(touch.y);
                    dataOutputStream.writeByte(touch.state);
                }
                dataOutputStream.writeLong(touch.timestamp);

                byte[] byteArray = byteArrayOutputStream.toByteArray();
                byte[] bytes = ByteBuffer.allocate(byteArray.length).put(byteArray).array();
                datagramSocket.send(new DatagramPacket(bytes, byteArray.length, INET_ADDRESS, PORT));
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final class ChannelRunnable implements Runnable {
        private final Rotation rotation;
        private final Acceleration acceleration;
        private final Touch touch;

        ChannelRunnable(Rotation rotation, Acceleration acceleration, Touch touch) {
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
                    outputStream.writeFloat(touch.x);
                    outputStream.writeFloat(touch.y);
                    outputStream.writeByte(touch.state);
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
}
