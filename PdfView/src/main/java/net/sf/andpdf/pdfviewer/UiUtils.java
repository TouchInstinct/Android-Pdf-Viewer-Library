package net.sf.andpdf.pdfviewer;
/*
 *  Copyright (c) 2015 RoboSwag (Gavriil Sitnikov, Vsevolod Ivanov)
 *
 *  This file is part of RoboSwag library.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.IdRes;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import java.util.concurrent.atomic.AtomicInteger;

public final class UiUtils {



    private UiUtils() {
    }

    /**
     * Utilities methods related to metrics.
     */
    public static class OfMetrics {

        private static final int MAX_METRICS_TRIES_COUNT = 5;

        /**
         * Returns right metrics with non-zero height/width.
         * It is common bug when metrics are calling at {@link Application#onCreate()} method and it returns metrics with zero height/width.
         *
         * @param context {@link Context} of metrics;
         * @return {@link DisplayMetrics}.
         */
        @SuppressWarnings("BusyWait")
        @NonNull
        public static DisplayMetrics getDisplayMetrics(@NonNull final Context context) {
            DisplayMetrics result = context.getResources().getDisplayMetrics();
            // it is needed to avoid bug with invalid metrics when user restore application from other application
            int metricsTryNumber = 0;
            while (metricsTryNumber < MAX_METRICS_TRIES_COUNT && (result.heightPixels <= 0 || result.widthPixels <= 0)) {
                try {
                    Thread.sleep(500);
                } catch (final InterruptedException ignored) {
                    return result;
                }
                result = context.getResources().getDisplayMetrics();
                metricsTryNumber++;
            }
            return result;
        }

        /**
         * Simply converts DP to pixels.
         *
         * @param context  {@link Context} of metrics;
         * @param sizeInDp Size in DP;
         * @return Size in pixels.
         */
        public static float dpToPixels(@NonNull final Context context, final float sizeInDp) {
            return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, sizeInDp, getDisplayMetrics(context));
        }

        private OfMetrics() {
        }

    }
}

