FROM node:20-slim

WORKDIR /app

# Install production dependencies first (better layer caching)
COPY package*.json ./
RUN npm ci --omit=dev

# Copy application code (includes pre-built dashboard in ./static and ./apk)
COPY . .

# Hugging Face Spaces run as a non-root user (UID 1000). The node:20 image already
# ships a "node" user at UID 1000, so reuse it instead of creating a duplicate.
RUN mkdir -p /data && \
    chown -R node:node /app /data
USER node

ENV NODE_ENV=production \
    PORT=7860 \
    TZ=UTC

EXPOSE 7860

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
    CMD node -e "fetch('http://localhost:7860/health').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "src/server.js"]
