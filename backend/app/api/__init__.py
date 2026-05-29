from fastapi import APIRouter
from app.api.auth import router as auth_router
from app.api.devices import router as devices_router
from app.api.customers import router as customers_router
from app.api.emi import router as emi_router
from app.api.commands import router as commands_router
from app.api.users import router as users_router
from app.api.enrollment import router as enrollment_router
from app.api.app_distribution import router as app_distribution_router
from app.api.zero_touch import router as zte_router
from app.api.amapi import router as amapi_router
from app.api.metadata import router as metadata_router
from app.api.screen_mirror import router as screen_mirror_router
from app.api.file_manager import router as file_manager_router

api_router = APIRouter(prefix="/api/v1")

api_router.include_router(auth_router)
api_router.include_router(devices_router)
api_router.include_router(customers_router)
api_router.include_router(emi_router)
api_router.include_router(commands_router)
api_router.include_router(users_router)
api_router.include_router(enrollment_router)
api_router.include_router(app_distribution_router)
api_router.include_router(zte_router)
api_router.include_router(amapi_router)
api_router.include_router(metadata_router)
api_router.include_router(screen_mirror_router)
api_router.include_router(file_manager_router)
