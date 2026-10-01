from __future__ import annotations

import argparse

from tvde_contract.config import BackendSettings
from tvde_contract.models import Role
from tvde_contract.persistent import PersistentBackend


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed one synthetic local backend license.")
    parser.add_argument("--activation-key", required=True)
    parser.add_argument("--role", choices=[item.value for item in Role], default=Role.CLIENT.value)
    args = parser.parse_args()
    settings = BackendSettings.from_env()
    backend = PersistentBackend(settings)
    backend.add_license(args.activation_key, role=Role(args.role))
    print(f"Seeded synthetic {args.role} license in {settings.database_path}")


if __name__ == "__main__":
    main()
