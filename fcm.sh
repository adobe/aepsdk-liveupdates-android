#!/bin/bash
#
# Sends a sequence of FOUR production-shape Live Update pushes to a single device via FCM v1 API.
# All four stages share the same notification_id, so each push updates the same chip in place.
#
# Production payload shape (matches the design wiki and AJO authoring contract):
#   message.android.data._xdm                  - full AJO XDM mixins (messageExecution,
#                                                campaignID, decisioning, etc.). Opaque to
#                                                the on-device SDK; flows to AJO reporting
#                                                via Edge.
#   message.android.data.adb_liveupdate_data   - JSON-stringified Live Update envelope:
#       notification_id, channel_id, event_type, title  (REQUIRED v1.1)
#       priority, body, critical_text, when, dismiss_after, topic_name (optional)
#       action_type, action_uri, action_buttons[] (tap intent fields)
#       content_state{} (app-defined opaque object, parsed by StyleProvider)
#
# NOTE on data placement: this script puts data under `android.data` (Android-specific
# delivery), matching the production backend convention. The on-device SDK reads from
# RemoteMessage.getData() either way - FCM merges `message.data` and `message.android.data`
# transparently. Use `android.data` when the payload is Android-only (as Live Updates are).
#
# Usage:
#   ./fcm.sh <FCM_TOKEN> [DELAY_SECONDS]
#   NOTIFICATION_ID=flight_AA241 ./fcm.sh <token>

set -euo pipefail

PROJECT_ID="collabpoc-b074b"
SERVICE_ACCOUNT_KEY="$(dirname "$0")/fcm-key.json"

FCM_TOKEN="${1:-}"
DELAY="${2:-3}"
NOTIFICATION_ID="${NOTIFICATION_ID:-flight_DL241_2026_01_15}"
CHANNEL_ID="live_updates_channel"
TOPIC_NAME="flight_DL241"

if [ -z "$FCM_TOKEN" ]; then
    echo "Usage: $0 <FCM_TOKEN> [DELAY_SECONDS]"
    exit 1
fi
if [ ! -f "$SERVICE_ACCOUNT_KEY" ]; then
    echo "Missing service account key: $SERVICE_ACCOUNT_KEY"
    exit 1
fi

echo "Authenticating..."
gcloud auth activate-service-account --key-file="$SERVICE_ACCOUNT_KEY" > /dev/null
ACCESS_TOKEN=$(gcloud auth print-access-token)

# Full _xdm passthrough block - server-defined contents. Stripped to the AJO-relevant
# mixins for demo purposes; production payloads carry the entire customer journey
# management + decisioning XDM.
build_xdm() {
    local event_type="$1"
    cat <<EOF
{
  "mixins": {
    "_experience": {
      "customerJourneyManagement": {
        "messageExecution": {
          "messageExecutionID": "HUPU-59740365",
          "messageID": "6750d3a0-9ac7-4944-97d8-21ecbab9bc8a-0",
          "messageType": "transactional",
          "campaignID": "96008fbe-4dfa-445f-80d5-10287a54e948",
          "campaignVersionID": "843f2ea5-815d-466b-9754-ab5e57b3d8de",
          "campaignActionID": "3cbb4bb7-60af-439f-bdbe-216f14cbd47f",
          "batchInstanceID": "e37a8db9-1359-46ac-a67c-1266b275cc68"
        }
      },
      "decisioning": {
        "propositions": [
          { "scopeDetails": { "correlationID": "6750d3a0-9ac7-4944-97d8-21ecbab9bc8a-0" } }
        ]
      }
    }
  },
  "liveupdate_event_type": "$event_type",
  "liveupdate_type": "unitary",
  "liveupdate_topic_name": "$TOPIC_NAME"
}
EOF
}

# Action buttons - same across all stages for this demo. Two buttons: a DEEPLINK and a WEBURL.
ACTION_BUTTONS='[{"label":"Snooze","uri":"myapp://flight/DL241/snooze","type":"DEEPLINK"},{"label":"View","uri":"https://airline.example/DL241","type":"WEBURL"}]'

# send_stage TITLE BODY CRITICAL_TEXT PROGRESS EVENT_TYPE [DISMISS_AFTER_SECONDS]
send_stage() {
    local title="$1"
    local body="$2"
    local critical="$3"
    local progress="$4"
    local event_type="$5"
    local dismiss_after="${6:-}"

    local dismiss_after_field=""
    if [ -n "$dismiss_after" ]; then
        dismiss_after_field=",\"dismiss_after\":$dismiss_after"
    fi

    local now_ms
    now_ms=$(($(date +%s) * 1000))

    local envelope
    envelope=$(cat <<EOF
{
  "notification_id":  "$NOTIFICATION_ID",
  "channel_id":       "$CHANNEL_ID",
  "priority":         "PRIORITY_HIGH",
  "event_type":       "$event_type",
  "topic_name":       "$TOPIC_NAME",
  "title":            "$title",
  "body":             "$body",
  "critical_text":    "$critical",
  "when":             $now_ms,
  "action_type":      "DEEPLINK",
  "action_uri":       "myapp://flight/DL241",
  "action_buttons":   $ACTION_BUTTONS,
  "content_state": {
    "custom_key_template_type":    "progress",
    "custom_key_journey_start":    "DEL",
    "custom_key_journey_progress": $progress,
    "custom_key_journey_end":      "MUM"
  }$dismiss_after_field
}
EOF
)

    local xdm
    xdm=$(build_xdm "$event_type")

    # Escape inner JSON for embedding in the outer FCM JSON.
    local xdm_escaped envelope_escaped
    xdm_escaped=$(printf '%s' "$xdm" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
    envelope_escaped=$(printf '%s' "$envelope" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")

    echo "Stage: event_type=$event_type progress=$progress '$title'"

    local response
    response=$(curl -s -X POST \
        "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send" \
        -H "Authorization: Bearer $ACCESS_TOKEN" \
        -H 'Content-Type: application/json' \
        -d "{
          \"message\": {
            \"token\": \"$FCM_TOKEN\",
            \"android\": {
              \"priority\": \"HIGH\",
              \"data\": {
                \"_xdm\": $xdm_escaped,
                \"adb_liveupdate_data\": $envelope_escaped
              }
            }
          }
        }")

    if echo "$response" | grep -q "\"name\""; then
        echo "  Delivered"
    else
        echo "  Send failed: $response"
        exit 1
    fi
}

echo "Live Update demo - chip id=$NOTIFICATION_ID, delay=${DELAY}s"
echo "=========================================="

send_stage "Flight on time"     "Boarding starts shortly"       "25 min" 10  "start"
sleep "$DELAY"
send_stage "Boarding"           "Gate D22, boarding now"        "Now"    40  "update"
sleep "$DELAY"
send_stage "In flight"          "Estimated landing 3:45 PM"     "1h 15m" 70  "update"
sleep "$DELAY"
send_stage "Landed"             "Welcome to MUM"                "Done"   100 "end" 5

echo "=========================================="
echo "All 4 stages sent. Chip should update in place, then dismiss 5s after the final stage."
