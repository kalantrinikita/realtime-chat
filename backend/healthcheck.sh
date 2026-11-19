#!/usr/bin/env bash
# Docker healthcheck: GET /api/health, healthy only on HTTP 200 (which also proves the DB answers).
# Uses bash's built-in /dev/tcp so the runtime image needs no curl or wget.
set -euo pipefail
exec 3<>"/dev/tcp/127.0.0.1/${PORT:-8080}"
printf 'GET /api/health HTTP/1.0\r\nHost: localhost\r\n\r\n' >&3
head -n 1 <&3 | grep -q ' 200 '
