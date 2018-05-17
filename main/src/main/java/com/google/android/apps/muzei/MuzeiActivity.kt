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

package com.google.android.apps.muzei

import android.app.WallpaperManager
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.view.View
import androidx.navigation.NavController
import androidx.navigation.findNavController
import com.google.android.apps.muzei.wallpaper.WallpaperActiveState
import com.google.firebase.analytics.FirebaseAnalytics
import net.nurik.roman.muzei.BuildConfig
import net.nurik.roman.muzei.R

class MuzeiActivity : AppCompatActivity() {
    private var fadeIn = false

    private val navigatedListener = NavController.OnNavigatedListener { _, destination ->
        when (destination.id) {
            R.id.main_fragment -> {
                FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Main",
                        MainFragment::class.java.simpleName)
            }
            R.id.tutorial_fragment -> {
                FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Tutorial",
                    TutorialFragment::class.java.simpleName)
            }
            R.id.intro_fragment -> {
                FirebaseAnalytics.getInstance(this).setCurrentScreen(this, "Intro",
                        IntroFragment::class.java.simpleName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.muzei_activity)
        FirebaseAnalytics.getInstance(this).setUserProperty("device_type", BuildConfig.DEVICE_TYPE)
        val containerView = findViewById<View>(R.id.container)

        containerView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        containerView.findNavController().addOnNavigatedListener(navigatedListener)

        if (savedInstanceState == null) {
            if (WallpaperActiveState.value != true) {
                // Double check to make sure we aren't just in the processing of being started
                val wallpaperManager = WallpaperManager.getInstance(this)
                if (wallpaperManager.wallpaperInfo?.packageName == packageName) {
                    // Ah, we are the active wallpaper. We'll just mark ourselves as active here
                    // to skip the Intro screen
                    WallpaperActiveState.value = true
                }
            }
            fadeIn = true
        }
    }

    override fun onPostResume() {
        super.onPostResume()

        if (fadeIn) {
            // Note: normally should use window animations for this, but there's a bug
            // on Samsung devices where the wallpaper is animated along with the window for
            // windows showing the wallpaper (the wallpaper _should_ be static, not part of
            // the animation).
            window.decorView.run {
                alpha = 0f
                animate().cancel()
                animate().setStartDelay(500).alpha(1f).duration = 300
            }

            fadeIn = false
        }
    }
}
