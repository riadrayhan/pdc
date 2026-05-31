---
title: RR Locker API
emoji: 🔒
colorFrom: blue
colorTo: indigo
sdk: docker
app_port: 7860
pinned: false
---

# RR Locker API

Node.js (Express + Socket.IO + ws) backend for the RR Locker Mobile Device
Management system. Migrated from FastAPI.

Deployed as a Hugging Face Docker Space.

- Health check: `/health`
- API base: `/api/v1`
- Realtime: Socket.IO namespaces `/device`, `/dashboard`
