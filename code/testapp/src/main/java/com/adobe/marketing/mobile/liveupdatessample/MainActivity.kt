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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
        // LiveUpdate wiring lives in LiveUpdatesApplication.onCreate now — single call to
        // Messaging.setLiveUpdateHandler(LiveUpdateRenderer(...)).
        requestNotificationPermission()
        fetchFcmToken()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LiveUpdateInfoScreen(fcmToken = fcmToken)
                }
            }
        }
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
private fun LiveUpdateInfoScreen(fcmToken: String?) {
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
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
