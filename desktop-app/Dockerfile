# syntax=docker/dockerfile:1
# Electron khong co cua so that trong container: chay duoi man hinh ao (Xvfb)
# va phat qua trinh duyet bang noVNC tai http://localhost:6080.
FROM node:22-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends \
      xvfb x11vnc novnc websockify openbox fonts-noto fonts-noto-color-emoji \
      libgtk-3-0 libnss3 libasound2 libgbm1 libxss1 libxtst6 libxkbcommon0 libdrm2 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts --no-audit --no-fund \
 && node node_modules/electron/install.js
COPY . .
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh && chmod +x /usr/local/bin/docker-entrypoint.sh
ENV DISPLAY=:99
EXPOSE 6080
ENTRYPOINT ["docker-entrypoint.sh"]
