package com.epicgames.ue4;

import java.sql.Timestamp;

public class Touch {
    final float x, y;
    /**
     * 0 = new touch
     * 1 = hold
     * 2 = released
     */
    final int state;
    final Timestamp timestamp;

    Touch(final float x, final float y, final int state) {
        this.x = x;
        this.y = y;
        this.state = state;
        this.timestamp = new Timestamp(System.currentTimeMillis());
    }
}
