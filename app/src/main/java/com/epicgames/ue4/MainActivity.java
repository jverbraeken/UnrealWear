package com.epicgames.ue4;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Vibrator;
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
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MainActivity extends WearableActivity implements SensorEventListener, ConnectionCallbacks, OnConnectionFailedListener {
    public static final String TAG = "WearApp";
    public static final int SENSITIVITY_LIGHT = 11;
    public static final int SENSITIVITY_MEDIUM = 13;
    public static final int SENSITIVITY_HARD = 15;
    private static final String IP_ADDRESS = "192.168.178.29";
    private static final int PORT = 55056;
    private static final InetAddress INET_ADDRESS;
    private static final long SEND_TIME_THRESHOLD = 1000 / 25; // 20 times per 1000 millisecond (= 20 times per second)
    private static final Touch NO_TOUCH = new Touch(-1, -1, (byte) 0);
    private static final float ALPHA = 0.8f;
    private static final int DEFAULT_ACCELERATION_THRESHOLD = SENSITIVITY_LIGHT;

    static {
        InetAddress tmp = null;
        try {
            tmp = InetAddress.getByName(IP_ADDRESS);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        INET_ADDRESS = tmp;
    }

    private final SampleQueue queue = new SampleQueue();
    private final List<Rotation> rotations = Collections.synchronizedList(new ArrayList<Rotation>());
    private final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    /**
     * When the magnitude of total acceleration exceeds this
     * value, the phone is accelerating.
     */
    private int accelerationThreshold = DEFAULT_ACCELERATION_THRESHOLD;
    private Acceleration accelerationWithGravity = new Acceleration(0, 0, 0);
    private Acceleration acceleration = new Acceleration(0, 0, 0);
    private Channel channel;
    private GoogleApiClient mGoogleApiClient;
    private Node node;
    private DataOutputStream channelOutputStream;
    private SensorManager sensorManager;
    private Sensor gyroscope;
    private Sensor accelerometer;
    private Vibrator vibrator;
    private Timer vibrationTimer = new Timer("vibration timer");
    private DatagramSocket datagramSocket;
    private boolean touchWasOnScreen;
    private boolean newTouchThisSample;
    private Touch touch = NO_TOUCH;
    private long vibrationStartTime = 0L;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(layout.activity_main);
        setAmbientEnabled();

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

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

        final ScheduledExecutorService service = Executors.newScheduledThreadPool(2);
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
    public void onConnectionSuspended(final int i) {

    }

    @Override
    public void onConnectionFailed(@NonNull final ConnectionResult connectionResult) {

    }

    @Override
    public void onSensorChanged(final SensorEvent sensorEvent) {
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
                boolean accelerating = isAccelerating(sensorEvent);
                long timestamp = sensorEvent.timestamp;
                queue.add(timestamp, accelerating);
                if (queue.isShaking()) {
                    queue.clear();
                    if (System.currentTimeMillis() > vibrationStartTime + 99999) {
                        vibrator.vibrate(99999);
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
                    }, 650);
                    Log.d(TAG, "Shaked!!!");
                }
                break;
            default:
                break;
        }
    }

    /**
     * Returns true if the device is currently accelerating.
     */
    private boolean isAccelerating(SensorEvent event) {
        float ax = event.values[0];
        float ay = event.values[1];
        float az = event.values[2];

        // Instead of comparing magnitude to ACCELERATION_THRESHOLD,
        // compare their squares. This is equivalent and doesn't need the
        // actual magnitude, which would be computed using (expensive) Math.sqrt().
        final double magnitudeSquared = ax * ax + ay * ay + az * az;
        return magnitudeSquared > accelerationThreshold * accelerationThreshold;
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
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_FASTEST);
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    @Override
    protected void onPause() {
        sensorManager.unregisterListener(this);
        Log.d(TAG, "paused");
        super.onPause();
    }

    /**
     * Queue of samples. Keeps a running average.
     */
    static class SampleQueue {

        /**
         * Window size in ns. Used to compute the average.
         */
        private static final long MAX_WINDOW_SIZE = 500000000; // 0.5s
        private static final long MIN_WINDOW_SIZE = MAX_WINDOW_SIZE >> 1; // 0.25s

        /**
         * Ensure the queue size never falls below this size, even if the device
         * fails to deliver this many events during the time window. The LG Ally
         * is one such device.
         */
        private static final int MIN_QUEUE_SIZE = 4;

        private final SamplePool pool = new SamplePool();

        private Sample oldest;
        private Sample newest;
        private int sampleCount;
        private int acceleratingCount;

        /**
         * Adds a sample.
         *
         * @param timestamp in nanoseconds of sample
         */
        void add(long timestamp, boolean accelerating) {
            // Purge samples that proceed window.
            purge(timestamp - MAX_WINDOW_SIZE);

            // Add the sample to the queue.
            Sample added = pool.acquire();
            added.timestamp = timestamp;
            added.accelerating = accelerating;
            added.next = null;
            if (newest != null) {
                newest.next = added;
            }
            newest = added;
            if (oldest == null) {
                oldest = added;
            }

            // Update running average.
            sampleCount++;
            if (accelerating) {
                acceleratingCount++;
            }
        }

        /**
         * Removes all samples from this queue.
         */
        void clear() {
            while (oldest != null) {
                Sample removed = oldest;
                oldest = removed.next;
                pool.release(removed);
            }
            newest = null;
            sampleCount = 0;
            acceleratingCount = 0;
        }

        /**
         * Purges samples with timestamps older than cutoff.
         */
        void purge(long cutoff) {
            while (sampleCount >= MIN_QUEUE_SIZE
                    && oldest != null && cutoff - oldest.timestamp > 0) {
                // Remove sample.
                Sample removed = oldest;
                if (removed.accelerating) {
                    acceleratingCount--;
                }
                sampleCount--;

                oldest = removed.next;
                if (oldest == null) {
                    newest = null;
                }
                pool.release(removed);
            }
        }

        /**
         * Copies the samples into a list, with the oldest entry at index 0.
         */
        List<Sample> asList() {
            List<Sample> list = new ArrayList<Sample>();
            Sample s = oldest;
            while (s != null) {
                list.add(s);
                s = s.next;
            }
            return list;
        }

        /**
         * Returns true if we have enough samples and more than 3/4 of those samples
         * are accelerating.
         */
        final boolean isShaking() {
            return newest != null
                    && oldest != null
                    && newest.timestamp - oldest.timestamp >= MIN_WINDOW_SIZE
                    && acceleratingCount >= (sampleCount >> 1) + (sampleCount >> 2);
        }
    }

    /**
     * An accelerometer sample.
     */
    static class Sample {
        /**
         * Time sample was taken.
         */
        long timestamp;

        boolean accelerating;

        /**
         * Next sample in the queue or pool.
         */
        Sample next;
    }

    /**
     * Pools samples. Avoids garbage collection.
     */
    static class SamplePool {
        private Sample head;

        /**
         * Acquires a sample from the pool.
         */
        Sample acquire() {
            Sample acquired = head;
            if (acquired == null) {
                acquired = new Sample();
            } else {
                // Remove instance from pool.
                head = acquired.next;
            }
            return acquired;
        }

        /**
         * Returns a sample to the pool.
         */
        void release(Sample sample) {
            sample.next = head;
            head = sample;
        }
    }

    private final class ChannelCreateRunnable implements Runnable {
        @Override
        public void run() {
            Wearable.ChannelApi.openChannel(mGoogleApiClient, node.getId(), "WEAR_ORIENTATION").setResultCallback(new ResultCallback<OpenChannelResult>() {
                @Override
                public void onResult(@NonNull final OpenChannelResult openChannelResult) {
                    Log.d(TAG, "channel found");
                    channel = openChannelResult.getChannel();
                    channel.getOutputStream(mGoogleApiClient).setResultCallback(new ResultCallback<GetOutputStreamResult>() {
                        @Override
                        public void onResult(@NonNull final GetOutputStreamResult getOutputStreamResult) {
                            Log.d(TAG, "Creating DataOutputStream");

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

                synchronized (MainActivity.this) {
                    if (newTouchThisSample) {
                        touchWasOnScreen = true;
                        newTouchThisSample = false;
                    }
                    if (touch.equals(NO_TOUCH)) {
                        touchWasOnScreen = false;
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
}
