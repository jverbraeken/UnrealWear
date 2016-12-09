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
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;

    private SensorManager mSensorManager;
    private Sensor gyrometer;
    private Sensor accelerometer;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setAmbientEnabled();

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyrometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
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
                    public void onResult(NodeApi.GetConnectedNodesResult nodes) {
                        for (Node node : nodes.getNodes()) {
                            mNode = node;
                        }
                    }
                });
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
                float[] rotation = new float[4];
                SensorManager.getQuaternionFromVector(rotation, event.values);
                sendRotation(mNode.getId(), rotation);
                break;
            case Sensor.TYPE_ACCELEROMETER:
                float[] acceleration = new float[3];
                System.arraycopy(event.values, 0, acceleration, 0, 3);
                sendAcceleration(mNode.getId(), acceleration);
                break;
        }
    }

    private void sendRotation(String node, float[] rotation) {
        Log.d(TAG, "Now sending rotation: " + Arrays.toString(rotation));
        ByteBuffer byteBuffer = ByteBuffer.allocate(4 * 4);
        for (int i = 0; i < 4; i++) {
            byteBuffer.putFloat(rotation[i]);
        }
        final byte[] data = byteBuffer.array();
        Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
                "WEAR_ORIENTATION", data);
    }

    private void sendAcceleration(String node, float[] acceleration) {
        Log.d(TAG, "Now sending acceleration: " + Arrays.toString(acceleration));
        ByteBuffer byteBuffer = ByteBuffer.allocate(3 * 4);
        for (int i = 0; i < 3; i++) {
            byteBuffer.putFloat(acceleration[i]);
        }
        final byte[] data = byteBuffer.array();
        Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
                "WEAR_ORIENTATION", data);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    protected void onResume() {
        super.onResume();
        mGoogleApiClient.connect();
        android.util.Log.d(TAG, "resumed");
        mSensorManager.registerListener(this, gyrometer, SensorManager.SENSOR_DELAY_GAME);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }
}
