package com.epicgames.ue4;


class ShakingQueue {
    private static final long MAX_WINDOW_SIZE = 500000000;
    private static final long MIN_WINDOW_SIZE = MAX_WINDOW_SIZE >> 1;
    private static final int MIN_QUEUE_SIZE = 4;
    private final SamplePool pool = new SamplePool();
    private Sample oldest;
    private Sample newest;
    private int sampleCount;
    private int acceleratingCount;

    @SuppressWarnings("NonBooleanMethodNameMayNotStartWithQuestion")
    final void add(final long timestamp, final boolean accelerating) {
        purge(timestamp - MAX_WINDOW_SIZE);

        final Sample added = pool.acquire();
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
        sampleCount++;
        if (accelerating) {
            acceleratingCount++;
        }
    }

    final void clear() {
        while (oldest != null) {
            final Sample removed = oldest;
            oldest = removed.next;
            pool.release(removed);
        }
        newest = null;
        sampleCount = 0;
        acceleratingCount = 0;
    }

    final void purge(final long cutoff) {
        while (sampleCount >= MIN_QUEUE_SIZE
                && oldest != null && cutoff - oldest.timestamp > 0) {
            final Sample removed = oldest;
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

    final boolean isShaking() {
        return newest != null
                && oldest != null
                && newest.timestamp - oldest.timestamp >= MIN_WINDOW_SIZE
                && acceleratingCount >= (sampleCount >> 1) + (sampleCount >> 2);
    }

    private static class Sample {
        long timestamp;
        boolean accelerating;
        Sample next;
    }

    private static class SamplePool {
        private Sample head;
        private Sample acquire() {
            Sample acquired = head;
            if (acquired == null) {
                acquired = new Sample();
            } else {
                head = acquired.next;
            }
            return acquired;
        }

        final void release(final Sample sample) {
            sample.next = head;
            head = sample;
        }
    }
}
