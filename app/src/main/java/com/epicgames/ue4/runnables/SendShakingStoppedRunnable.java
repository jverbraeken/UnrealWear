package com.epicgames.ue4.runnables;

import android.util.Log;

import com.epicgames.ue4.MainActivity;

import java.io.DataOutputStream;
import java.io.IOException;

import static com.epicgames.ue4.MainActivity.TAG;

public final class SendShakingStoppedRunnable implements Runnable {
    @Override
    public void run() {
        final DataOutputStream dataOutputStream = MainActivity.getChannelOutputStream();
        if (dataOutputStream != null) {
            MainActivity.sendChannelLock.lock();
            try {
                Log.d(TAG, "Written that the shaking stopped");
                dataOutputStream.writeByte(MainActivity.COMTP_SHAKING_STOPPED);
                dataOutputStream.flush();
            } catch (final IOException e) {
                e.printStackTrace();
            } finally {
                MainActivity.sendChannelLock.unlock();
            }
        }
    }
}