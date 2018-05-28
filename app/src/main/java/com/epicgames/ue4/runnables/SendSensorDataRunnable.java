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
            Log.d("Foo", String.format("Rotation: %.2f %.2f %.2f || %.2f, %.2f, %.2f || %.2f, %.2f, %.2f", rotation.vectorX, rotation.vectorY, rotation.vectorZ, rotation.rotX, rotation.rotY, rotation.rotZ, acceleration.x, acceleration.y, acceleration.z));
            outputStream.writeFloat(rotation.vectorX);
            outputStream.writeFloat(rotation.vectorY);
            outputStream.writeFloat(rotation.vectorZ);
            outputStream.writeFloat(rotation.rotX);
            outputStream.writeFloat(rotation.rotY);
            outputStream.writeFloat(rotation.rotZ);
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
        float vectorX = 0;
        float vectorY = 0;
        float vectorZ = 0;
        float rotX = 0;
        float rotY = 0;
        float rotZ = 0;
        for (final Rotation rotation : rotations) {
            vectorX += rotation.vectorX;
            vectorY += rotation.vectorY;
            vectorZ += rotation.vectorZ;
            rotX += rotation.rotX;
            rotY += rotation.rotY;
            rotZ += rotation.rotZ;
        }
        vectorX /= rotations.size();
        vectorY /= rotations.size();
        vectorZ /= rotations.size();
        rotX /= rotations.size();
        rotY /= rotations.size();
        rotZ /= rotations.size();
        final Rotation rotation = new Rotation(vectorX, vectorY, vectorZ, rotX, rotY, rotZ, rotations.get(rotations.size() - 1).timestamp);
        MainActivity.resetRotations();
        MainActivity.rotationsLock.unlock();
        return rotation;
    }
}