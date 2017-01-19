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
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class MainActivity extends WearableActivity implements SensorEventListener, GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node mNode;
    private DataOutputStream outputStream;

    private SensorManager mSensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;

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
                    public void onResult(@NonNull NodeApi.GetConnectedNodesResult nodes) {
                        for (final Node node : nodes.getNodes()) {
                            mNode = node;
                            new Thread() {
                                @Override
                                public void run() {
                                    ChannelApi.OpenChannelResult result = Wearable.ChannelApi.openChannel(mGoogleApiClient, node.getId(), "WEAR_ORIENTATION").await();
                                    channel = result.getChannel();
                                    outputStream = new DataOutputStream(channel.getOutputStream(mGoogleApiClient).await().getOutputStream());
                                }
                            }.start();
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
                //Log.d(TAG, "Now sending rotation: " + Arrays.toString(event.values));
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

    public float[] toAngles(float[] angles) {
        float[] result = new float[3];
        float sqw = angles[0] * angles[0];
        float sqx = angles[1] * angles[1];
        float sqy = angles[2] * angles[2];
        float sqz = angles[3] * angles[3];
        float unit = sqx + sqy + sqz + sqw; // if normalized is one, otherwise
        // is correction factor
        float test = angles[1] * angles[2] + angles[3] * angles[0];
        if (test > 0.499 * unit) { // singularity at north pole
            result[1] = 2 * (float) Math.atan2(angles[1], angles[0]);
            result[2] = (float) Math.PI / 2f;
            result[0] = 0;
        } else if (test < -0.499 * unit) { // singularity at south pole
            result[1] = -2 * (float) Math.atan2(angles[1], angles[0]);
            result[2] = (float) -Math.PI / 2f;
            result[0] = 0;
        } else {
            result[1] = (float) Math.atan2(2 * angles[2] * angles[0] - 2 * angles[1] * angles[3], sqx - sqy - sqz + sqw); // roll or heading
            result[2] = (float) Math.asin(2 * test / unit); // pitch or attitude
            result[0] = (float) Math.atan2(2 * angles[1] * angles[0] - 2 * angles[2] * angles[3], -sqx + sqy - sqz + sqw); // yaw or bank
        }

        result[0] *= 180 / Math.PI;
        result[1] *= 180 / Math.PI;
        result[2] *= 180 / Math.PI;
        return result;
    }

    private void sendRotation(String node, float[] rotation) {
        float[] euler = toAngles(rotation);
        Log.d(TAG, "Now sending rotation: " + Arrays.toString(euler));
        ByteBuffer byteBuffer = ByteBuffer.allocate(4 * 4);
        for (int i = 0; i < 4; i++) {
            byteBuffer.putFloat(rotation[i]);
        }
        final byte[] data = byteBuffer.array();
        try {
            if (outputStream != null) {
                //outputStream.write(data);
                outputStream.writeFloat(euler[0]);
                outputStream.writeFloat(euler[1]);
                outputStream.writeFloat(euler[2]);
                outputStream.writeFloat(0);
            } else {
                Log.d(TAG, "rotation: Outputstream was null!");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        //Wearable.MessageApi.sendMessage(mGoogleApiClient, node,
        //        "WEAR_ORIENTATION", data);
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
    }
}
