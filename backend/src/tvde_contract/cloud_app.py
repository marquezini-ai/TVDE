from __future__ import annotations

import logging
import time
from collections.abc import Callable

from fastapi import FastAPI, Request

from .cloud_config import CloudBackendSettings
from .firestore_storage import FirestoreStorage
from .harness import create_contract_app
from .persistent import FailureInjector, PersistentBackend
from .structured_logging import configure_logging, log_event, safe_principal


def create_cloud_app(
    settings: CloudBackendSettings,
    *,
    clock: Callable[[], int] | None = None,
    failure_injector: FailureInjector | None = None,
    storage: FirestoreStorage | None = None,
) -> FastAPI:
    configure_logging(settings.log_level)
    cloud_storage = storage or FirestoreStorage(settings.project_id, settings.database_id)
    cloud_storage.assert_schema()
    backend = PersistentBackend(
        settings,
        clock=clock or (lambda: int(time.time())),
        failure_injector=failure_injector,
        storage=cloud_storage,
    )
    app = create_contract_app(clock=backend.clock, limits=settings.limits, backend=backend)

    @app.get("/_health", include_in_schema=False)
    async def health() -> dict[str, str]:
        cloud_storage.assert_schema()
        return {"status": "ok"}

    @app.middleware("http")
    async def structured_request_log(request: Request, call_next):
        started = time.perf_counter()
        status = 500
        try:
            response = await call_next(request)
            status = response.status_code
            return response
        finally:
            installation_id = request.headers.get("X-TVDE-Installation-Id")
            log_event(
                backend.logger,
                logging.INFO,
                "request_completed",
                method=request.method,
                path=request.url.path,
                status=status,
                duration_ms=round((time.perf_counter() - started) * 1000, 2),
                principal=safe_principal(installation_id),
            )

    app.state.cloud_backend = backend
    app.state.firestore_storage = cloud_storage
    return app
