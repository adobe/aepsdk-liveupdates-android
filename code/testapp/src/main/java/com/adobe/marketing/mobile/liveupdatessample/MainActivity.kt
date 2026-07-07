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

package com.adobe.marketing.mobile.liveupdatessample

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.adobe.marketing.mobile.Assurance
import com.adobe.marketing.mobile.Messaging
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "LiveUpdateSample"
    }

    private var fcmToken by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // LiveUpdate wiring lives in LiveUpdatesApplication.onCreate now - single call to
        // Messaging.setLiveUpdateHandler(LiveUpdateHandlerImpl(...)).
        requestNotificationPermission()
        fetchFcmToken()

        // Handle a notification tap that launched (or re-fronted) this activity.
        // No-ops if the intent has no AEP tracking extras.
        fireTapTrackingIfAny(intent)

        // Handle Assurance deep-link (lutest://...?adb_validation_sessionid=...).
        startAssuranceSessionIfAny(intent)

        setContent {
            var showTopics by remember { mutableStateOf(false) }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showTopics) {
                        TopicsScreen()
                        // Back navigation is handled by the system back gesture returning to
                        // the home screen; the button below the FCM token flips the flag.
                    } else {
                        LiveUpdateInfoScreen(
                            fcmToken = fcmToken,
                            onOpenTopics = { showTopics = true }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Called when the activity is reused (launchMode="singleTop"). Fire tracking for
        // the new tap. The new intent will already carry the messageId + xdm extras
        // injected by SampleNotificationService via Messaging.addPushTrackingDetails.
        setIntent(intent)
        fireTapTrackingIfAny(intent)
        startAssuranceSessionIfAny(intent)
    }

    /**
     * Starts an Assurance session if [incoming] is the deep-link configured for this app
     * and carries the `adb_validation_sessionid` query param. No-op for any other intent.
     */
    private fun startAssuranceSessionIfAny(incoming: Intent?) {
        val data = incoming?.data ?: return
        if (data.scheme.equals("lutest", ignoreCase = true) &&
            data.getQueryParameter("adb_validation_sessionid") != null
        ) {
            Log.d(TAG, "Starting Assurance session for $data")
            Assurance.startSession(data.toString())
        }
    }

    /**
     * Fires a push-tap tracking event if the intent carries AEP tracking extras
     * (messageId + adobe_xdm), injected upstream by [Messaging.addPushTrackingDetails].
     * Internally a no-op when those extras are absent, so it is safe to call on every
     * onCreate / onNewIntent unconditionally.
     */
    private fun fireTapTrackingIfAny(incoming: Intent?) {
        if (incoming == null) return
        Messaging.handleNotificationResponse(incoming, /* applicationOpened = */ true, /* customActionId = */ null)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    private fun fetchFcmToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Log.w(TAG, "Fetching FCM token failed", task.exception)
                return@addOnCompleteListener
            }
            val token = task.result
            fcmToken = token
            // Clearly tagged so it's easy to grep from logcat:
            //   adb logcat -s LiveUpdateSample
            Log.d(TAG, "FCM_TOKEN=$token")
        }
    }
}

@Composable
private fun LiveUpdateInfoScreen(fcmToken: String?, onOpenTopics: () -> Unit) {
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Live Updates sample",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = if (fcmToken != null) "✅ FCM token ready" else "⏳ Loading FCM token…",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (fcmToken != null) {
            Text(
                text = fcmToken,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { copyToClipboard(context, fcmToken, "FCM token") }) {
                Text("Copy FCM token")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    copyToClipboard(context, "./fcm.sh $fcmToken", "fcm.sh command")
                }
            ) {
                Text("Copy ./fcm.sh <token> command")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Run the copied command from the repo root to send a Live Update push.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenTopics) {
            Text("Manage FCM topics")
        }
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
