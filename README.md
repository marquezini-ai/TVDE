# TVDE Insight

Aplicação Android que captura ofertas visíveis da Uber Driver e Bolt Driver, calcula a rentabilidade segundo critérios do motorista e apresenta uma decisão informativa. A aplicação não aceita, rejeita ou envia cliques às plataformas.

Baseline profissional: `0.6.0-unified`, `versionCode 173`. Consulte o [relatório da release](docs/releases/0.6.0-unified.md).

## Arquitetura

```text
Android
  -> captura local: Accessibility / Notification Listener / OCR
  -> parsers e RuleEngine
  -> Room + outbox
  -> BackendSyncGateway + WorkManager
  -> Cloud Run / FastAPI
  -> Firestore, fonte de verdade
  -> projection outbox
  -> Google Sheets, somente projeção
```

Detalhes: [Arquitetura](docs/ARCHITECTURE.md) e [ADRs](backend/docs/).

## Variantes

| Papel | Pacote | Uso |
| --- | --- | --- |
| Client | `com.daniel.tvdeinsight` | Aplicação licenciada do motorista |
| Admin | `com.daniel.tvdeinsight.admin` | Administração e emissão de licenças |

Cada papel possui build `debug` e `release`. Release exige assinatura configurada localmente.

## Requisitos verificados

- Android: `minSdk 29`, `targetSdk 35`, `compileSdk 35`.
- JDK 17. O baseline foi criado com Microsoft OpenJDK `17.0.20`.
- Gradle Wrapper `8.9` e Android Gradle Plugin `8.7.3`.
- Python `>=3.12,<3.15`; a validação final usou Python `3.13.15`.
- Conta Google Cloud autorizada apenas para deploy ou operação cloud.

## Configuração local

1. Configure o Android SDK em `local.properties`. Esse arquivo não é versionado.
2. Copie `keystore.properties.example` para `keystore.properties` e informe o keystore de release. Não versione o arquivo nem o keystore.
3. Para licenciamento, siga [LICENSE_SETUP.md](LICENSE_SETUP.md). A chave pública pode entrar no APK; a chave privada nunca pode.
4. O Android usa o backend definido em `BACKEND_BASE_URL`. Credenciais privadas Google não pertencem ao APK.
5. Para o backend, use variáveis de ambiente ou Secret Manager conforme [backend/docs/CLOUD-TEST.md](backend/docs/CLOUD-TEST.md).

## Build Android

Em máquinas com RAM limitada, compile sequencialmente. O build conjunto das quatro variantes excedeu a memória de uma máquina com 16 GB, enquanto os comandos abaixo funcionaram:

```powershell
cmd /c "gradlew.bat --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process testClientDebugUnitTest assembleClientDebug assembleClientRelease"
cmd /c "gradlew.bat --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx2048m -Pkotlin.compiler.execution.strategy=in-process testAdminDebugUnitTest assembleAdminDebug assembleAdminRelease"
```

## Backend local

```powershell
py -3.13 -m venv backend/.venv
backend/.venv/Scripts/python.exe -m pip install -e "backend[test]"
$env:TVDE_BACKEND_ENV = "development"
$env:TVDE_DATABASE_PATH = "C:\safe-local-path\tvde-local.sqlite3"
backend/.venv/Scripts/python.exe -m uvicorn tvde_contract.main:app --host 127.0.0.1 --port 8080
```

Use apenas chaves sintéticas nos testes locais. Mais detalhes em [backend/README.md](backend/README.md).

## Testes

```powershell
gradlew.bat testClientDebugUnitTest testAdminDebugUnitTest
backend/.venv/Scripts/python.exe -m pytest backend/tests -q
backend/.venv/Scripts/python.exe backend/tools/export_openapi.py --check
backend/.venv/Scripts/python.exe scripts/check_tracked_secrets.py
```

Os testes cloud exigem projeto isolado, autenticação e `TVDE_RUN_CLOUD_TESTS=1`. Não são executados automaticamente no CI.

## Operação e recuperação

- [Checklist de release](docs/RELEASE-CHECKLIST.md)
- [Backup](docs/BACKUP.md)
- [Restore e rollback](docs/RESTORE.md)
- [Disaster recovery](docs/DISASTER-RECOVERY.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Guia para manutenção por IA](docs/AI-MAINTENANCE-GUIDE.md)
- [Baseline profissional](docs/PROFESSIONAL-BASELINE.md)
- [Changelog](CHANGELOG.md)

APKs e bancos locais não são versionados. O Git preserva código e documentação; dados persistentes exigem a política específica de backup.
