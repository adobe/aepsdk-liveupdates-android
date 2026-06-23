#!/bin/bash
#
# Football score Live Update demo - 4 stages showing a match's progress with a chip
# styled by the sample app's StyleProvider using a "metric" template_type (MetricStyle
# where available on API 37+, falls back to a formatted BigTextStyle on API 36).
#
# Stages:
#   1. start  - kickoff (0-0)
#   2. update - home goal (1-0, 23')
#   3. update - away equaliser (1-1, 56')
#   4. end    - full time (2-1, dismiss after 10s)
#
# All four stages share the same notification_id so the chip updates in place.
#
# Usage:
#   ./fcm-football.sh <FCM_TOKEN> [DELAY_SECONDS]
#   NOTIFICATION_ID=match_lv_arsenal ./fcm-football.sh <token>

set -euo pipefail

PROJECT_ID="collabpoc-b074b"
SERVICE_ACCOUNT_KEY="$(dirname "$0")/fcm-key.json"

FCM_TOKEN="${1:-}"
DELAY="${2:-3}"
NOTIFICATION_ID="${NOTIFICATION_ID:-match_lv_arsenal_2026_06_23}"
CHANNEL_ID="live_updates_channel"
TOPIC_NAME="match_lv_arsenal"

if [ -z "$FCM_TOKEN" ]; then
    echo "Usage: $0 <FCM_TOKEN> [DELAY_SECONDS]"
    exit 1
fi

gcloud auth activate-service-account --key-file="$SERVICE_ACCOUNT_KEY" > /dev/null 2>&1 || true
ACCESS_TOKEN=$(gcloud auth print-access-token)

build_xdm() {
    local event_type="$1"
    cat <<EOF
{
  "mixins": {
    "_experience": {
      "customerJourneyManagement": {
        "messageExecution": {
          "messageExecutionID": "FOOTBALL-DEMO-001",
          "messageType": "transactional",
          "campaignID": "football-livescore-campaign"
        }
      }
    }
  },
  "liveupdate_event_type": "$event_type",
  "liveupdate_type": "unitary",
  "liveupdate_topic_name": "$TOPIC_NAME"
}
EOF
}

# send_stage TITLE BODY CRITICAL_TEXT HOME_SCORE AWAY_SCORE MATCH_TIME EVENT_TYPE [DISMISS_AFTER]
send_stage() {
    local title="$1"
    local body="$2"
    local critical="$3"
    local home_score="$4"
    local away_score="$5"
    local match_time="$6"
    local event_type="$7"
    local dismiss_after="${8:-}"

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
  "action_uri":       "myapp://match/lv-arsenal",
  "action_buttons":   [
    { "label": "Stats", "uri": "myapp://match/lv-arsenal/stats", "type": "DEEPLINK" },
    { "label": "Watch", "uri": "https://sport.example/lv-arsenal/live", "type": "WEBURL" }
  ],
  "content_state": {
    "custom_key_template_type": "metric",
    "custom_key_home_team":     "Liverpool",
    "custom_key_away_team":     "Arsenal",
    "custom_key_home_score":    $home_score,
    "custom_key_away_score":    $away_score,
    "custom_key_match_time":    "$match_time",
    "custom_key_match_status":  "$event_type"
  }$dismiss_after_field
}
EOF
)

    local xdm
    xdm=$(build_xdm "$event_type")

    local xdm_escaped envelope_escaped
    xdm_escaped=$(printf '%s' "$xdm" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")
    envelope_escaped=$(printf '%s' "$envelope" | python3 -c "import sys,json; print(json.dumps(sys.stdin.read()))")

    echo "Stage: event_type=$event_type score=$home_score-$away_score min=$match_time '$title'"

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

echo "Football match Live Update demo - chip id=$NOTIFICATION_ID, delay=${DELAY}s"
echo "Liverpool vs Arsenal"
echo "=========================================="

send_stage "Liverpool vs Arsenal" "Kickoff at Anfield"          "0-0"  0 0 "1'"  "start"
sleep "$DELAY"
send_stage "Liverpool 1-0 Arsenal" "Salah scores for Liverpool" "1-0" 1 0 "23'" "update"
sleep "$DELAY"
send_stage "Liverpool 1-1 Arsenal" "Saka equalises for Arsenal" "1-1" 1 1 "56'" "update"
sleep "$DELAY"
send_stage "Liverpool 2-1 Arsenal" "Full time - Liverpool win"  "FT"   2 1 "90'" "end" 10

echo "=========================================="
echo "Match complete. Chip should update in place, then dismiss 10s after FT."
