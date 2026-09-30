#!/usr/bin/env bash
# clock.sh — HelloAI Executor checkIn / heartbeat / checkOut (macOS/Linux)
# Usage:
#   ./clock.sh onDuty
#   ./clock.sh heartbeat
#   ./clock.sh checkOut
#
# Reads baseUrl / apiKey / agentId from config.json (copy from config.example.json first).
# On first onDuty, agentId is auto-written back to config.json.
# Requires: curl, jq

set -euo pipefail
export LANG=zh_CN.UTF-8
export LC_ALL=zh_CN.UTF-8

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_PATH="$SCRIPT_DIR/config.json"

if ! command -v jq &> /dev/null; then
    echo "ERROR: jq is required. Install: brew install jq (macOS) / apt-get install jq (Linux)" >&2
    exit 1
fi
if ! command -v curl &> /dev/null; then
    echo "ERROR: curl is required." >&2
    exit 1
fi

if [ ! -f "$CONFIG_PATH" ]; then
    echo "ERROR: config.json not found. Copy config.example.json to config.json and fill in real values." >&2
    exit 1
fi

ACTION="${1:-}"
if [ "$ACTION" != "onDuty" ] && [ "$ACTION" != "heartbeat" ] && [ "$ACTION" != "checkOut" ]; then
    echo "Usage: $0 {onDuty|heartbeat|checkOut}" >&2
    exit 1
fi

BASE_URL=$(jq -r '.baseUrl' "$CONFIG_PATH")
API_KEY=$(jq -r '.apiKey' "$CONFIG_PATH")

post_tool() {
    local name="$1"
    local args_json="$2"
    local payload
    payload=$(jq -n \
        --arg name "$name" \
        --argjson args "$args_json" \
        '{jsonrpc: "2.0", method: "tools/call", id: 1, params: {name: $name, arguments: $args}}')

    local resp
    resp=$(curl -s -X POST "$BASE_URL/api/mcp/jsonrpc" \
        -H "Authorization: Bearer $API_KEY" \
        -H "Content-Type: application/json" \
        -d "$payload")

    local err
    err=$(echo "$resp" | jq -r '.error.message // empty')
    if [ -n "$err" ]; then
        echo "ERROR: Tool $name failed: $err" >&2
        exit 1
    fi
    echo "$resp" | jq '.result'
}

case "$ACTION" in
    onDuty)
        CONCURRENCY=$(jq -r '.concurrencyMax' "$CONFIG_PATH")
        DUTY_TTL=$(jq -r '.dutyTTL' "$CONFIG_PATH")
        ARGS=$(jq -n \
            --arg workMode "AUTO" \
            --argjson maxConcurrent "$CONCURRENCY" \
            --argjson ttlMinutes "$DUTY_TTL" \
            '{workMode: $workMode, maxConcurrent: $maxConcurrent, ttlMinutes: $ttlMinutes}')

        RESULT=$(post_tool "checkIn" "$ARGS")
        echo "=== onDuty ==="
        echo "agentId         = $(echo "$RESULT" | jq -r '.agentId')"
        echo "leaseId         = $(echo "$RESULT" | jq -r '.leaseId')"
        echo "sessionId       = $(echo "$RESULT" | jq -r '.sessionId')"
        echo "leaseExpiresAt  = $(echo "$RESULT" | jq -r '.leaseExpiresAt')"
        echo "onDuty          = $(echo "$RESULT" | jq -r '.onDuty')"

        # Auto-write agentId back to config.json on first run
        AGENT_ID=$(echo "$RESULT" | jq -r '.agentId')
        EXISTING_AGENT_ID=$(jq -r '.agentId // empty' "$CONFIG_PATH")
        if [ -n "$AGENT_ID" ] && [ -z "$EXISTING_AGENT_ID" ]; then
            jq --arg agentId "$AGENT_ID" '.agentId = $agentId' "$CONFIG_PATH" > "$CONFIG_PATH.tmp" && mv "$CONFIG_PATH.tmp" "$CONFIG_PATH"
            echo "(agentId saved to config.json)"
        fi
        ;;
    heartbeat)
        ARGS="{}"
        AGENT_ID=$(jq -r '.agentId // empty' "$CONFIG_PATH")
        if [ -n "$AGENT_ID" ]; then
            ARGS=$(jq -n --arg agentId "$AGENT_ID" '{agentId: $agentId}')
        fi
        RESULT=$(post_tool "heartbeat" "$ARGS")
        echo "=== heartbeat ==="
        echo "onDuty           = $(echo "$RESULT" | jq -r '.onDuty')"
        echo "leaseExpiresAt   = $(echo "$RESULT" | jq -r '.leaseExpiresAt')"
        echo "remainingTtlSec  = $(echo "$RESULT" | jq -r '.remainingTtlSeconds')"
        ;;
    checkOut)
        ARGS='{"closeReason": "shutdown"}'
        AGENT_ID=$(jq -r '.agentId // empty' "$CONFIG_PATH")
        if [ -n "$AGENT_ID" ]; then
            ARGS=$(jq -n --arg agentId "$AGENT_ID" '{closeReason: "shutdown", agentId: $agentId}')
        fi
        RESULT=$(post_tool "checkOut" "$ARGS")
        echo "=== checkOut ==="
        echo "currentStatus = $(echo "$RESULT" | jq -r '.currentStatus')"
        echo "closedCount   = $(echo "$RESULT" | jq -r '.closedCount')"
        ;;
esac