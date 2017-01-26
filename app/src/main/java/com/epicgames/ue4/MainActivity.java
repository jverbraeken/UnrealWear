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
import java.net.UnknownHostException;
import java.nio.ByteBuffer;

public class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;
    private DataOutputStream outputStream;

    private SensorManager mSensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private DatagramSocket datagramSocket;

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
            datagramSocket = new DatagramSocket(8765);
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
        float[] result = new float[3];
        float[] orientation = new float[3];
        float[] rotMat = new float[9];
        SensorManager.getRotationMatrixFromVector(rotMat, values);
        SensorManager.getOrientation(rotMat, orientation);
        result[0] = orientation[0] * 180.f / (float) Math.PI; //Yaw
        result[1] = orientation[1] * 180.f / (float) Math.PI; //Pitch
        result[2] = orientation[2] * 180.f / (float) Math.PI; //Roll
        Log.d(TAG, String.format("Now sending rotation: %.2f, %.2f, %.2f", result[0], result[1], result[2]));
        try {
            if (outputStream != null) {
                outputStream.writeFloat(result[0]);
                outputStream.writeFloat(result[1]);
                outputStream.writeFloat(result[2]);
            } else {
                Log.d(TAG, "rotation: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        final float[] result2 = result;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress IPAddress = InetAddress.getByName("192.168.178.42");
                    byte[] bytes = ByteBuffer.allocate(4 * 3).putFloat(result2[0]).putFloat(result2[1]).putFloat(result2[2]).array();
                    datagramSocket.setBroadcast(true);
                    datagramSocket.send(new DatagramPacket(bytes, 4 * 3, IPAddress, 8765));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void sendAcceleration(String node, float[] acceleration) {
        //Log.d(TAG, "Now sending acceleration: " + Arrays.toString(acceleration));
        ByteBuffer byteBuffer = ByteBuffer.allocate(3 * 4);
        for (int i = 0; i < 3; i++) {
            byteBuffer.putFloat(acceleration[i]);
        }
        final byte[] data = byteBuffer.array();
        /*try {
            if (outputStream != null) {
                outputStream.write(data);
            } else {
                Log.d(TAG, "acceleration: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }*/
        //Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
        //        "WEAR_ORIENTATION", data);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        mGoogleApiClient.connect();
        android.util.Log.d(TAG, "resumed");
        mSensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
        android.util.Log.d(TAG, "paused");
    }
}
