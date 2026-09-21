#!/bin/sh
# Stands in for the Java executable so the toolchain's exact launch command can be
# recorded without starting a game. Arguments are written NUL-separated.
set -eu
: "${FABRICATED_BACKPACKS_LAUNCH_RECORD:?recorder output path is required}"
printf '%s\0' "$@" > "$FABRICATED_BACKPACKS_LAUNCH_RECORD"
pwd > "$FABRICATED_BACKPACKS_LAUNCH_RECORD.cwd"
