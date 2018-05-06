package com.epicgames.ue4;

import android.content.Context;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import java.util.Objects;

import static com.epicgames.ue4.MainActivity.NO_TOUCH;

class TouchListener implements View.OnTouchListener {
    private final GestureDetector gestureDetector;
    private Touch touch;

    TouchListener(final Context context) {
        this.gestureDetector = new GestureDetector(context, new GestureListener());
    }

    @Override
    public boolean onTouch(final View view, final MotionEvent motionEvent) {
        Log.d("Touch!!", motionEvent.toString());
        if (motionEvent.getAction() == MotionEvent.ACTION_UP) {
            Log.d("Touch!#", "Up");
            touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), Touch.STATE.UP);
            MainActivity.setTouchDown(false);
        } else if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
            Log.d("Touch!#", "Down");
            touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), Touch.STATE.DOWN);
            MainActivity.setTouchDown(true);
        } else {
            if (MainActivity.isTouchDown()) {
                touch = new Touch(motionEvent.getRawX(), motionEvent.getRawY(), Touch.STATE.HOLD);
                Log.d("Touch!#", "Hold");
            }
        }
        gestureDetector.onTouchEvent(motionEvent);
        MainActivity.addTouch(touch);
        return true;
    }

    private final class GestureListener extends GestureDetector.SimpleOnGestureListener {

        private static final int SWIPE_THRESHOLD = 25;
        private static final int SWIPE_VELOCITY_THRESHOLD = 50;

        @Override
        public boolean onDown(final MotionEvent e) {
            return true;
        }

        @Override
        public boolean onFling(final MotionEvent e1, final MotionEvent e2, final float velocityX, final float velocityY) {
            boolean result = false;
            final float diffY = e2.getY() - e1.getY();
            final float diffX = e2.getX() - e1.getX();
            if (Math.abs(diffX) > Math.abs(diffY)) {
                if (Math.abs(diffX) > SWIPE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                    if (diffX > 0) {
                        touch = new Touch(0, 0, Touch.STATE.SWIPE_RIGHT);
                        Log.d("Touch!#", "SWIPE_RIGHT");
                    } else {
                        touch = new Touch(0, 0, Touch.STATE.SWIPE_LEFT);
                        Log.d("Touch!#", "SWIPE_LEFT");
                    }
                    result = true;
                }
            } else if (Math.abs(diffY) > SWIPE_THRESHOLD && Math.abs(velocityY) > SWIPE_VELOCITY_THRESHOLD) {
                if (diffY > 0) {
                    touch = new Touch(0, 0, Touch.STATE.SWIPE_DOWN);
                    Log.d("Touch!#", "SWIPE_DOWN");
                } else {
                    touch = new Touch(0, 0, Touch.STATE.SWIPE_UP);
                    Log.d("Touch!#", "SWIPE_UP");
                }
                result = true;
            }
            return result;
        }
    }
}
