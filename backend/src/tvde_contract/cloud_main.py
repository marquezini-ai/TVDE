from .cloud_app import create_cloud_app
from .cloud_config import CloudBackendSettings


app = create_cloud_app(CloudBackendSettings.from_env())
