package com.epicgames.ue4;

public final class Rotation {
    public final float x;
    public final float y;
    public final float z;
    public final long timestamp;

    Rotation(final float x, final float y, final float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        timestamp = System.currentTimeMillis();
    }

    public Rotation(final float x, final float y, final float z, final long timestamp) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = timestamp;
    }
}
