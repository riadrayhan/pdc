"""Deploy backend and dashboard to Hugging Face Spaces."""
import sys
from huggingface_hub import HfApi

api = HfApi()

IGNORE = [
    "node_modules/**", "**/node_modules/**", "venv/**", "**/venv/**",
    "**/__pycache__/**", "*.pyc", "*.db", "test_boot.db",
    "service-account.json", "firebase-credentials.json", ".env",
    "**/.git/**", "*.log",
]


def deploy_backend():
    print(">>> Uploading Node.js backend to riadrayhan111/rr-locker-api ...")
    api.upload_folder(
        folder_path="backend-node",
        repo_id="riadrayhan111/rr-locker-api",
        repo_type="space",
        ignore_patterns=IGNORE,
        commit_message="Node.js + Socket.IO backend (migrated from FastAPI)",
    )
    print(">>> Backend done.")


def deploy_dashboard():
    print(">>> Uploading dashboard dist to riadrayhan111/rr-locker-dashboard ...")
    api.upload_folder(
        folder_path="dashboard/dist",
        repo_id="riadrayhan111/rr-locker-dashboard",
        repo_type="space",
        commit_message="Remove provisioning pages; Device Admin only",
    )
    print(">>> Dashboard done.")


if __name__ == "__main__":
    target = sys.argv[1] if len(sys.argv) > 1 else "all"
    if target in ("backend", "all"):
        deploy_backend()
    if target in ("dashboard", "all"):
        deploy_dashboard()
    print(">>> All uploads complete.")
