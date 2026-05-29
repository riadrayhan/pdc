# pdc



## Architecture

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Admin Dashboard│────▶│  Python Backend │────▶│  Android App    │
│  (React)        │     │  (FastAPI)      │     │  (Java)         │
└─────────────────┘     └─────────────────┘     └─────────────────┘
                               │                        │
                               ▼                        ▼
                        ┌─────────────────┐     ┌─────────────────┐
                        │  PostgreSQL     │     │  Firebase FCM   │
                        │  + Redis        │     │                 │
                        └─────────────────┘     └─────────────────┘
```

## Project Structure

```
emi_locker/
├── backend/                 # Python FastAPI Backend
│   ├── app/
│   │   ├── api/            # API routes
│   │   ├── core/           # Config, security
│   │   ├── models/         # SQLAlchemy models
│   │   ├── schemas/        # Pydantic schemas
│   │   ├── services/       # Business logic
│   │   └── tasks/          # Celery tasks
│   ├── alembic/            # Database migrations
│   └── requirements.txt
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

### Backend (Python FastAPI)
- Device registration and management
- Customer and EMI contract management
- Firebase Cloud Messaging integration
- Auto-lock scheduler with Celery
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
cd backend
python -m venv venv
venv\Scripts\activate  # Windows
pip install -r requirements.txt
alembic upgrade head
uvicorn app.main:app --reload
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

## API Documentation

After starting backend, visit:
- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc
- Live URL: https://rr-lockerapp.onrender.com/
## Environment Variables

### Backend (.env)
```
DATABASE_URL=postgresql://user:pass@localhost/emilocker
REDIS_URL=redis://localhost:6379
SECRET_KEY=your-secret-key
FIREBASE_CREDENTIALS_PATH=firebase-credentials.json
```

## License

Proprietary - All Rights Reserved
