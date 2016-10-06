package net.sf.andpdf.pdfviewer;

import android.view.MotionEvent;

import uk.co.senab.photoview.PhotoViewAttacher;


public abstract class OnSwipeTouchListener implements PhotoViewAttacher.OnSingleFlingListener {

    private static final int SWIPE_DISTANCE_THRESHOLD = 100;
    private static final int SWIPE_VELOCITY_THRESHOLD = 100;

    @Override
    public boolean onFling(final MotionEvent e1, final MotionEvent e2, final float velocityX, final float velocityY) {
        float distanceX = e2.getX() - e1.getX();
        float distanceY = e2.getY() - e1.getY();
        if (Math.abs(distanceX) > Math.abs(distanceY) && Math.abs(distanceX) > SWIPE_DISTANCE_THRESHOLD && Math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
            if (distanceX > 0)
                onSwipeRight();
            else
                onSwipeLeft();
            return true;
        }
        return false;
    }

    public abstract void onSwipeLeft();

    public abstract void onSwipeRight();

}
