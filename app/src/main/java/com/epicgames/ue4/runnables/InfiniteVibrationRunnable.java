package com.epicgames.ue4.runnables;

import android.os.Vibrator;

import com.epicgames.ue4.MainActivity;
import com.epicgames.ue4.ThreadManager;

import java.util.concurrent.TimeUnit;

public final class InfiniteVibrationRunnable implements Runnable {
    final Vibrator vibrator;

    public InfiniteVibrationRunnable(final Vibrator vibrator) {
        this.vibrator = vibrator;
    }

    @Override
    public final void run() {
        if (!MainActivity.isCancelInfiniteVibration()) {
            vibrator.vibrate(8000);
            ThreadManager.schedule(new InfiniteVibrationRunnable(vibrator), 8000, TimeUnit.MILLISECONDS);
        }
    }
}
