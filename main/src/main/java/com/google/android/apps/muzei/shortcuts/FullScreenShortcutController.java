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

package com.google.android.apps.muzei.shortcuts;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.provider.BaseColumns;
import android.support.annotation.RequiresApi;
import android.support.v4.app.TaskStackBuilder;

import com.google.android.apps.muzei.FullScreenActivity;
import com.google.android.apps.muzei.api.MuzeiContract;

import net.nurik.roman.muzei.R;

import java.util.Collections;

/**
 * Static controller for adding the Full Screen Shortcut when there is valid artwork
 */
@RequiresApi(api = Build.VERSION_CODES.N_MR1)
public class FullScreenShortcutController {
    private static final String FULL_SCREEN_SHORTCUT_ID = "full_screen";

    public static void updateShortcut(Context context) {
        Cursor data = context.getContentResolver().query(MuzeiContract.Artwork.CONTENT_URI,
                new String[] {BaseColumns._ID}, null, null, null);
        if (data == null) {
            return;
        }
        boolean hasArtwork = data.getCount() > 0;
        data.close();
        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);

        if (hasArtwork) {
            Intent fullScreenIntent = new Intent(Intent.ACTION_VIEW);
            fullScreenIntent.setClass(context, FullScreenActivity.class);
            TaskStackBuilder taskStackBuilder = TaskStackBuilder.create(context)
                    .addNextIntentWithParentStack(fullScreenIntent);
            ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(
                    context, FULL_SCREEN_SHORTCUT_ID)
                    .setIcon(Icon.createWithResource(context,
                            R.drawable.ic_shortcut_full_screen))
                    .setShortLabel(context.getString(R.string.full_screen_title))
                    .setIntents(taskStackBuilder.getIntents())
                    .build();
            shortcutManager.addDynamicShortcuts(
                    Collections.singletonList(shortcutInfo));
        }
        // No else statement since Muzei only goes from no artwork -> at least one artwork
    }

    private FullScreenShortcutController() {
    }
}
