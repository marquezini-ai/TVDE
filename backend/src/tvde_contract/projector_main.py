from __future__ import annotations

import json

from .cloud_config import CloudBackendSettings
from .firestore_storage import FirestoreStorage
from .google_sheets_projector import CloudProjectionWorker, GoogleSheetsProjector
from .structured_logging import configure_logging


def main() -> None:
    settings = CloudBackendSettings.from_env(require_spreadsheet=True)
    configure_logging(settings.log_level)
    storage = FirestoreStorage(settings.project_id, settings.database_id)
    storage.assert_schema()
    projector = GoogleSheetsProjector(settings.spreadsheet_id or "", settings.spreadsheet_tab)
    projector.ensure_headers()
    print(json.dumps(CloudProjectionWorker(storage, projector, settings).drain(), sort_keys=True))


if __name__ == "__main__":
    main()
