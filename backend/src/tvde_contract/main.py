from .config import BackendSettings
from .persistent import create_persistent_app


app = create_persistent_app(BackendSettings.from_env())
