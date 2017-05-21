package com.epicgames.ue4;

import java.sql.Timestamp;

class Rotation {
    final float x, y, z;
    final Timestamp timestamp;

    Rotation(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.timestamp = new Timestamp(System.currentTimeMillis());
    }
}
