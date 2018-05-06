package com.epicgames.ue4.runnables;

import android.util.Log;

import com.epicgames.ue4.Acceleration;
import com.epicgames.ue4.BuildConfig;
import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.Rotation;
import com.epicgames.ue4.Touch;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Random;

public final class SendSensorDataRunnable implements Runnable {
    private static void sendDataOverChannel(final DataOutputStream outputStream, final Rotation rotation, final Acceleration acceleration, final Touch touch) {
        try {
            outputStream.writeByte(MainActivity.COMTP_SENSOR_DATA);
            outputStream.writeFloat(rotation.x);
            outputStream.writeFloat(rotation.y);
            outputStream.writeFloat(rotation.z);
            outputStream.writeLong(rotation.timestamp);
            outputStream.writeFloat(acceleration.x);
            outputStream.writeFloat(acceleration.y);
            outputStream.writeFloat(acceleration.z);
            outputStream.writeLong(acceleration.timestamp);
            if (touch == null) {
                outputStream.writeFloat(-1);
                outputStream.writeFloat(-1);
                outputStream.writeByte(2);
                outputStream.writeLong(System.currentTimeMillis());
            } else {
                outputStream.writeFloat(touch.x);
                outputStream.writeFloat(touch.y);
                outputStream.writeByte(touch.state.getCode());
                outputStream.writeLong(touch.timestamp);
            }
            outputStream.flush();
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        Log.d("Bar", "running: " + new Random().nextDouble());
        final DataOutputStream dataOutputStream = MainActivity.getChannelOutputStream();
        if (dataOutputStream != null) {
            final List<Rotation> rotations = MainActivity.getRotations();
            final Acceleration acceleration = MainActivity.getAcceleration();
            final Touch touch = MainActivity.getTouch();

            if (!rotations.isEmpty()) {
                final Rotation avgRotation = avgAndResetRotations();

                if (BuildConfig.DEBUG) {
                    //Log.d(TAG, String.format("Rotation: %.2f, %.2f, %.2f - Acceleration: %.2f, %.2f, %.2f - Timestamp: %d", avgRotation.x, avgRotation.y, avgRotation.z, acceleration.x, acceleration.y, acceleration.z, avgRotation.timestamp));
                }

                MainActivity.sendChannelLock.lock();
                try {
                    sendDataOverChannel(dataOutputStream, avgRotation, acceleration, touch);
                    MainActivity.nextTouch();
                } finally {
                    MainActivity.sendChannelLock.unlock();
                }
            }
        }
    }

    private Rotation avgAndResetRotations() {
        MainActivity.rotationsLock.lock();
        final List<Rotation> rotations = MainActivity.getRotations();
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
        final Rotation rotation = new Rotation(x, y, z, rotations.get(rotations.size() - 1).timestamp);
        MainActivity.resetRotations();
        MainActivity.rotationsLock.unlock();
        return rotation;
    }
}