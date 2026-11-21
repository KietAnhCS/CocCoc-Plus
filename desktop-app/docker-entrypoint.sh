#!/bin/sh
set -e
Xvfb :99 -screen 0 1440x900x24 -nolisten tcp &
sleep 1
openbox &
x11vnc -display :99 -forever -shared -nopw -quiet -rfbport 5900 &
websockify --web /usr/share/novnc 6080 localhost:5900 &
# Backend nam chung network namespace nen "localhost:8080" tro dung backend.
exec npx electron-vite dev -- --no-sandbox --disable-gpu --disable-dev-shm-usage
