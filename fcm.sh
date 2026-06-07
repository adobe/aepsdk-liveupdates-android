#!/bin/bash
#
# Sends a sequence of Live Update pushes to a single device via FCM v1 API.
# Mirrors the APNS Live Activity lifecycle: 1× start, 2× update, 1× end.
# All four stages share the same live_update_id, so each push updates the same chip.
#
# Payload shape (FCM data map):
#   _xdm                  — AJO marker (satisfies isAJONotification)
#   adb_title / adb_body  — standard Messaging keys, parsed by MessagingPushBuilder
#   adb_channel_id        — HIGH-importance channel for promotion eligibility
#   adb_n_priority        — PRIORITY_HIGH
#   adb_live_update_data  — JSON-stringified envelope:
#       live_update_id            (required)
#       live_update_template_type ("progress" — drives ProgressStyle)
#       live_update_event         ("start" | "update" | "end")
#       live_update_timestamp     (unix sec)
#       live_update_dismiss_at    (unix sec, only on "end")
#       live_update_critical_text (status bar chip short text)
#       live_update_content_state ({"journeyProgress": N, ...})
#
# Usage:
#   ./fcm.sh <FCM_TOKEN> [DELAY_SECONDS]
#
# Get FCM_TOKEN from the running app's UI ("Copy FCM token") or:
#   adb logcat -s LiveUpdateSample
#
# Override the live update id (chip identity) with:
#   LIVE_UPDATE_ID=flight_AA241 ./fcm.sh <token>

set -euo pipefail

PROJECT_ID="collabpoc-b074b"
SERVICE_ACCOUNT_KEY="$(dirname "$0")/fcm-key.json"

FCM_TOKEN="${1:-}"
DELAY="${2:-3}"
LIVE_UPDATE_ID="${LIVE_UPDATE_ID:-flight_demo_001}"

if [ -z "$FCM_TOKEN" ]; then
    echo "❌ Usage: $0 <FCM_TOKEN> [DELAY_SECONDS]"
    exit 1
fi

if [ ! -f "$SERVICE_ACCOUNT_KEY" ]; then
    echo "❌ Missing service account key: $SERVICE_ACCOUNT_KEY"
    exit 1
fi

echo "🔐 Authenticating with service account..."
gcloud auth activate-service-account --key-file="$SERVICE_ACCOUNT_KEY" > /dev/null

echo "🎟️  Generating access token..."
ACCESS_TOKEN=$(gcloud auth print-access-token)
if [ -z "$ACCESS_TOKEN" ]; then
    echo "❌ Failed to generate access token"
    exit 1
fi

# send_stage TITLE BODY CRITICAL_TEXT PROGRESS EVENT [DISMISS_AFTER_SECONDS]
#
# CRITICAL_TEXT shows in the status bar chip.
# DISMISS_AFTER_SECONDS is a relative duration; meaningful only when EVENT == "end".
send_stage() {
    local title="$1"
    local body="$2"
    local critical="$3"
    local progress="$4"
    local event="$5"
    local dismiss_after="${6:-}"

    local dismiss_after_field=""
    if [ -n "$dismiss_after" ] && [ "$event" = "end" ]; then
        dismiss_after_field=",\"live_update_dismiss_after\":$dismiss_after"
    fi

    local envelope
    envelope="{\"live_update_id\":\"$LIVE_UPDATE_ID\",\"live_update_template_type\":\"progress\",\"live_update_event\":\"$event\",\"live_update_critical_text\":\"$critical\",\"live_update_content_state\":{\"journeyProgress\":$progress}$dismiss_after_field}"

    # Escape double-quotes for embedding inside the outer curl JSON.
    local envelope_escaped
    envelope_escaped=$(printf '%s' "$envelope" | sed 's/"/\\"/g')

    echo "📤 Stage: event=$event  progress=$progress  '$title'"

    local response
    response=$(curl -s -X POST \
        "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send" \
        -H "Authorization: Bearer $ACCESS_TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{
          \"message\": {
            \"token\": \"$FCM_TOKEN\",
            \"android\": { \"priority\": \"HIGH\" },
            \"data\": {
              \"_xdm\": \"{}\",
              \"adb_title\": \"$title\",
              \"adb_body\": \"$body\",
              \"adb_channel_id\": \"live_updates_channel\",
              \"adb_n_priority\": \"PRIORITY_HIGH\",
              \"adb_live_update_data\": \"$envelope_escaped\"
            }
          }
        }")

    if echo "$response" | grep -q "\"name\""; then
        echo "   ✅ Delivered"
    else
        echo "   ❌ Send failed: $response"
        exit 1
    fi
}

echo "🛫 Live Update demo — chip id=$LIVE_UPDATE_ID, delay=${DELAY}s"
echo "=========================================="

send_stage "Flight on time"     "Boarding starts shortly"       "25 min" 10  "start"
sleep "$DELAY"
send_stage "Boarding"           "Gate D22, boarding now"        "Now"    40  "update"
sleep "$DELAY"
send_stage "In flight"          "Estimated landing 3:45 PM"     "1h 15m" 70  "update"
sleep "$DELAY"
# end event with auto-dismiss 5 seconds after delivery (dismiss_at = now + 5 computed inside send_stage)
send_stage "Landed"             "Welcome to MUM"                "Done"   100 "end" 5

echo "=========================================="
echo "🎉 All 4 stages sent. The chip should have updated in place,"
echo "   then auto-dismissed 5s after the 'end' event."
