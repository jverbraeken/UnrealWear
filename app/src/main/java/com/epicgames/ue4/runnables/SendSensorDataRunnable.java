package com.epicgames.ue4.runnables;

import android.util.Log;

import com.epicgames.ue4.Acceleration;
import com.epicgames.ue4.BuildConfig;
import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.Rotation;
import com.epicgames.ue4.Touch;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.List;

import static com.epicgames.ue4.MainActivity.INET_ADDRESS;
import static com.epicgames.ue4.MainActivity.PORT;
import static com.epicgames.ue4.MainActivity.TAG;

public final class SendSensorDataRunnable implements Runnable {

    public SendSensorDataRunnable() {
    }

    private static void sendData(final DatagramSocket datagramSocket, final Rotation rotation, final Acceleration acceleration, final Touch touch) {

        try (final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
             DataOutputStream dataOutputStream = new DataOutputStream(byteArrayOutputStream)) {
            dataOutputStream.writeByte(MainActivity.COMTP_SENSOR_DATA);
            dataOutputStream.writeFloat(rotation.x);
            dataOutputStream.writeFloat(rotation.y);
            dataOutputStream.writeFloat(rotation.z);
            dataOutputStream.writeLong(rotation.timestamp);

            dataOutputStream.writeFloat(acceleration.x);
            dataOutputStream.writeFloat(acceleration.y);
            dataOutputStream.writeFloat(acceleration.z);
            dataOutputStream.writeLong(acceleration.timestamp);

            if (touch.equals(MainActivity.NO_TOUCH)) {
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
            final byte[] bytes = ByteBuffer.allocate(byteArray.length).put(byteArray).array();
            datagramSocket.send(new DatagramPacket(bytes, byteArray.length, INET_ADDRESS, PORT));
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        final List<Rotation> rotations = MainActivity.getRotations();
        final Acceleration acceleration = MainActivity.getAcceleration();
        final Touch touch = MainActivity.getTouch();
        final boolean newTouchThisSample = MainActivity.isNewTouchThisSample();

        if (!rotations.isEmpty()) {
            final Rotation avgRotation = avgAndResetRotations();

            if (BuildConfig.DEBUG) {
                Log.d(TAG, String.format("Rotation: %.0f, %.0f, %.0f - Acceleration: %.1f, %.1f, %.1f", avgRotation.x, avgRotation.y, avgRotation.z, acceleration.x, acceleration.y, acceleration.z));
            }

            MainActivity.sendData.lock();
            try {
                if (newTouchThisSample) {
                    MainActivity.resetNewTouchThisSample();
                }
                sendData(MainActivity.getDatagramSocket(), avgRotation, acceleration, touch);
                MainActivity.resetTouch();
            } finally {
                MainActivity.sendData.unlock();
            }
        }
    }

    private Rotation avgAndResetRotations() {
        MainActivity.rotationsLock.lock();
        final List<Rotation> rotations = MainActivity.getRotations();
        float x = 0;
        float y = 0;
        float z = 0;
        float lastX = rotations.get(0).x;
        float lastY = rotations.get(0).y;
        float lastZ = rotations.get(0).z;
        int xNum = rotations.size();
        int yNum = rotations.size();
        int zNum = rotations.size();
        for (int i = 0; i < rotations.size(); i++) {
            if (Math.abs(lastX - rotations.get(i).x) > 180) {
                x = rotations.get(i).x;
                xNum = rotations.size() - i;
            } else {
                x += rotations.get(i).x;
            }
            if (Math.abs(lastY - rotations.get(i).y) > 180) {
                y = rotations.get(i).y;
                yNum = rotations.size() - i;
            } else {
                y += rotations.get(i).y;
            }
            if (Math.abs(lastZ - rotations.get(i).z) > 180) {
                z = rotations.get(i).z;
                zNum = rotations.size() - i;
            } else {
                z += rotations.get(i).z;
            }
        }
        x /= xNum;
        y /= yNum;
        z /= zNum;
        final Rotation rotation = new Rotation(x, y, z, rotations.get(rotations.size() - 1).timestamp);
        MainActivity.resetRotations();
        MainActivity.rotationsLock.unlock();
        return rotation;
    }
}
