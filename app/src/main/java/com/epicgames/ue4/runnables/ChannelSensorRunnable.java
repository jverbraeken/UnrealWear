package com.epicgames.ue4.runnables;

import com.epicgames.ue4.Acceleration;
import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.Rotation;
import com.epicgames.ue4.Touch;

import java.io.DataOutputStream;
import java.io.IOException;

final class ChannelSensorRunnable implements Runnable {
    private static final byte COMTP_SENSOR_DATA = 1;
    private static final byte COMTP_SHAKING_STARTED = 2;
    private static final byte COMTP_SHAKING_STOPPED = 3;

    private final Rotation rotation;
    private final Acceleration acceleration;
    private final Touch touch;
    private final DataOutputStream outputStream;

    ChannelSensorRunnable(final DataOutputStream outputStream, final Rotation rotation, final Acceleration acceleration, final Touch touch) {
        this.outputStream = outputStream;
        this.rotation = rotation;
        this.acceleration = acceleration;
        this.touch = touch;
    }

    @Override
    public void run() {
        try {
            synchronized (outputStream) {
                outputStream.writeFloat(rotation.x);
                outputStream.writeFloat(rotation.y);
                outputStream.writeFloat(rotation.z);
                outputStream.writeLong(rotation.timestamp);
                outputStream.writeFloat(acceleration.x);
                outputStream.writeFloat(acceleration.y);
                outputStream.writeFloat(acceleration.z);
                outputStream.writeLong(acceleration.timestamp);
                if (touch.equals(MainActivity.NO_TOUCH)) {
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
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
}