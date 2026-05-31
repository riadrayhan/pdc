FROM node:20-slim

WORKDIR /app

# Install production dependencies first (better layer caching)
COPY package*.json ./
RUN npm ci --omit=dev

# Copy application code (includes pre-built dashboard in ./static and ./apk)
COPY . .

# Hugging Face Spaces run as a non-root user; ensure the app + data dirs are writable.
RUN mkdir -p /data && \
    useradd -m -u 1000 appuser && \
    chown -R appuser:appuser /app /data
USER appuser

ENV NODE_ENV=production \
    PORT=7860 \
    TZ=UTC

EXPOSE 7860

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD node -e "fetch('http://localhost:7860/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "src/server.js"]
