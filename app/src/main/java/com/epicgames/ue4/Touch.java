package com.epicgames.ue4;

class Touch {
    final float x, y;
    /**
     * 0 = new touch
     * 1 = hold
     * 2 = released
     */
    final byte state;
    final long timestamp;

    Touch(final float x, final float y, final byte state) {
        this.x = x;
        this.y = y;
        this.state = state;
        this.timestamp = System.currentTimeMillis();
    }

    Touch(final float x, final float y, final byte state, long timestamp) {
        this.x = x;
        this.y = y;
        this.state = state;
        this.timestamp = timestamp;
    }
}
