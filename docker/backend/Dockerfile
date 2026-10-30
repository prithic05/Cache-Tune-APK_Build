FROM python:3.11-slim-bookworm

# Install system dependencies.
# gpac (MP4Box) is not in Bookworm repos — we pull it from the archived Bullseye
# repo pinned at priority 100 so it never overrides Bookworm packages.
RUN apt-get update && apt-get install -y \
        ffmpeg \
        curl \
        docker.io \
        gnupg \
    && echo "deb [trusted=yes] http://archive.debian.org/debian bullseye main" \
        > /etc/apt/sources.list.d/bullseye-archive.list \
    && printf 'Package: *\nPin: release n=bullseye\nPin-Priority: 100\n' \
        > /etc/apt/preferences.d/bullseye-archive \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && apt-get install -y -t bullseye gpac \
    && rm -rf /var/lib/apt/lists/* \
              /etc/apt/sources.list.d/bullseye-archive.list \
              /etc/apt/preferences.d/bullseye-archive \
    && MP4Box -version

# Install Go 1.23.1 (dynamically detect amd64 or arm64)
RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "arm64" ]; then GO_ARCH="arm64"; else GO_ARCH="amd64"; fi && \
    curl -LO "https://go.dev/dl/go1.23.1.linux-${GO_ARCH}.tar.gz" \
    && tar -C /usr/local -xzf "go1.23.1.linux-${GO_ARCH}.tar.gz" \
    && rm "go1.23.1.linux-${GO_ARCH}.tar.gz"
ENV PATH="/usr/local/go/bin:${PATH}"

# Install Docker Compose V2 (dynamically detect x86_64 or aarch64)
RUN ARCH=$(dpkg --print-architecture) && \
    if [ "$ARCH" = "arm64" ]; then COMPOSE_ARCH="aarch64"; else COMPOSE_ARCH="x86_64"; fi && \
    mkdir -p /usr/local/lib/docker/cli-plugins/ \
    && curl -SL "https://github.com/docker/compose/releases/download/v2.29.2/docker-compose-linux-${COMPOSE_ARCH}" \
        -o /usr/local/lib/docker/cli-plugins/docker-compose \
    && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose

WORKDIR /app

# Install Python dependencies
COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --no-cache-dir -r backend/requirements.txt

# Copy application source
# Note: Downloader/, yt-music/, and cache/ are mounted as volumes at runtime.
COPY backend /app/backend
COPY apple_music_search /app/apple_music_search

EXPOSE 8000
CMD ["python", "-m", "uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8000"]
