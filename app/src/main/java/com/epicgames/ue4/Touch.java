package com.epicgames.ue4;

import android.support.annotation.NonNull;

public final class Touch {
    public final float x;
    public final float y;
    /**
     * 0 = new touch
     * 1 = hold
     * 2 = released
     */
    public final Touch.STATE state;
    public final long timestamp;

    public enum STATE {
        DOWN((byte) 1),
        HOLD((byte) 2),
        UP((byte) 3),
        SWIPE_LEFT((byte) 4),
        SWIPE_UP((byte) 5),
        SWIPE_RIGHT((byte) 6),
        SWIPE_DOWN((byte) 7);

        final byte code;

        STATE(final byte code) {
            this.code = code;
        }

        public byte getCode() {
            return code;
        }
    }

    Touch(final float x, final float y, @NonNull final Touch.STATE state) {
        this.x = x;
        this.y = y;
        this.state = state;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || this.getClass() != o.getClass()) {
            return false;
        }

        final Touch touch = (Touch) o;

        if (Float.compare(touch.x, this.x) != 0) {
            return false;
        }
        if (Float.compare(touch.y, this.y) != 0) {
            return false;
        }
        return this.state == touch.state;

    }

    @Override
    public int hashCode() {
        int result = this.x == +0.0f ? 0 : Float.floatToIntBits(this.x);
        result = 31 * result + (this.y == +0.0f ? 0 : Float.floatToIntBits(this.y));
        result = 31 * result + this.state.hashCode();
        return result;
    }
}
