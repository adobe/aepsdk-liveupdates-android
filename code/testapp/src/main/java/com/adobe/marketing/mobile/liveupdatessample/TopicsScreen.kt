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

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.delay

/**
 * Sample UI for managing FCM topic subscriptions. Interacts with
 * [FirebaseMessaging] directly - topic management is intentionally kept out of the Live
 * Updates SDK's public surface and lives in the application layer.
 *
 * TODO(topics-list): Firebase's client SDK does not expose the list of topics a device
 * is currently subscribed to. To render the "currently subscribed" list, the app needs to
 * call the Instance ID REST API:
 *
 *   GET https://iid.googleapis.com/iid/info/<FCM_TOKEN>?details=true
 *   Authorization: Bearer <oauth2_access_token>
 *
 * The response's `rel.topics` map holds the server-authoritative subscription list. That
 * requires an OAuth2 access token minted from the Firebase service-account key; wire this
 * in when the demo has a way to source the token securely (a paste field or bundled
 * service account with local JWT signing). For now the screen only exposes the two
 * mutating operations.
 */
@Composable
fun TopicsScreen() {
    val context = LocalContext.current
    var topicInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<TopicStatus?>(null) }

    // Auto-dismiss the result snackbar after a short delay. Loading state stays until the
    // FirebaseMessaging task completes and replaces it.
    LaunchedEffect(status) {
        val current = status
        if (current is TopicStatus.Result) {
            delay(RESULT_DISMISS_MS)
            if (status === current) status = null
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                text = "FCM Topic Subscriptions",
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Currently-subscribed topic list is not shown - Firebase's client SDK does not expose it. Use the Instance ID REST API on the backend when a read view is needed.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = topicInput,
                onValueChange = { topicInput = it },
                label = { Text("Topic name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = {
                        val topic = topicInput.trim().ifEmpty {
                            status = TopicStatus.Result("Enter a topic name first.", success = false)
                            return@Button
                        }
                        if (!context.isOnline()) {
                            status = TopicStatus.Result(NO_INTERNET_MESSAGE, success = false)
                            return@Button
                        }
                        status = TopicStatus.Loading("Subscribing to \"$topic\"...")
                        FirebaseMessaging.getInstance().subscribeToTopic(topic)
                            .addOnCompleteListener { task ->
                                status = task.toResult(
                                    okMessage = "Subscribed to \"$topic\"",
                                    failPrefix = "Failed to subscribe to \"$topic\""
                                )
                                Log.d(TAG, "subscribeToTopic($topic) -> ${task.isSuccessful}", task.exception)
                            }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { Text("Subscribe") }

                Button(
                    onClick = {
                        val topic = topicInput.trim().ifEmpty {
                            status = TopicStatus.Result("Enter a topic name first.", success = false)
                            return@Button
                        }
                        if (!context.isOnline()) {
                            status = TopicStatus.Result(NO_INTERNET_MESSAGE, success = false)
                            return@Button
                        }
                        status = TopicStatus.Loading("Unsubscribing from \"$topic\"...")
                        FirebaseMessaging.getInstance().unsubscribeFromTopic(topic)
                            .addOnCompleteListener { task ->
                                status = task.toResult(
                                    okMessage = "Unsubscribed from \"$topic\"",
                                    failPrefix = "Failed to unsubscribe from \"$topic\""
                                )
                                Log.d(TAG, "unsubscribeFromTopic($topic) -> ${task.isSuccessful}", task.exception)
                            }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) { Text("Unsubscribe") }
            }
        }

        status?.let { current ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (current is TopicStatus.Loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(current.message)
                }
            }
        }
    }
}

/** Snackbar state for topic subscribe / unsubscribe operations. */
private sealed class TopicStatus(val message: String) {
    /** Operation in flight - snackbar shows a spinner and stays visible until the result arrives. */
    class Loading(message: String) : TopicStatus(message)

    /** Operation completed - snackbar shows the outcome and auto-dismisses. */
    class Result(message: String, val success: Boolean) : TopicStatus(message)
}

/**
 * Converts a completed Firebase [com.google.android.gms.tasks.Task] into a
 * [TopicStatus.Result], surfacing the underlying exception when the task failed so the
 * user sees the concrete reason (rate-limit, invalid topic name, missing token, etc.).
 */
private fun com.google.android.gms.tasks.Task<Void>.toResult(
    okMessage: String,
    failPrefix: String
): TopicStatus.Result = if (isSuccessful) {
    TopicStatus.Result(okMessage, success = true)
} else {
    val err = exception
    val detail = err?.let { "${it.javaClass.simpleName}: ${it.localizedMessage ?: "no message"}" }
        ?: "unknown error (task not successful, no exception)"
    TopicStatus.Result("$failPrefix - $detail", success = false)
}

/**
 * Reports whether the device currently has a validated internet connection. Used as a
 * preflight before firing Firebase topic mutations, since `FirebaseMessaging.subscribeToTopic`
 * queues the request offline and never invokes its completion listener - which would leave
 * the UI stuck on the loading snackbar. Requires the ACCESS_NETWORK_STATE permission
 * declared in AndroidManifest.
 */
private fun Context.isOnline(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

private const val TAG = "TopicsScreen"
private const val RESULT_DISMISS_MS = 3500L
private const val NO_INTERNET_MESSAGE = "No internet connection. Connect to a network and try again."
