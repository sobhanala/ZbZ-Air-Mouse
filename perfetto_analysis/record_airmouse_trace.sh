#!/bin/sh
set -eu

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
ADB="${ANDROID_HOME:-$HOME/Library/Android/sdk}/platform-tools/adb"
CONFIG="$ROOT_DIR/perfetto_analysis/config.pbtx"
REMOTE_TRACE="/data/misc/perfetto-traces/airmouse_trace.perfetto-trace"
LOCAL_TRACE="$ROOT_DIR/perfetto_analysis/airmouse_trace.perfetto-trace"
PACKAGE="io.github.zbz.airmouse"

"$ADB" shell setprop debug.atrace.app_number 1
"$ADB" shell setprop debug.atrace.app_0 "$PACKAGE"
"$ADB" shell setprop debug.atrace.tags.enableflags 4096
cat "$CONFIG" | "$ADB" shell perfetto -c - --txt -o "$REMOTE_TRACE"
"$ADB" pull "$REMOTE_TRACE" "$LOCAL_TRACE"
"$ADB" shell setprop debug.atrace.tags.enableflags 0
"$ADB" shell setprop debug.atrace.app_number 0
"$ADB" shell setprop debug.atrace.app_0 ""

printf '%s\n' "$LOCAL_TRACE"
