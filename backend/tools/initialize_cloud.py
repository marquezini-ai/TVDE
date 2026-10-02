from __future__ import annotations

import time

from tvde_contract.cloud_config import CloudBackendSettings
from tvde_contract.firestore_storage import FirestoreStorage


def main() -> None:
    settings = CloudBackendSettings.from_env()
    storage = FirestoreStorage(settings.project_id, settings.database_id)
    storage.initialize_schema(int(time.time()))
    storage.assert_schema()
    print("Firestore schema initialized")


if __name__ == "__main__":
    main()
