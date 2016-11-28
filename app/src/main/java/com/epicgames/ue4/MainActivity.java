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
    private static final float nanoToSeconds = 1f / 1000000000f;
    private static final double EPSILON = 0.00001;
    float mGravity[] = new float[3];
    float mGeomagnetic[] = new float[3];
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;
    private boolean ready = false;
    private float timestamp;

    private SensorManager mSensorManager;
    private Sensor accelerometer;
    private Sensor magnetomer;
    private Sensor gyroscope;
    private Sensor rotationVector;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetomer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        gyroscope = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        rotationVector = mSensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

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
            case Sensor.TYPE_ACCELEROMETER:
                mGravity = event.values.clone();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mGeomagnetic = event.values.clone();
                //ready = true;
                break;
            case Sensor.TYPE_GYROSCOPE:
                mGravity = event.values.clone();
                //ready = true;
                break;
            case Sensor.TYPE_ROTATION_VECTOR:
                mGravity = event.values.clone();
                ready = true;
                break;
        }
        if (ready) {
            float rotationMatrix[] = new float[16];
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
            sendOrientation(mNode.getId(), rotationMatrix);
        }
        /*if (ready) {
            ready = false;
            if (timestamp != 0) {
                final float deltaTime = (event.timestamp - timestamp) * nanoToSeconds;

                float axisX = event.values[0];
                float axisY = event.values[1];
                float axisZ = event.values[2];

                double angularSpeed = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
                if (angularSpeed > EPSILON) {
                    axisX /= angularSpeed;
                    axisY /= angularSpeed;
                    axisZ /= angularSpeed;
            }
        }*/
        /*if (mGravity != null && mGeomagnetic != null && ready) {
            ready = false;
            float matrixR[] = new float[9];
            boolean success = SensorManager.getRotationMatrix(matrixR, null, mGravity, mGeomagnetic);
            if (success) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(matrixR, orientation);
                sendOrientation(mNode.getId(), orientation[0], orientation[1], orientation[2]);
            } else {
                Log.e(TAG, "Couldn't get rotation matrix");
            }
        }*/
    }

    private void sendOrientation(String node, final float azimuth, final float pitch, final float roll) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(12);
        byteBuffer.putFloat(azimuth);
        byteBuffer.putFloat(pitch);
        byteBuffer.putFloat(roll);
        final byte[] data = byteBuffer.array();
        Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
                "WEAR_ORIENTATION", data);
    }

    private void sendOrientation(String node, final float[] rotationMatrix) {
        Log.d(TAG, "Now sending rotation matrix: " + Arrays.toString(rotationMatrix));
        ByteBuffer byteBuffer = ByteBuffer.allocate(16 * 4);
        for (int i = 0; i < 16; i++) {
            byteBuffer.putFloat(rotationMatrix[i]);
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
        Log.d(TAG, "resumed");
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(this, magnetomer, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        mSensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }
}
