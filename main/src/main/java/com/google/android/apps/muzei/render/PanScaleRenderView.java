/*
 * Copyright 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.apps.muzei.render;

import android.content.Context;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.Parcel;
import android.os.Parcelable;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.GestureDetectorCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.OverScroller;

import com.google.android.apps.muzei.util.MathUtil;
import com.google.android.apps.muzei.util.Zoomer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PanScaleRenderView extends GLTextureView {
    private static final String TAG = "PanScaleRenderView";

    private static final float MIN_VIEWPORT_SIZE = 0.2f;
    /**
     * The current viewport. This rectangle represents the currently visible chart domain
     * and range. The currently visible chart X values are from this rectangle's left to its right.
     * The currently visible chart Y values are from this rectangle's top to its bottom.
     */
    private RectF mCurrentViewport = new RectF(0, 0, 1, 1);

    private Point mSurfaceSizeBuffer = new Point();

    private int mWidth = 1;
    private int mHeight = 1;
    private float mRelativeAspectRatio = 1f;
    private boolean mPanScaleEnabled = false;

    // State objects and values related to gesture tracking.
    private ScaleGestureDetector mScaleGestureDetector;
    private GestureDetectorCompat mGestureDetector;
    private OverScroller mScroller;
    private Zoomer mZoomer;
    private PointF mZoomFocalPoint = new PointF();
    private RectF mScrollerStartViewport = new RectF(); // Used only for zooms and flings.
    private boolean mDragZoomed = false;
    private boolean mMotionEventDown;

    private Handler mHandler = new Handler();

    private OnOtherGestureListener mOnOtherGestureListener;
    private ImageRenderer mRenderer;

    public PanScaleRenderView(Context context) {
        this(context, null);
    }

    public PanScaleRenderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);

        mRenderer = new ImageRenderer();
        setRenderer(mRenderer);
        setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        setPreserveEGLContextOnPause(true);

        // Sets up interactions
        mScaleGestureDetector = new ScaleGestureDetector(context, mScaleGestureListener);
        mScaleGestureDetector.setQuickScaleEnabled(true);
        mGestureDetector = new GestureDetectorCompat(context, mGestureListener);

        mScroller = new OverScroller(context);
        mZoomer = new Zoomer(context);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        mWidth = Math.max(1, w);
        mHeight = Math.max(1, h);
        requestRender();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and objects related to rendering the image
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public void setImageUri(final Uri imageUri) {
        final Context context = getContext();
        new AsyncTask<Void, Void, BitmapRegionLoader>() {
            @Override
            protected BitmapRegionLoader doInBackground(Void... voids) {
                // Load the stream
                try {
                    // Check if there's rotation
                    int rotation = 0;
                    try {
                        InputStream in = context.getContentResolver().openInputStream(imageUri);
                        ExifInterface exifInterface;
                        if ( Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            exifInterface = new ExifInterface(in);
                        } else {
                            exifInterface = new ExifInterface(writeArtworkToFile(in).getAbsolutePath());
                        }
                        int orientation = exifInterface.getAttributeInt(
                                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                        switch (orientation) {
                            case ExifInterface.ORIENTATION_ROTATE_90: rotation = 90; break;
                            case ExifInterface.ORIENTATION_ROTATE_180: rotation = 180; break;
                            case ExifInterface.ORIENTATION_ROTATE_270: rotation = 270; break;
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Couldn't open EXIF interface on artwork", e);
                    }
                    return BitmapRegionLoader.newInstance(
                            context.getContentResolver().openInputStream(imageUri), rotation);
                } catch (IOException e) {
                    Log.e(TAG, "Error loading image", e);
                    return null;
                }
            }


            private File writeArtworkToFile(InputStream in) throws IOException {
                File file = new File(context.getCacheDir(), "temp_artwork");
                FileOutputStream out = new FileOutputStream(file);
                try {
                    byte[] buffer = new byte[1024];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) > 0) {
                        out.write(buffer, 0, bytesRead);
                    }
                    out.flush();
                } finally {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }
                return file;
            }

            @Override
            protected void onPostExecute(BitmapRegionLoader bitmapRegionLoader) {
                if (bitmapRegionLoader == null) {
                    return;
                }
                mRenderer.setBitmapRegionLoader(bitmapRegionLoader);
            }
        }.execute();
    }

    private class ImageRenderer implements GLSurfaceView.Renderer {
        // Model, view, projection, and MVP matrices
        private final float[] mMMatrix = new float[16];
        private final float[] mVMatrix = new float[16];
        private volatile float[] mPMatrix = new float[16];
        private final float[] mMVPMatrix = new float[16];

        private boolean mSurfaceCreated = false;

        private BitmapRegionLoader mBitmapRegionLoader;
        private GLPicture mPicture;

        void setBitmapRegionLoader(BitmapRegionLoader bitmapRegionLoader) {
            mBitmapRegionLoader = bitmapRegionLoader;
            createAndSetPicture();
        }

        @Override
        public void onSurfaceCreated(GL10 unused, EGLConfig config) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFuncSeparate(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA,
                    GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glClearColor(0, 0, 0, 0);

            // Set the camera position (View matrix)
            Matrix.setLookAtM(mVMatrix, 0,
                    0, 0, 1,
                    0, 0, -1,
                    0, 1, 0);

            GLPicture.initGl();
            mSurfaceCreated = true;
            createAndSetPicture();
        }

        @Override
        public void onSurfaceChanged(GL10 unused, int width, int height) {
            GLES20.glViewport(0, 0, width, height);
            createAndSetPicture();
        }

        private void createAndSetPicture() {
            if (!mSurfaceCreated || mBitmapRegionLoader == null) {
                return;
            }
            queueEvent(new Runnable() {
                @Override
                public void run() {
                    if (mPicture != null) {
                        mPicture.destroy();
                    }
                    mPicture = new GLPicture(mBitmapRegionLoader, getHeight());
                    float imageAspectRatio = mBitmapRegionLoader.getWidth() * 1f / mBitmapRegionLoader.getHeight();
                    float aspectRatio = getWidth() * 1f / getHeight();
                    setRelativeAspectRatio(imageAspectRatio / aspectRatio);
                    mPanScaleEnabled = true;
                }
            });
            requestRender();
        }

        @Override
        public void onDrawFrame(GL10 unused) {
            if (mPicture == null) {
                return;
            }
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            Matrix.setIdentityM(mMMatrix, 0);
            Matrix.orthoM(mPMatrix, 0,
                    MathUtil.interpolate(-1, 1, mCurrentViewport.left),
                    MathUtil.interpolate(-1, 1, mCurrentViewport.right),
                    MathUtil.interpolate(1, -1, mCurrentViewport.bottom),
                    MathUtil.interpolate(1, -1, mCurrentViewport.top),
                    1, 10);

            Matrix.multiplyMM(mMVPMatrix, 0, mVMatrix, 0, mMMatrix, 0);
            Matrix.multiplyMM(mMVPMatrix, 0, mPMatrix, 0, mMVPMatrix, 0);
            mPicture.draw(mMVPMatrix, 1);
        }

        void destroy() {
            if (mPicture != null) {
                mPicture.destroy();
            }
            mBitmapRegionLoader.destroy();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestRender();
    }

    @Override
    protected void onDetachedFromWindow() {
        queueEvent(new Runnable() {
            @Override
            public void run() {
                mRenderer.destroy();
            }
        });
        super.onDetachedFromWindow();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and objects related to gesture handling
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Finds the chart point (i.e. within the chart's domain and range) represented by the
     * given pixel coordinates. The "dest" argument is set to the point and
     * this function returns true.
     */
    private void hitTest(float x, float y, PointF dest) {
        dest.set(mCurrentViewport.left + mCurrentViewport.width() * x / mWidth,
                mCurrentViewport.top + mCurrentViewport.height() * y / mHeight);
     }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mMotionEventDown = true;
        }
        boolean retVal = mScaleGestureDetector.onTouchEvent(event);
        retVal = mGestureDetector.onTouchEvent(event) || retVal;
        if (mMotionEventDown && event.getActionMasked() == MotionEvent.ACTION_UP) {
            mMotionEventDown = false;
        }
        return retVal || super.onTouchEvent(event);
    }

    /**
     * The scale listener, used for handling multi-finger scale gestures.
     */
    private final ScaleGestureDetector.OnScaleGestureListener mScaleGestureListener
            = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        /**
         * This is the active focal point in terms of the viewport. Could be a local
         * variable but kept here to minimize per-frame allocations.
         */
        private PointF viewportFocus = new PointF();

        @Override
        public boolean onScaleBegin(ScaleGestureDetector detector) {
            if (!mPanScaleEnabled) {
                return false;
            }

            mDragZoomed = true;
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
            if (!mPanScaleEnabled) {
                return false;
            }

            float newWidth = 1 / scaleGestureDetector.getScaleFactor() * mCurrentViewport.width();
            float newHeight = 1 / scaleGestureDetector.getScaleFactor() * mCurrentViewport.height();

            float focusX = scaleGestureDetector.getFocusX();
            float focusY = scaleGestureDetector.getFocusY();
            hitTest(focusX, focusY, viewportFocus);

            mCurrentViewport.set(
                    viewportFocus.x - newWidth * focusX / mWidth,
                    viewportFocus.y - newHeight * focusY / mHeight,
                    0,
                    0);
            mCurrentViewport.right = mCurrentViewport.left + newWidth;
            mCurrentViewport.bottom = mCurrentViewport.top + newHeight;
            constrainViewport();
            requestRender();
            return true;
        }
    };

    /**
     * Ensures that current viewport is inside the viewport extremes and original
     * aspect ratio is kept.
     */
    private void constrainViewport() {
        if (mRelativeAspectRatio > 1) {
            if (mCurrentViewport.top < 0) {
                mCurrentViewport.offset(0, -mCurrentViewport.top);
            }
            if (mCurrentViewport.bottom > 1) {
                float requestedHeight = mCurrentViewport.height();
                mCurrentViewport.bottom = 1;
                mCurrentViewport.top = Math.max(0, mCurrentViewport.bottom - requestedHeight);
            }
            if (mCurrentViewport.height() < MIN_VIEWPORT_SIZE) {
                mCurrentViewport.bottom = (mCurrentViewport.bottom + mCurrentViewport.top) / 2
                        + MIN_VIEWPORT_SIZE / 2;
                mCurrentViewport.top = mCurrentViewport.bottom - MIN_VIEWPORT_SIZE;
            }
            float halfWidth = mCurrentViewport.height() / mRelativeAspectRatio / 2;
            float centerX = MathUtil.constrain(halfWidth, 1 - halfWidth,
                    (mCurrentViewport.right + mCurrentViewport.left) / 2);
            mCurrentViewport.left = centerX - halfWidth;
            mCurrentViewport.right = centerX + halfWidth;
        } else {
            if (mCurrentViewport.left < 0) {
                mCurrentViewport.offset(-mCurrentViewport.left, 0);
            }
            if (mCurrentViewport.right > 1) {
                float requestedWidth = mCurrentViewport.width();
                mCurrentViewport.right = 1;
                mCurrentViewport.left = Math.max(0, mCurrentViewport.right - requestedWidth);
            }
            if (mCurrentViewport.width() < MIN_VIEWPORT_SIZE) {
                mCurrentViewport.right = (mCurrentViewport.right + mCurrentViewport.left) / 2
                        + MIN_VIEWPORT_SIZE / 2;
                mCurrentViewport.left = mCurrentViewport.right - MIN_VIEWPORT_SIZE;
            }
            float halfHeight = mCurrentViewport.width() * mRelativeAspectRatio / 2;
            float centerY = MathUtil.constrain(halfHeight, 1 - halfHeight,
                    (mCurrentViewport.bottom + mCurrentViewport.top) / 2);
            mCurrentViewport.top = centerY - halfHeight;
            mCurrentViewport.bottom = centerY + halfHeight;
        }
    }

    /**
     * The gesture listener, used for handling simple gestures such as double touches, scrolls,
     * and flings.
     */
    private final GestureDetector.SimpleOnGestureListener mGestureListener
            = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            if (!mPanScaleEnabled) {
                return false;
            }

            mDragZoomed = false;
            mScrollerStartViewport.set(mCurrentViewport);
            mScroller.forceFinished(true);
            return true;
        }

        @Override
        public boolean onSingleTapConfirmed(MotionEvent e) {
            if (mOnOtherGestureListener != null) {
                mOnOtherGestureListener.onSingleTapUp();
            }
            return true;
        }

        @Override
        public boolean onDoubleTapEvent(MotionEvent e) {
            if (!mPanScaleEnabled || mDragZoomed || e.getActionMasked() != MotionEvent.ACTION_UP) {
                return false;
            }

            mZoomer.forceFinished(true);
            hitTest(e.getX(), e.getY(), mZoomFocalPoint);
            float startZoom;
            if (mRelativeAspectRatio > 1) {
                startZoom = 1 / mCurrentViewport.height();
            } else {
                startZoom = 1 / mCurrentViewport.width();
            }
            boolean zoomIn = (startZoom < 1.5f);
            mZoomer.startZoom(startZoom, zoomIn ? 2f : 1f);
            requestRender();
            postAnimateTick();

            // Workaround for 11952668; blow away the entire scale gesture detector after
            // a double tap
            mScaleGestureDetector = new ScaleGestureDetector(getContext(), mScaleGestureListener);
            mScaleGestureDetector.setQuickScaleEnabled(true);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (!mPanScaleEnabled) {
                return false;
            }

            // Scrolling uses math based on the viewport (as opposed to math using pixels).
            /**
             * Pixel offset is the offset in screen pixels, while viewport offset is the
             * offset within the current viewport. For additional information on surface sizes
             * and pixel offsets, see the docs for {@link computeScrollSurfaceSize()}. For
             * additional information about the viewport, see the comments for
             * {@link mCurrentViewport}.
             */
            float viewportOffsetX = distanceX * mCurrentViewport.width() / mWidth;
            float viewportOffsetY = distanceY * mCurrentViewport.height() / mHeight;
            computeScrollSurfaceSize(mSurfaceSizeBuffer);
            setViewportTopLeft(
                    mCurrentViewport.left + viewportOffsetX,
                    mCurrentViewport.top + viewportOffsetY);
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            if (!mPanScaleEnabled) {
                return false;
            }

            fling((int) -velocityX, (int) -velocityY);
            return true;
        }
    };

    private void fling(int velocityX, int velocityY) {
        // Flings use math in pixels (as opposed to math based on the viewport).
        computeScrollSurfaceSize(mSurfaceSizeBuffer);
        mScrollerStartViewport.set(mCurrentViewport);
        int startX = (int) (mSurfaceSizeBuffer.x * mScrollerStartViewport.left);
        int startY = (int) (mSurfaceSizeBuffer.y * mScrollerStartViewport.top);
        mScroller.forceFinished(true);
        mScroller.fling(
                startX,
                startY,
                velocityX,
                velocityY,
                0, mSurfaceSizeBuffer.x - mWidth,
                0, mSurfaceSizeBuffer.y - mHeight,
                mWidth / 2,
                mHeight / 2);
        postAnimateTick();
        requestRender();
    }

    private void postAnimateTick() {
        mHandler.removeCallbacks(mAnimateTickRunnable);
        mHandler.post(mAnimateTickRunnable);
    }

    private Runnable mAnimateTickRunnable = new Runnable() {
        @Override
        public void run() {
            boolean needsInvalidate = false;

            if (mScroller.computeScrollOffset()) {
                // The scroller isn't finished, meaning a fling or programmatic pan operation is
                // currently active.

                computeScrollSurfaceSize(mSurfaceSizeBuffer);
                int currX = mScroller.getCurrX();
                int currY = mScroller.getCurrY();

                float currXRange = currX * 1f / mSurfaceSizeBuffer.x;
                float currYRange = currY * 1f / mSurfaceSizeBuffer.y;
                setViewportTopLeft(currXRange, currYRange);
                needsInvalidate = true;
            }

            if (mZoomer.computeZoom()) {
                // Performs the zoom since a zoom is in progress.
                float newWidth, newHeight;
                if (mRelativeAspectRatio > 1) {
                    newHeight = 1 / mZoomer.getCurrZoom();
                    newWidth = newHeight / mRelativeAspectRatio;
                } else {
                    newWidth = 1 / mZoomer.getCurrZoom();
                    newHeight = newWidth * mRelativeAspectRatio;
                }
                // focalPointOnScreen... 0 = left/top edge of screen, 1 = right/bottom edge of sreen
                float focalPointOnScreenX = (mZoomFocalPoint.x - mScrollerStartViewport.left)
                        / mScrollerStartViewport.width();
                float focalPointOnScreenY = (mZoomFocalPoint.y - mScrollerStartViewport.top)
                        / mScrollerStartViewport.height();
                mCurrentViewport.set(
                        mZoomFocalPoint.x - newWidth * focalPointOnScreenX,
                        mZoomFocalPoint.y - newHeight * focalPointOnScreenY,
                        mZoomFocalPoint.x + newWidth * (1 - focalPointOnScreenX),
                        mZoomFocalPoint.y + newHeight * (1 - focalPointOnScreenY));
                constrainViewport();
                needsInvalidate = true;
            }

            if (needsInvalidate) {
                requestRender();
                postAnimateTick();
            }
        }
    };

    /**
     * Computes the current scrollable surface size, in pixels. For example, if the entire chart
     * area is visible, this is simply the current view width and height. If the chart
     * is zoomed in 200% in both directions, the returned size will be twice as large horizontally
     * and vertically.
     */
    private void computeScrollSurfaceSize(Point out) {
        out.set(
                (int) (mWidth / mCurrentViewport.width()),
                (int) (mHeight / mCurrentViewport.height()));
    }

    /**
     * Sets the current viewport (defined by {@link #mCurrentViewport}) to the given
     * X and Y positions. Note that the Y value represents the topmost pixel position, and thus
     * the bottom of the {@link #mCurrentViewport} rectangle. For more details on why top and
     * bottom are flipped, see {@link #mCurrentViewport}.
     */
    private void setViewportTopLeft(float x, float y) {
        /**
         * Constrains within the scroll range. The scroll range is simply the viewport extremes
         * (AXIS_X_MAX, etc.) minus the viewport size. For example, if the extrema were 0 and 10,
         * and the viewport size was 2, the scroll range would be 0 to 8.
         */

        float curWidth = mCurrentViewport.width();
        float curHeight = mCurrentViewport.height();
        x = Math.max(0, Math.min(x, 1 - curWidth));
        y = Math.max(0, Math.min(y, 1 - curHeight));

        mCurrentViewport.set(x, y, x + curWidth, y + curHeight);
        requestRender();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods and classes related to view state persistence.
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.viewport = mCurrentViewport;
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        mCurrentViewport = ss.viewport;
    }

    public void setRelativeAspectRatio(float relativeAspectRatio) {
        mRelativeAspectRatio = relativeAspectRatio;
        constrainViewport();
        requestRender();
    }

    public void setOnOtherGestureListener(OnOtherGestureListener onOtherGestureListener) {
        mOnOtherGestureListener = onOtherGestureListener;
    }

    /**
     * Persistent state that is saved by PanScaleProxyView.
     */
    public static class SavedState extends BaseSavedState {
        private RectF viewport;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(viewport.left);
            out.writeFloat(viewport.top);
            out.writeFloat(viewport.right);
            out.writeFloat(viewport.bottom);
        }

        @Override
        public String toString() {
            return "PanScaleProxyView.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " viewport=" + viewport.toString() + "}";
        }

        public static final Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        });

        SavedState(Parcel in) {
            super(in);
            viewport = new RectF(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
        }
    }

    public interface OnOtherGestureListener {
        void onSingleTapUp();
    }
}
