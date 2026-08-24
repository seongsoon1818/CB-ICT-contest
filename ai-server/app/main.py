from fastapi import FastAPI, HTTPException, status

from app.settings import Settings


def create_app(settings: Settings | None = None) -> FastAPI:
    app_settings = settings or Settings.from_env()
    application = FastAPI(title="AnimalGuard AI Server")

    @application.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @application.get("/health/ready")
    async def ready() -> dict[str, str]:
        if app_settings.backend_base_url is None:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="BACKEND_BASE_URL is not configured",
            )
        return {"status": "READY", "inference": "mock"}

    return application


app = create_app()
