from __future__ import annotations

import argparse
import time

from tvde_contract.cloud_config import CloudBackendSettings
from tvde_contract.firestore_storage import FirestoreStorage
from tvde_contract.models import Role


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed one synthetic cloud-test license")
    parser.add_argument("activation_key")
    parser.add_argument("--role", choices=[item.value for item in Role], default=Role.CLIENT.value)
    parser.add_argument("--expires-at", type=int)
    parser.add_argument("--revoked", action="store_true")
    args = parser.parse_args()
    settings = CloudBackendSettings.from_env()
    storage = FirestoreStorage(settings.project_id, settings.database_id)
    storage.assert_schema()
    storage.put_license(
        args.activation_key,
        args.role,
        args.expires_at,
        args.revoked,
        int(time.time()),
    )
    print("Synthetic cloud-test license stored")


if __name__ == "__main__":
    main()
