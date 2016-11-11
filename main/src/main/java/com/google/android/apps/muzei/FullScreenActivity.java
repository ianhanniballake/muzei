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

package com.google.android.apps.muzei;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.apps.muzei.api.Artwork;
import com.google.android.apps.muzei.api.MuzeiContract;
import com.google.android.apps.muzei.event.ArtDetailOpenedClosedEvent;
import com.google.android.apps.muzei.event.ArtworkSizeChangedEvent;
import com.google.android.apps.muzei.event.SwitchingPhotosStateChangedEvent;
import com.google.android.apps.muzei.event.WallpaperSizeChangedEvent;
import com.google.android.apps.muzei.util.DrawInsetsFrameLayout;
import com.google.android.apps.muzei.util.PanScaleProxyView;
import com.google.android.apps.muzei.util.ScrimUtil;
import com.google.android.apps.muzei.util.TypefaceUtil;

import net.nurik.roman.muzei.R;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;

public class FullScreenActivity extends AppCompatActivity {
    private static final String TAG = "FullScreenActivity";

    private int mCurrentViewportId = 0;
    private float mWallpaperAspectRatio;
    private float mArtworkAspectRatio;

    private LoaderManager.LoaderCallbacks<Cursor> mArtworkLoaderCallbacks = new LoaderManager.LoaderCallbacks<Cursor>() {
        @Override
        public Loader<Cursor> onCreateLoader(final int id, final Bundle args) {
            return new CursorLoader(FullScreenActivity.this, MuzeiContract.Artwork.CONTENT_URI,
                    null, null, null, null);
        }

        @Override
        public void onLoadFinished(final Loader<Cursor> loader, final Cursor data) {
            if (!data.moveToFirst()) {
                return;
            }
            Artwork currentArtwork = Artwork.fromCursor(data);
            String titleFont = "AlegreyaSans-Black.ttf";
            String bylineFont = "AlegreyaSans-Medium.ttf";
            if (Artwork.FONT_TYPE_ELEGANT.equals(currentArtwork.getMetaFont())) {
                titleFont = "Alegreya-BlackItalic.ttf";
                bylineFont = "Alegreya-Italic.ttf";
            }

            mTitleView.setTypeface(TypefaceUtil.getAndCache(FullScreenActivity.this, titleFont));
            mTitleView.setText(currentArtwork.getTitle());

            mBylineView.setTypeface(TypefaceUtil.getAndCache(FullScreenActivity.this, bylineFont));
            mBylineView.setText(currentArtwork.getByline());

            String attribution = currentArtwork.getAttribution();
            if (!TextUtils.isEmpty(attribution)) {
                mAttributionView.setText(attribution);
                mAttributionView.setVisibility(View.VISIBLE);
            } else {
                mAttributionView.setVisibility(View.GONE);
            }

            final Intent viewIntent = currentArtwork.getViewIntent();
            mMetadataView.setEnabled(viewIntent != null);
            if (viewIntent != null) {
                mMetadataView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        // Make sure any data URIs granted to Muzei are passed onto the
                        // started Activity
                        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        try {
                            startActivity(viewIntent);
                        } catch (ActivityNotFoundException | SecurityException e) {
                            Toast.makeText(FullScreenActivity.this, R.string.error_view_details,
                                    Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Error viewing artwork details.", e);
                        }
                    }
                });
            } else {
                mMetadataView.setOnClickListener(null);
            }
        }

        @Override
        public void onLoaderReset(final Loader<Cursor> loader) {
        }
    };

    private boolean mGuardViewportChangeListener;
    private boolean mDeferResetViewport;

    private DrawInsetsFrameLayout mContainerView;
    private View mChromeContainerView;
    private View mStatusBarScrimView;
    private View mMetadataView;
    private TextView mTitleView;
    private TextView mBylineView;
    private TextView mAttributionView;
    private PanScaleProxyView mPanScaleProxyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.full_screen_activity);

        mContainerView = (DrawInsetsFrameLayout) findViewById(R.id.container);


        mChromeContainerView = findViewById(R.id.chrome_container);
        mStatusBarScrimView = findViewById(R.id.statusbar_scrim);

        mChromeContainerView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                0xaa000000, 8, Gravity.BOTTOM));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            mStatusBarScrimView.setVisibility(View.GONE);
            mStatusBarScrimView = null;
        } else {
            mStatusBarScrimView.setBackground(ScrimUtil.makeCubicGradientScrimDrawable(
                    0x44000000, 8, Gravity.TOP));
        }

        mMetadataView = findViewById(R.id.metadata);

        final float metadataSlideDistance = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        mContainerView.setOnSystemUiVisibilityChangeListener(
                new View.OnSystemUiVisibilityChangeListener() {
                    @Override
                    public void onSystemUiVisibilityChange(int vis) {
                        final boolean visible = (vis & View.SYSTEM_UI_FLAG_LOW_PROFILE) == 0;

                        mChromeContainerView.setVisibility(View.VISIBLE);
                        mChromeContainerView.animate()
                                .alpha(visible ? 1f : 0f)
                                .translationY(visible ? 0 : metadataSlideDistance)
                                .setDuration(200)
                                .withEndAction(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (!visible) {
                                            mChromeContainerView.setVisibility(View.GONE);
                                        }
                                    }
                                });

                        if (mStatusBarScrimView != null) {
                            mStatusBarScrimView.setVisibility(View.VISIBLE);
                            mStatusBarScrimView.animate()
                                    .alpha(visible ? 1f : 0f)
                                    .setDuration(200)
                                    .withEndAction(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!visible) {
                                                mStatusBarScrimView.setVisibility(View.GONE);
                                            }
                                        }
                                    });
                        }
                    }
                });

        mTitleView = (TextView) findViewById(R.id.title);
        mBylineView = (TextView) findViewById(R.id.byline);
        mAttributionView = (TextView) findViewById(R.id.attribution);

        mPanScaleProxyView = (PanScaleProxyView) findViewById(R.id.pan_scale_proxy);
        mPanScaleProxyView.setMaxZoom(5);
        mPanScaleProxyView.setOnViewportChangedListener(
                new PanScaleProxyView.OnViewportChangedListener() {
                    @Override
                    public void onViewportChanged() {
                        if (mGuardViewportChangeListener) {
                            return;
                        }

                        ArtDetailViewport.getInstance().setViewport(
                                mCurrentViewportId, mPanScaleProxyView.getCurrentViewport(), true);
                    }
                });
        mPanScaleProxyView.setOnOtherGestureListener(
                new PanScaleProxyView.OnOtherGestureListener() {
                    @Override
                    public void onSingleTapUp() {
                        showHideChrome((mContainerView.getSystemUiVisibility()
                                & View.SYSTEM_UI_FLAG_LOW_PROFILE) != 0);
                    }
                });

        mContainerView.setOnInsetsCallback(new DrawInsetsFrameLayout.OnInsetsCallback() {
            @Override
            public void onInsetsChanged(Rect insets) {
                mChromeContainerView.setPadding(
                        insets.left, insets.top, insets.right, insets.bottom);
            }
        });

        showHideChrome(true);

        EventBus.getDefault().register(this);

        WallpaperSizeChangedEvent wsce = EventBus.getDefault().getStickyEvent(
                WallpaperSizeChangedEvent.class);
        if (wsce != null) {
            onEventMainThread(wsce);
        }

        ArtworkSizeChangedEvent asce = EventBus.getDefault().getStickyEvent(
                ArtworkSizeChangedEvent.class);
        if (asce != null) {
            onEventMainThread(asce);
        }

        ArtDetailViewport fve = EventBus.getDefault().getStickyEvent(ArtDetailViewport.class);
        if (fve != null) {
            onEventMainThread(fve);
        }

        SwitchingPhotosStateChangedEvent spsce = EventBus.getDefault().getStickyEvent(
                SwitchingPhotosStateChangedEvent.class);
        if (spsce != null) {
            onEventMainThread(spsce);
        }

        getSupportLoaderManager().initLoader(1, null, mArtworkLoaderCallbacks);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    private void showHideChrome(boolean show) {
        int flags = show ? 0 : View.SYSTEM_UI_FLAG_LOW_PROFILE;
        flags |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!show) {
            flags |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
        }
        mContainerView.setSystemUiVisibility(flags);
    }

    @Subscribe
    public void onEventMainThread(WallpaperSizeChangedEvent wsce) {
        if (wsce.getHeight() > 0) {
            mWallpaperAspectRatio = wsce.getWidth() * 1f / wsce.getHeight();
        } else {
            mWallpaperAspectRatio = mPanScaleProxyView.getWidth()
                    * 1f / mPanScaleProxyView.getHeight();
        }
        resetProxyViewport();
    }

    @Subscribe
    public void onEventMainThread(ArtworkSizeChangedEvent ase) {
        mArtworkAspectRatio = ase.getWidth() * 1f / ase.getHeight();
        resetProxyViewport();
    }

    private void resetProxyViewport() {
        if (mWallpaperAspectRatio == 0 || mArtworkAspectRatio == 0) {
            return;
        }

        mDeferResetViewport = false;
        SwitchingPhotosStateChangedEvent spe = EventBus.getDefault()
                .getStickyEvent(SwitchingPhotosStateChangedEvent.class);
        if (spe != null && spe.isSwitchingPhotos()) {
            mDeferResetViewport = true;
            return;
        }

        mPanScaleProxyView.setRelativeAspectRatio(mArtworkAspectRatio / mWallpaperAspectRatio);
    }

    @Subscribe
    public void onEventMainThread(ArtDetailViewport e) {
        if (!e.isFromUser()) {
            mGuardViewportChangeListener = true;
            mPanScaleProxyView.setViewport(e.getViewport(mCurrentViewportId));
            mGuardViewportChangeListener = false;
        }
    }

    @Subscribe
    public void onEventMainThread(SwitchingPhotosStateChangedEvent spe) {
        mCurrentViewportId = spe.getCurrentId();
        mPanScaleProxyView.enablePanScale(!spe.isSwitchingPhotos());
        // Process deferred artwork size change when done switching
        if (!spe.isSwitchingPhotos() && mDeferResetViewport) {
            resetProxyViewport();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        ArtDetailOpenedClosedEvent adoce = EventBus.getDefault()
                .getStickyEvent(ArtDetailOpenedClosedEvent.class);
        if (adoce.isArtDetailOpened()) {
            EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(false));
        }
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();

        ArtDetailOpenedClosedEvent adoce = EventBus.getDefault()
                .getStickyEvent(ArtDetailOpenedClosedEvent.class);
        if (!adoce.isArtDetailOpened()) {
            EventBus.getDefault().postSticky(new ArtDetailOpenedClosedEvent(true));
        }
        mChromeContainerView.setVisibility(View.VISIBLE);
        if (mStatusBarScrimView != null) {
            mStatusBarScrimView.setVisibility(View.VISIBLE);
        }

        // Note: normally should use window animations for this, but there's a bug
        // on Samsung devices where the wallpaper is animated along with the window for
        // windows showing the wallpaper (the wallpaper _should_ be static, not part of
        // the animation).
        View decorView = getWindow().getDecorView();
        decorView.setAlpha(0f);
        decorView.animate().cancel();
        decorView.animate()
                .setStartDelay(500)
                .alpha(1f)
                .setDuration(300);
    }
}
