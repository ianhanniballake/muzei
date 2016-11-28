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
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class ImageUtil {
    private static final String TAG = "ImageUtil";

    // Make sure input images are very small!
    public static float calculateDarkness(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        int totalLum = 0;
        int n = 0;
        int x, y, color;
        for (y = 0; y < height; y++) {
            for (x = 0; x < width; x++) {
                ++n;
                color = bitmap.getPixel(x, y);
                totalLum += (0.21f * Color.red(color)
                        + 0.71f * Color.green(color)
                        + 0.07f * Color.blue(color));
            }
        }

        return (totalLum / n) / 256f;
    }

    private ImageUtil() {
    }

    public static int calculateSampleSize(int rawSize, int targetSize) {
        int sampleSize = 1;
        while (rawSize / (sampleSize << 1) > targetSize) {
            sampleSize <<= 1;
        }
        return sampleSize;
    }

    public static class RotationAsyncTask extends AsyncTask<Uri, Void, Integer> {
        private final Context mContext;

        public RotationAsyncTask(Context context) {
            mContext = context;
        }

        @Override
        protected Integer doInBackground(Uri... uris) {
            int rotation = 0;
            try {
                InputStream in = mContext.getContentResolver().openInputStream(uris[0]);
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
            return rotation;
        }

        private File writeArtworkToFile(InputStream in) throws IOException {
            File file = new File(mContext.getCacheDir(), "temp_artwork");
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
    }
}
