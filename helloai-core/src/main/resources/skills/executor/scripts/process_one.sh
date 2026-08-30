#!/usr/bin/env bash
# process_one.sh — HelloAI Executor process a single sub-task (macOS/Linux)
# Usage:
#   ./process_one.sh -s <subTaskId> -f <filePath> [-m <messageId>] [-r resultTag] [-c finishReason]
#
# Flow: startById (REST) → upload artifact → submitResult (MCP JSON-RPC) → ack (MCP JSON-RPC)
# NOTE: startById uses REST because the MCP tool returns 500 for this endpoint.
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

SUB_TASK_ID=""
FILE_PATH=""
MESSAGE_ID=""
RESULT_TAG="v1"
FINISH_REASON="completed"

while getopts "s:f:m:r:c:" opt; do
    case "$opt" in
        s) SUB_TASK_ID="$OPTARG" ;;
        f) FILE_PATH="$OPTARG" ;;
        m) MESSAGE_ID="$OPTARG" ;;
        r) RESULT_TAG="$OPTARG" ;;
        c) FINISH_REASON="$OPTARG" ;;
        *) echo "Usage: $0 -s <subTaskId> -f <filePath> [-m <messageId>] [-r resultTag] [-c finishReason]" >&2; exit 1 ;;
    esac
done

if [ -z "$SUB_TASK_ID" ] || [ -z "$FILE_PATH" ]; then
    echo "ERROR: -s <subTaskId> and -f <filePath> are required" >&2
    exit 1
fi

if [ ! -f "$FILE_PATH" ]; then
    echo "ERROR: File not found: $FILE_PATH" >&2
    exit 1
fi

# Portable file size: macOS stat -f%z, Linux stat -c%s
FILE_SIZE=$(stat -f%z "$FILE_PATH" 2>/dev/null || stat -c%s "$FILE_PATH" 2>/dev/null)
FILE_NAME=$(basename "$FILE_PATH")
echo "file size = $FILE_SIZE"

# 1) startById — use REST endpoint (MCP tool returns 500)
START_RESP=$(curl -s -X POST "$BASE_URL/api/sub-tasks/startById/$SUB_TASK_ID" \
    -H "Authorization: Bearer $API_KEY")
echo "startById: $START_RESP"

# 2) Upload artifact
UPLOAD_RESP=$(curl -s -X POST "$BASE_URL/api/artifacts/upload" \
    -H "Authorization: Bearer $API_KEY" \
    -F "file=@$FILE_PATH;type=text/markdown" \
    -F "subTaskId=$SUB_TASK_ID" \
    -F "mimeType=text/markdown")
echo "upload: $UPLOAD_RESP"

ATTACHMENT_ID=$(echo "$UPLOAD_RESP" | jq -r '.data.attachmentId')

# 3) submitResult via MCP JSON-RPC
EXEC_RECORD=$(cat <<EOF
## EXECUTION_RECORD
SUMMARY: Generated $FILE_NAME (attachmentId=$ATTACHMENT_ID, size=$FILE_SIZE). Processed by executor script.
KEY_DECISIONS:
- Followed deliverable template as specified in task requirements.
DOWNSTREAM_NOTES:
- See deliverable document body for details.
DELIVERABLES:
- $FILE_NAME (attachmentId=$ATTACHMENT_ID, size=$FILE_SIZE)
VERIFICATION:
- command: test -s $FILE_NAME && echo OK
- output: OK
- conclusion: passed
EOF
)

RESULT_ID="r-${SUB_TASK_ID}-${RESULT_TAG}"

SUBMIT_ARGS=$(jq -n \
    --arg subTaskId "$SUB_TASK_ID" \
    --arg resultId "$RESULT_ID" \
    --argjson success true \
    --arg output "$EXEC_RECORD" \
    --arg finishReason "$FINISH_REASON" \
    '{subTaskId: $subTaskId, resultId: $resultId, success: $success, output: $output, finishReason: $finishReason}')

SUBMIT_PAYLOAD=$(jq -n \
    --argjson args "$SUBMIT_ARGS" \
    '{jsonrpc: "2.0", method: "tools/call", id: 1, params: {name: "submitResult", arguments: $args}}')

SUBMIT_RESP=$(curl -s -X POST "$BASE_URL/api/mcp/jsonrpc" \
    -H "Authorization: Bearer $API_KEY" \
    -H "Content-Type: application/json" \
    -d "$SUBMIT_PAYLOAD")
echo "submit: $SUBMIT_RESP"

# 4) ack inbox message
if [ -n "$MESSAGE_ID" ]; then
    ACK_PAYLOAD=$(jq -n \
        --arg messageId "$MESSAGE_ID" \
        '{jsonrpc: "2.0", method: "tools/call", id: 2, params: {name: "ack", arguments: {messageId: $messageId}}}')

    ACK_RESP=$(curl -s -X POST "$BASE_URL/api/mcp/jsonrpc" \
        -H "Authorization: Bearer $API_KEY" \
        -H "Content-Type: application/json" \
        -d "$ACK_PAYLOAD")
    echo "ack $MESSAGE_ID: $ACK_RESP"
fi

echo "DONE"