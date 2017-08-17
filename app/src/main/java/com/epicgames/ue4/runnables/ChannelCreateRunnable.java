package com.epicgames.ue4.runnables;

import android.util.Log;

import com.epicgames.ue4.ThreadManager;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Channel;
import com.google.android.gms.wearable.ChannelApi;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import static com.epicgames.ue4.MainActivity.TAG;

public final class ChannelCreateRunnable implements Callable<ChannelCreateRunnable.ChannelCreateRunnableResult> {
    final GoogleApiClient googleApiClient;
    final Node node;

    public ChannelCreateRunnable(final GoogleApiClient googleApiClient, final Node node) {
        this.googleApiClient = googleApiClient;
        this.node = node;
    }

    @Override
    public ChannelCreateRunnable.ChannelCreateRunnableResult call() {
        final ChannelApi.OpenChannelResult openChannelResult = Wearable.ChannelApi.openChannel(googleApiClient, node.getId(), "WEAR_ORIENTATION").await();

        Log.d(TAG, "channel found");
        final Channel channel = openChannelResult.getChannel();
        if (channel == null) {
            Log.e(TAG, "Couldn't open a channel");
            try {
                Thread.sleep(500);
            } catch (final InterruptedException e) {
                Log.wtf(TAG, "ChannelCreateRunnable was interrupted...");
                e.printStackTrace();
            }
            try {
                return (ChannelCreateRunnable.ChannelCreateRunnableResult) ThreadManager.submit(new ChannelCreateRunnable(googleApiClient, node)).get();
            } catch (final InterruptedException | ExecutionException e) {
                e.printStackTrace();
                throw new RuntimeException("Fatal error");
            }
        } else {
            Log.v(TAG, "Can open a channel");
            final Channel.GetInputStreamResult inputStreamResult = channel.getInputStream(googleApiClient).await();
            final DataInputStream inputStream = new DataInputStream(inputStreamResult.getInputStream());
            final Channel.GetOutputStreamResult outputStreamResult = channel.getOutputStream(googleApiClient).await();
            final DataOutputStream outputStream = new DataOutputStream(outputStreamResult.getOutputStream());
            Log.v(TAG, "Obtained an input and output stream");
            return new ChannelCreateRunnable.ChannelCreateRunnableResult(inputStream, outputStream);
        }
    }

    public final class ChannelCreateRunnableResult {
        public final DataInputStream channelInputStream;
        public final DataOutputStream channelOutputStream;

        public ChannelCreateRunnableResult(final DataInputStream channelInputStream, final DataOutputStream channelOutputStream) {
            this.channelInputStream = channelInputStream;
            this.channelOutputStream = channelOutputStream;
        }
    }
}