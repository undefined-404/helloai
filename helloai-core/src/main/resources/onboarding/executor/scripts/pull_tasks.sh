#!/usr/bin/env bash
# pull_tasks.sh — HelloAI Executor pull inbox and print (macOS/Linux)
# Usage:
#   ./pull_tasks.sh              # unread only
#   ./pull_tasks.sh -i           # include read messages
#   ./pull_tasks.sh -n 30        # max 30 messages
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

BASE_URL=$(jq -r '.baseUrl' "$CONFIG_PATH")
API_KEY=$(jq -r '.apiKey' "$CONFIG_PATH")

INCLUDE_READ=false
MAX=20

while getopts "in:" opt; do
    case "$opt" in
        i) INCLUDE_READ=true ;;
        n) MAX="$OPTARG" ;;
        *) echo "Usage: $0 [-i] [-n max]" >&2; exit 1 ;;
    esac
done

PAYLOAD=$(jq -n \
    --argjson includeRead "$INCLUDE_READ" \
    --argjson max "$MAX" \
    '{jsonrpc: "2.0", method: "tools/call", id: 1, params: {name: "pullTasks", arguments: {role: "EXECUTOR", max: $max, includeRead: $includeRead}}}')

RESP=$(curl -s -X POST "$BASE_URL/api/mcp/jsonrpc" \
    -H "Authorization: Bearer $API_KEY" \
    -H "Content-Type: application/json" \
    -d "$PAYLOAD")

ERR=$(echo "$RESP" | jq -r '.error.message // empty')
if [ -n "$ERR" ]; then
    echo "ERROR: $ERR" >&2
    exit 1
fi

MSGS=$(echo "$RESP" | jq '.result.messages')
COUNT=$(echo "$MSGS" | jq -r 'length')

echo "=== inbox (includeRead=$INCLUDE_READ) ==="
echo "count = $COUNT"

echo "$MSGS" | jq -r '.[] | "  [\(.messageId)] \(.type)  subTaskId=\(.subTaskId)  priority=\(.priority)"'

if [ "$COUNT" -gt 0 ]; then
    echo ""
    FIRST_MSG_ID=$(echo "$MSGS" | jq -r '.[0].messageId')
    FIRST_SUB_ID=$(echo "$MSGS" | jq -r '.[0].subTaskId')
    echo "first messageId: $FIRST_MSG_ID"
    echo "first subTaskId: $FIRST_SUB_ID"
fi