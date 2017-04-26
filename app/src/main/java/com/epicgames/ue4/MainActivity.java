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

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private static final String IP_ADDRESS = "192.168.188.23";
    private static final int PORT = 55056;
    private static final short MIN_DATA_TO_SEND = 1;
    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;
    private DataOutputStream outputStream;
    private SensorManager mSensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private DatagramSocket datagramSocket;
    private List<float[]> rotations = new ArrayList<>();
    private List<float[]> accelerations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

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
                float[] result = new float[3];
                float[] orientation = new float[3];
                float[] rotMat = new float[9];
                SensorManager.getRotationMatrixFromVector(rotMat, event.values);
                SensorManager.getOrientation(rotMat, orientation);
                result[0] = orientation[0] * 180.f / (float) Math.PI; //Yaw
                result[1] = orientation[1] * 180.f / (float) Math.PI; //Pitch
                result[2] = orientation[2] * 180.f / (float) Math.PI; //Roll
                rotations.add(result);
                if (rotations.size() >= MIN_DATA_TO_SEND && accelerations.size() >= MIN_DATA_TO_SEND) {
                    sendData();
                    rotations = new ArrayList<>();
                    accelerations = new ArrayList<>();
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float[] acceleration = new float[3];
                System.arraycopy(event.values, 0, acceleration, 0, 3);
                accelerations.add(acceleration);
                if (rotations.size() >= MIN_DATA_TO_SEND && accelerations.size() >= MIN_DATA_TO_SEND) {
                    sendData();
                    rotations = new ArrayList<>();
                    accelerations = new ArrayList<>();
                }
                break;
        }
    }

    private void sendData() {
        float[] rotation = new float[3];
        for (float[] rot : rotations) {
            rotation[0] += rot[0];
            rotation[1] += rot[1];
            rotation[2] += rot[2];
        }
        rotation[0] /= rotations.size();
        rotation[1] /= rotations.size();
        rotation[2] /= rotations.size();

        float[] acceleration = new float[3];
        for (float[] acc : accelerations) {
            acceleration[0] += acc[0];
            acceleration[1] += acc[1];
            acceleration[2] += acc[2];
        }
        acceleration[0] /= accelerations.size();
        acceleration[1] /= accelerations.size();
        acceleration[2] /= accelerations.size();

        Log.d(TAG, String.format(
                "Now sending rotation: %.2f, %.2f, %.2f - acceleration: %.2f, %.2f, %.2f",
                rotation[0], rotation[1], rotation[2],
                acceleration[0], acceleration[1], acceleration[2]));
        try {
            if (outputStream != null) {
                outputStream.writeFloat(rotation[0]);
                outputStream.writeFloat(rotation[1]);
                outputStream.writeFloat(rotation[2]);
                outputStream.writeFloat(acceleration[0]);
                outputStream.writeFloat(acceleration[1]);
                outputStream.writeFloat(acceleration[2]);
            } else {
                Log.d(TAG, "rotation: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        final float[] rotation2 = rotation;
        final float[] acceleration2 = acceleration;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress IPAddress = InetAddress.getByName(IP_ADDRESS);
                    byte[] bytes = ByteBuffer.allocate(6 * 4).
                            putFloat(rotation2[0]).putFloat(rotation2[1]).putFloat(rotation2[2]).
                            putFloat(acceleration2[0]).putFloat(acceleration2[1]).putFloat(acceleration2[2]).array();
                    datagramSocket.setBroadcast(true);
                    datagramSocket.send(new DatagramPacket(bytes, 6 * 4, IPAddress, PORT));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
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
        super.onPause();
        mSensorManager.unregisterListener(this);
        android.util.Log.d(TAG, "paused");
    }
}
