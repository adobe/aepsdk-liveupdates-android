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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.adobe.marketing.mobile.services.HttpMethod
import com.adobe.marketing.mobile.services.NetworkRequest
import com.adobe.marketing.mobile.services.ServiceProvider
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Sample UI for managing FCM topic subscriptions. Interacts with [FirebaseMessaging]
 * directly for mutations and with the Firebase Instance ID REST API (via Adobe Core's
 * shared network service) to fetch the server-side subscription list.
 *
 * The current-subscription list is fetched only when the user taps "Refresh list" and
 * pastes an OAuth2 access token; nothing is persisted locally. The token is not stored
 * across dialog dismissals - the user must paste it again on every refresh.
 */
@Composable
fun TopicsScreen(fcmToken: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var topicInput by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<TopicStatus?>(null) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var isFetching by remember { mutableStateOf(false) }
    var topics by remember { mutableStateOf<List<String>?>(null) }

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
                text = "Firebase's client SDK does not expose subscribed topics. Tap 'Refresh list' and paste an OAuth2 access token to fetch them via the Instance ID REST API.",
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

            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    if (!context.isOnline()) {
                        status = TopicStatus.Result(NO_INTERNET_MESSAGE, success = false)
                        return@Button
                    }
                    if (fcmToken.isNullOrEmpty()) {
                        status = TopicStatus.Result("FCM token not ready yet - go back to the home screen and wait for it.", success = false)
                        return@Button
                    }
                    showTokenDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Refresh list") }

            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.TopCenter
            ) {
                when {
                    isFetching -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Fetching subscribed topics...")
                    }
                    topics == null -> Text(
                        "Tap Refresh list to load your subscribed topics.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    topics!!.isEmpty() -> Text(
                        "No subscribed topics.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(topics!!) { topic ->
                            Text(
                                text = "•  $topic",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
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

    if (showTokenDialog) {
        AccessTokenDialog(
            onDismiss = { showTokenDialog = false },
            onFetch = { accessToken ->
                showTokenDialog = false
                val safeFcmToken = fcmToken ?: return@AccessTokenDialog
                scope.launch {
                    isFetching = true
                    val result = withContext(Dispatchers.IO) {
                        fetchSubscribedTopics(safeFcmToken, accessToken)
                    }
                    isFetching = false
                    result.fold(
                        onSuccess = { topics = it },
                        onFailure = { err ->
                            status = TopicStatus.Result(
                                message = "Refresh failed - ${err.javaClass.simpleName}: ${err.localizedMessage ?: "no message"}",
                                success = false
                            )
                        }
                    )
                }
            }
        )
    }
}

@Composable
private fun AccessTokenDialog(
    onDismiss: () -> Unit,
    onFetch: (String) -> Unit
) {
    var tokenInput by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OAuth2 access token") },
        text = {
            Column {
                Text(
                    text = "Paste an OAuth2 access token authorized to call the Firebase Cloud Messaging Instance ID API. The token is not stored - you'll need to paste it again on every refresh.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    label = { Text("Bearer token") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = tokenInput.isNotBlank(),
                onClick = { onFetch(tokenInput.trim()) }
            ) { Text("Fetch") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Fetches the list of FCM topics [fcmToken] is currently subscribed to by calling the
 * Firebase Instance ID REST API. Uses Adobe Core's shared [com.adobe.marketing.mobile.services.Networking]
 * service so we do not add a separate HTTP dependency to the sample.
 *
 * Endpoint:
 *   GET https://iid.googleapis.com/iid/info/<fcmToken>?details=true
 *   Authorization: Bearer <oauth2AccessToken>
 *
 * The response's `rel.topics` map holds the server-authoritative subscription list.
 */
private suspend fun fetchSubscribedTopics(
    fcmToken: String,
    oauth2AccessToken: String
): Result<List<String>> = suspendCancellableCoroutine { cont ->
    val request = NetworkRequest(
        "https://iid.googleapis.com/iid/info/$fcmToken?details=true",
        HttpMethod.GET,
        null,
        mapOf(
            "Authorization" to "Bearer $oauth2AccessToken",
            "access_token_auth" to "true"
        ),
        REQUEST_TIMEOUT_SEC,
        REQUEST_TIMEOUT_SEC
    )
    ServiceProvider.getInstance().networkService.connectAsync(request) { connection ->
        try {
            if (connection == null) {
                cont.resume(Result.failure(IOException("Network service returned no connection")))
                return@connectAsync
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val errBody = connection.errorStream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                cont.resume(
                    Result.failure(
                        IOException("HTTP $code${if (errBody.isNotEmpty()) ": ${errBody.take(200)}" else ""}")
                    )
                )
                return@connectAsync
            }
            val bodyText = connection.inputStream
                .bufferedReader()
                .use { it.readText() }
            val topicsObj = JSONObject(bodyText).optJSONObject("rel")?.optJSONObject("topics")
            val topicNames = topicsObj?.keys()?.asSequence()?.toList()?.sorted() ?: emptyList()
            cont.resume(Result.success(topicNames))
        } catch (e: Exception) {
            cont.resume(Result.failure(e))
        } finally {
            try { connection?.close() } catch (_: Exception) { /* ignore */ }
        }
    }
}

/** Snackbar state for topic subscribe / unsubscribe operations. */
private sealed class TopicStatus(val message: String) {
    class Loading(message: String) : TopicStatus(message)
    class Result(message: String, val success: Boolean) : TopicStatus(message)
}

/**
 * Converts a completed Firebase [com.google.android.gms.tasks.Task] into a
 * [TopicStatus.Result], surfacing the underlying exception when the task failed so the
 * user sees the concrete reason.
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
 * Reports whether the device currently has a validated internet connection.
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
private const val REQUEST_TIMEOUT_SEC = 10
private const val NO_INTERNET_MESSAGE = "No internet connection. Connect to a network and try again."
