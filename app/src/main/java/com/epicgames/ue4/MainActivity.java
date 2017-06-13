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
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private static final String IP_ADDRESS = "192.168.178.42";
    private static final int PORT = 55056;
    private static final InetAddress IPAddress;
    private static final long SEND_TIME_THRESHOLD = 1000 / 60; // 60 times per 1000 millisecond (= 60 times per second)
    private static final Touch NO_TOUCH = new Touch(-1, -1, -1);

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        IPAddress = tmp;
    }

    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;
    private DataOutputStream outputStream;
    private SensorManager mSensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private DatagramSocket datagramSocket;
    private boolean touchWasOnScreen = false;
    private List<Rotation> rotations = new ArrayList<>();
    private List<Acceleration> accelerations = new ArrayList<>();
    private Touch touch = NO_TOUCH;
    private Timestamp lastDataSend;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        lastDataSend = new Timestamp(System.currentTimeMillis());

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        final ImageView imageView = (ImageView) findViewById(R.id.imageView);
        imageView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(final View view, final MotionEvent event) {
                touch = new Touch(event.getRawX(), event.getRawY(), touchWasOnScreen ? (byte) 1 : (byte) 0);
                touchWasOnScreen = true;
                return true;
            }
        });

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
        mGoogleApiClient.connect();

        try {
            datagramSocket = new DatagramSocket(PORT);
        } catch (SocketException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    public void onConnected(@Nullable Bundle bundle) {
        Wearable.NodeApi.getConnectedNodes(mGoogleApiClient)
                .setResultCallback(new ResultCallback<NodeApi.GetConnectedNodesResult>() {
                                       @Override
                                       public void onResult(@NonNull NodeApi.GetConnectedNodesResult getConnectedNodesResult) {
                                           for (final Node node : getConnectedNodesResult.getNodes()) {
                                               mNode = node;
                                               Runnable task = new Runnable() {
                                                   @Override
                                                   public void run() {
                                                       ChannelApi.OpenChannelResult result = Wearable.ChannelApi.openChannel(mGoogleApiClient, node.getId(), "WEAR_ORIENTATION").await();
                                                       channel = result.getChannel();
                                                       outputStream = new DataOutputStream(channel.getOutputStream(mGoogleApiClient).await().getOutputStream());
                                                   }
                                               };
                                               new Thread(task).start();
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
    public void onSensorChanged(SensorEvent event) {
        if (mNode == null)
            return;
        if (mNode.getId() == null)
            return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ROTATION_VECTOR:
                sendRotation(event.values);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float[] acceleration = new float[3];
                System.arraycopy(event.values, 0, acceleration, 0, 3);
                sendAcceleration(mNode.getId(), acceleration);
                break;
        }
    }

    private void sendRotation(float[] values) {
        float[] orientation = new float[3];
        float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        SensorManager.getOrientation(rotMat, orientation);
        final float[] rotation = new float[3];
        rotation[0] = orientation[0] * 180.f / (float) Math.PI; //Yaw
        rotation[1] = orientation[1] * 180.f / (float) Math.PI; //Pitch
        rotation[2] = orientation[2] * 180.f / (float) Math.PI; //Roll
        Log.d(TAG, String.format("Now saving rotation: %.2f, %.2f, %.2f", rotation[0], rotation[1], rotation[2]));
        try {
            if (outputStream != null) {
                outputStream.writeFloat(rotation[0]);
                outputStream.writeFloat(rotation[1]);
                outputStream.writeFloat(rotation[2]);
            } else {
                Log.d(TAG, "rotation: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        rotations.add(new Rotation(rotation[0], rotation[1], rotation[2]));
        sendData();
    }

    private void sendAcceleration(String node, float[] values) {
        final float[] acceleration = new float[3];
        acceleration[0] = values[0];
        acceleration[1] = values[1];
        acceleration[2] = values[1];
        Log.d(TAG, String.format("Now sending acceleration: %.2f, %.2f, %.2f", acceleration[0], acceleration[1], acceleration[2]));
        ByteBuffer byteBuffer = ByteBuffer.allocate(3 * 4);
        for (int i = 0; i < 3; i++) {
            byteBuffer.putFloat(acceleration[i]);
        }
        final byte[] data = byteBuffer.array();
        try {
            if (outputStream != null) {
                outputStream.write(data);
            } else {
                Log.d(TAG, "acceleration: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        accelerations.add(new Acceleration(acceleration[0], acceleration[1], acceleration[2]));
        sendData();
    }

    private void sendData() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (System.currentTimeMillis() - lastDataSend.getTime() > SEND_TIME_THRESHOLD) {

                        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                        DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream);

                        final Rotation avgRotation = averageRot(rotations);
                        final Acceleration avgAcceleration = averageAcc(accelerations);

                        dataOutputStream.writeFloat(avgRotation.x);
                        dataOutputStream.writeFloat(avgRotation.y);
                        dataOutputStream.writeFloat(avgRotation.z);
                        dataOutputStream.writeLong(avgRotation.timestamp);

                        dataOutputStream.writeFloat(avgAcceleration.x);
                        dataOutputStream.writeFloat(avgAcceleration.y);
                        dataOutputStream.writeFloat(avgAcceleration.z);
                        dataOutputStream.writeLong(avgAcceleration.timestamp);

                        if (touchWasOnScreen && touch == NO_TOUCH) {
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
                        byte[] bytes = ByteBuffer.allocate(byteArray.length).put(byteArray).array();
                        datagramSocket.setBroadcast(true);
                        datagramSocket.send(new DatagramPacket(bytes, byteArray.length, IPAddress, PORT));

                        if (touch == NO_TOUCH) {
                            touchWasOnScreen = false;
                        }
                        touch = NO_TOUCH;
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private Rotation averageRot(final List<Rotation> rotations) {
        float x = 0, y = 0, z = 0;
        for (Rotation rotation : rotations) {
            x += rotation.x;
            y += rotation.y;
            z += rotation.z;
        }
        x /= rotations.size();
        y /= rotations.size();
        z /= rotations.size();
        return new Rotation(x, y, z);
    }

    private Acceleration averageAcc(final List<Acceleration> accelerations) {
        float x = 0, y = 0, z = 0;
        for (Acceleration acceleration : accelerations) {
            x += acceleration.x;
            y += acceleration.y;
            z += acceleration.z;
        }
        x /= accelerations.size();
        y /= accelerations.size();
        z /= accelerations.size();
        return new Acceleration(x, y, z);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        mGoogleApiClient.connect();
        android.util.Log.d(TAG, "resumed");
        mSensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        mSensorManager.unregisterListener(this);
        android.util.Log.d(TAG, "paused");
        super.onPause();
    }
}
