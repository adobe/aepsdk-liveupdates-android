/*
  Copyright 2026 Adobe. All rights reserved.
  This file is licensed to you under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License. You may obtain a copy
  of the License at http://www.apache.org/licenses/LICENSE-2.0
  Unless required by applicable law or agreed to in writing, software distributed under
  the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS
  OF ANY KIND, either express or implied. See the License for the specific language
  governing permissions and limitations under the License.
*/

@file:Suppress("RestrictedApi")

package com.adobe.marketing.mobile.liveupdatessample

import android.annotation.SuppressLint
import android.app.Notification
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationBuilderWithBuilderAccessor
import androidx.core.app.NotificationCompat

/**
 * Bridges the platform [android.app.Notification.MetricStyle] (API 37+) into a
 * [NotificationCompat.Style] so it can be returned from [SampleLiveUpdateStyleProvider]
 * and attached to the SDK's [NotificationCompat.Builder].
 *
 * The override of [apply] receives a [NotificationBuilderWithBuilderAccessor], which
 * exposes the underlying platform [android.app.Notification.Builder]. The platform
 * `MetricStyle` is built reflectively (it is not part of the AndroidX compat surface
 * yet) and set on the platform builder directly. NotificationCompat is unaware of the
 * concrete style; the resulting [android.app.Notification] carries the real MetricStyle.
 *
 * Callers must gate construction of this class on `Build.VERSION.SDK_INT >= 37` and
 * supply an alternative [NotificationCompat.Style] for older devices.
 *
 * Replace this class with [NotificationCompat.MetricStyle] once AndroidX core ships it.
 */
@SuppressLint("RestrictedApi")
class MetricStyleCompat(
    private val homeTeam: CharSequence,
    private val awayTeam: CharSequence,
    private val homeScore: Int,
    private val awayScore: Int,
    private val matchTime: CharSequence,
    private val criticalMetricIndex: Int = 0
) : NotificationCompat.Style() {

    override fun apply(builder: NotificationBuilderWithBuilderAccessor) {
        if (Build.VERSION.SDK_INT < 37) return
        val platformBuilder: Notification.Builder = builder.builder
        val metricStyle = buildPlatformMetricStyle() ?: return
        try {
            Notification.Builder::class.java
                .getMethod("setStyle", Notification.Style::class.java)
                .invoke(platformBuilder, metricStyle)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to attach MetricStyle: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * Reflectively builds a platform `Notification.MetricStyle` populated with the
     * home score, away score, and match time. The metric at [criticalMetricIndex] is
     * marked as the chip-visible headline. Returns null if the runtime does not expose
     * the required platform classes.
     */
    private fun buildPlatformMetricStyle(): Notification.Style? = try {
        val metricStyleCls = Class.forName("android.app.Notification\$MetricStyle")
        val metricCls = Class.forName("android.app.Notification\$Metric")
        val fixedIntCls = Class.forName("android.app.Notification\$Metric\$FixedInt")
        val fixedTextCls = Class.forName("android.app.Notification\$Metric\$FixedText")
        val metricValueCls = Class.forName("android.app.Notification\$Metric\$MetricValue")

        val fixedIntCtor = fixedIntCls.getConstructor(Int::class.javaPrimitiveType)
        val fixedTextCtor = fixedTextCls.getConstructor(CharSequence::class.java)
        val metricCtor = metricCls.getConstructor(metricValueCls, CharSequence::class.java)

        val homeMetric = metricCtor.newInstance(fixedIntCtor.newInstance(homeScore), homeTeam)
        val awayMetric = metricCtor.newInstance(fixedIntCtor.newInstance(awayScore), awayTeam)
        val timeMetric = metricCtor.newInstance(fixedTextCtor.newInstance(matchTime), "Time" as CharSequence)

        val style = metricStyleCls.getConstructor().newInstance()
        val addMetric = metricStyleCls.getMethod("addMetric", metricCls)
        addMetric.invoke(style, homeMetric)
        addMetric.invoke(style, awayMetric)
        addMetric.invoke(style, timeMetric)

        metricStyleCls.getMethod("setCriticalMetric", Int::class.javaPrimitiveType)
            .invoke(style, criticalMetricIndex)

        style as Notification.Style
    } catch (e: Exception) {
        Log.w(TAG, "Reflective MetricStyle build failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "MetricStyleCompat"
    }
}
