package com.epicgames.ue4;

class Acceleration {
    final float x, y, z;
    final long timestamp;

    Acceleration(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = System.currentTimeMillis();
    }

    Acceleration(float x, float y, float z, long timestamp) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = timestamp;
    }
}
