package com.epicgames.ue4;

import java.sql.Timestamp;

class Acceleration {
    final float x, y, z;
    final Timestamp timestamp;

    Acceleration(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = new Timestamp(System.currentTimeMillis());
    }
}
