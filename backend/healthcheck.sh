#!/usr/bin/env bash
# Docker healthcheck: healthy if GET /api/health returns 200.
set -euo pipefail
exec 3<>"/dev/tcp/127.0.0.1/${PORT:-8080}"
printf 'GET /api/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
head -n 1 <&3 | grep -q ' 200 '
