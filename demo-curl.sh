#!/bin/bash
#
# Standalone curl example sending ONE production-shape Live Update push.
# Copy the curl block below into Postman, replacing <FCM_TOKEN> and <BEARER_TOKEN>.
#
# How to get the bearer token (OAuth 2.0 access token for FCM v1 API):
#   gcloud auth activate-service-account --key-file=./fcm-key.json
#   gcloud auth print-access-token
#
# Payload notes:
#   - All Live Update display fields are inside adb_liveupdate_data (the standard
#     adb_title / adb_body / adb_channel_id / adb_n_priority keys are NOT used).
#   - data is placed under message.android.data (Android-specific delivery).
#   - _xdm is opaque to the on-device SDK and flows through to AJO server-side
#     reporting via Edge with messageExecutionID / campaignID correlation intact.
#   - action_type / action_uri / action_buttons are reserved schema fields; the v1.1
#     renderer parses them but does not yet wire tap PendingIntents (LIVEUP-5).
#
# Run:
#   ./demo-curl.sh <FCM_TOKEN>

set -euo pipefail

PROJECT_ID="collabpoc-b074b"
SERVICE_ACCOUNT_KEY="$(dirname "$0")/fcm-key.json"
FCM_TOKEN="${1:-}"

if [ -z "$FCM_TOKEN" ]; then
    echo "Usage: $0 <FCM_TOKEN>"
    echo "(For Postman, copy the curl block from this file directly and substitute tokens.)"
    exit 1
fi

# Mint a fresh bearer token (OAuth 2.0 access token, ~1 hour lifetime).
gcloud auth activate-service-account --key-file="$SERVICE_ACCOUNT_KEY" > /dev/null 2>&1 || true
ACCESS_TOKEN=$(gcloud auth print-access-token)

# ============================================================================
# Production-shape Live Update payload - one push, event_type=start.
# Same shape used in production AJO authoring; copy the curl block below
# into Postman or use directly. Replace <FCM_TOKEN> and <BEARER_TOKEN>.
# ============================================================================
curl -X POST \
  "https://fcm.googleapis.com/v1/projects/$PROJECT_ID/messages:send" \
  -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{
  "message": {
    "token": "'"$FCM_TOKEN"'",
    "android": {
      "priority": "HIGH",
      "data": {
        "_xdm": "{\"mixins\":{\"_experience\":{\"customerJourneyManagement\":{\"messageExecution\":{\"messageExecutionID\":\"HUPU-59740365\",\"messageID\":\"6750d3a0-9ac7-4944-97d8-21ecbab9bc8a-0\",\"messageType\":\"transactional\",\"campaignID\":\"96008fbe-4dfa-445f-80d5-10287a54e948\",\"campaignVersionID\":\"843f2ea5-815d-466b-9754-ab5e57b3d8de\",\"campaignActionID\":\"3cbb4bb7-60af-439f-bdbe-216f14cbd47f\",\"batchInstanceID\":\"e37a8db9-1359-46ac-a67c-1266b275cc68\"}},\"decisioning\":{\"propositions\":[{\"scopeDetails\":{\"correlationID\":\"6750d3a0-9ac7-4944-97d8-21ecbab9bc8a-0\"}}]}}},\"liveupdate_event_type\":\"start\",\"liveupdate_type\":\"unitary\",\"liveupdate_topic_name\":\"flight_DL241\"}",
        "adb_liveupdate_data": "{\"notification_id\":\"flight_DL241_2026_01_15\",\"channel_id\":\"live_updates_channel\",\"priority\":\"PRIORITY_HIGH\",\"event_type\":\"start\",\"topic_name\":\"flight_DL241\",\"title\":\"Flight DL241\",\"body\":\"Boarding now at Gate D22\",\"critical_text\":\"30 min\",\"when\":1768435200000,\"dismiss_after\":5,\"action_type\":\"DEEPLINK\",\"action_uri\":\"myapp://flight/DL241\",\"action_buttons\":[{\"label\":\"Snooze\",\"uri\":\"myapp://flight/DL241/snooze\",\"type\":\"DEEPLINK\"},{\"label\":\"View\",\"uri\":\"https://airline.example/DL241\",\"type\":\"WEBURL\"}],\"content_state\":{\"custom_key_template_type\":\"progress\",\"custom_key_journey_start\":\"DEL\",\"custom_key_journey_progress\":10,\"custom_key_journey_end\":\"MUM\"}}"
      }
    }
  }
}'
echo ""
