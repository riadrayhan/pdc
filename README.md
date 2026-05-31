# pdc



## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Admin Dashboard│────▶│  Node.js Backend│────▶│  Android App    │
│  (React)        │     │  (Express + IO) │     │  (Java)         │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                               │                        │
                               ▼                        ▼
                        ┌─────────────────┐     ┌─────────────────┐
                        │  SQLite/Postgres│     │  Firebase FCM   │
                        │  (Sequelize)    │     │                 │
                        └─────────────────┘     └─────────────────┘
```

## Project Structure

```
emi_locker/
├── backend-node/            # Node.js (Express + Socket.IO) Backend
│   ├── src/
│   │   ├── routes/         # REST API routes
│   │   ├── core/           # Config, security
│   │   ├── models/         # Sequelize models
│   │   ├── services/       # Business logic, realtime, stream bridge
│   │   └── utils/          # Enums, helpers
│   ├── apk/                # Android APK + version manifest
│   └── package.json
│
├── android/                 # Java Android App
│   └── EMILocker/
│       └── app/src/main/
│           ├── java/       # Java source code
│           └── res/        # Resources
│
├── dashboard/              # React Admin Dashboard
│   └── src/
│       ├── components/
│       ├── pages/
│       └── services/
│
└── docker-compose.yml      # Docker deployment
```

## Features

### Backend (Node.js — Express + Socket.IO)
- Device registration and management
- Customer and EMI contract management
- Firebase Cloud Messaging integration
- Auto-lock scheduler with in-process node-cron
- Real-time screen/audio/file streaming (Socket.IO + WebSocket bridge)
- JWT authentication
- Audit logging

### Android App (Java)
- Device Owner mode for bulletproof control
- Lock screen overlay (kiosk mode)
- FCM command receiver
- Boot persistence
- Tamper detection
- Emergency call access

### Admin Dashboard (React)
- Real-time device monitoring
- One-click lock/unlock
- Customer management
- EMI payment tracking
- Bulk operations
- Reports and analytics

## Quick Start

### 1. Backend Setup

```bash
cd backend-node
npm install
npm start
```

### 2. Dashboard Setup

```bash
cd dashboard
npm install
npm run dev
```

### 3. Android App
- Open `android/EMILocker` in Android Studio
- Build and deploy to device in Device Owner mode

## Device Enrollment

1. Factory reset the target device
2. Tap 6 times on welcome screen
3. Scan QR code generated from admin dashboard
4. Device will automatically provision with EMI Locker app

## API

All REST endpoints are served under `/api/v1`. Health check at `/health`.
Real-time layer (Socket.IO) is mounted at the server root (`/dashboard`, `/device`
namespaces) and a raw WebSocket bridge handles device screen/audio/file streams.

## Live Deployment (Hugging Face Spaces)

- Backend API: https://riadrayhan111-rr-locker-api.hf.space
- Admin Dashboard: https://riadrayhan111-rr-locker-dashboard.static.hf.space

## Environment Variables

### Backend (.env)
```
DATABASE_URL=postgresql://user:pass@localhost/emilocker  # optional; defaults to SQLite on /data
PORT=7860
SECRET_KEY=your-secret-key
```

## License

Proprietary - All Rights Reserved
