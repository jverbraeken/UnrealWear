package com.epicgames.ue4.runnables;

import android.os.Vibrator;
import android.util.Log;

import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.ThreadManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static com.epicgames.ue4.MainActivity.TAG;

public final class ReceiveDataRunnable implements Runnable {
    private static final byte COMTW_REQUEST_VIBRATION = 1;
    private static final byte COMTW_FIXED_TIME = 2;
    private static final byte COMTW_INFINITY = 3;
    private static final byte COMTW_STOP_VIBRATION = 4;
    private static final byte COMTW_DO_VIBRATE_WHILE_SHAKING = 5;
    private static final byte COMTW_DO_NOT_VIBRATE_WHILE_SHAKING = 6;
    private static final byte COMTW_REQUEST_SHAKING_SENSIVITY = 7;
    private static final byte COMTW_SHAKING_SENSIVITY_LOW = 8;
    private static final byte COMTW_SHAKING_SENSIVITY_MEDIUM = 9;
    private static final byte COMTW_SHAKING_SENSIVITY_HIGH = 10;

    private final DataInputStream dataInputStream;
    private final Vibrator vibrator;

    public ReceiveDataRunnable(final Vibrator vibrator, final DataInputStream dataInputStream) {
        this.vibrator = vibrator;
        this.dataInputStream = dataInputStream;
    }

    @Override
    public void run() {
        try {
            while (true) {
                final byte request = dataInputStream.readByte();
                Log.d(TAG, "Request received! -> " + request);
                switch (request) {
                    case COMTW_REQUEST_VIBRATION:
                        final byte duration = dataInputStream.readByte();
                        Log.d(TAG, "Duration received! -> " + duration);
                        if (duration == COMTW_FIXED_TIME) {
                            vibrator.vibrate(dataInputStream.readInt());
                            Log.d(TAG, "Now vibrating with duration!");
                        } else if (duration == COMTW_INFINITY) {
                            MainActivity.setForceVibration(true);
                            MainActivity.setCancelInfiniteVibration(false);
                            vibrator.vibrate(8000);
                            ThreadManager.schedule(new InfiniteVibrationRunnable(vibrator), 8000, TimeUnit.MILLISECONDS);

                            Log.d(TAG, "Now vibrating!");
                        }
                        break;
                    case COMTW_STOP_VIBRATION:
                        MainActivity.setForceVibration(false);
                        MainActivity.setCancelInfiniteVibration(true);
                        vibrator.cancel();
                        Log.d(TAG, "Vibrating cancelled");
                        break;
                    case COMTW_DO_VIBRATE_WHILE_SHAKING:
                        MainActivity.setDoVibrateWhileShaking(true);
                        Log.d(TAG, "Do vibrate while shaking");
                        break;
                    case COMTW_DO_NOT_VIBRATE_WHILE_SHAKING:
                        MainActivity.setDoVibrateWhileShaking(false);
                        Log.d(TAG, "Do NOT vibrate while shaking");
                        break;
                    default:
                        Log.d(TAG, "Unknown command received: " + request);
                }
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
}
