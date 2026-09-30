# Fase 1 — ambiente e build inicial

- Módulo Gradle: `:app`.
- `applicationId`: `com.daniel.tvdeinsight`; variante admin: sufixo `.admin`.
- AGP 8.7.3; Gradle 8.9; Kotlin 2.0.21; kotlinx-coroutines 1.9.0; JDK 17.0.20 Microsoft.
- Android: minSdk 29, targetSdk 35, compileSdk 35.
- O comando genérico `gradlew testDebugUnitTest assembleDebug` não existe neste projeto com flavors e falhou por ambiguidade.
- Comando correto executado antes de alterações de produção: `gradlew testAdminDebugUnitTest testClientDebugUnitTest assembleAdminDebug assembleClientDebug`.
- Resultado: `BUILD SUCCESSFUL`, 100 tarefas, todas `UP-TO-DATE`.
- Não havia ficheiros brutos `tvde-work-*.txt` ou `tvde-bolt-work-*.txt` no repositório/workspace.
- Não havia dispositivo ADB ligado. Não foi possível fazer recaptura BEFORE nem profiling de método/alocação no aparelho real.

## Divergência factual encontrada

A afirmação recebida de que a aplicação não usa OCR não corresponde ao código atual. No Android 13 ou superior, a Uber usa screenshot, Bitmap, ML Kit Text Recognition e pré-processamento OpenCV. No Android 12 ou inferior, a Uber usa acessibilidade. A Bolt usa notificação como gatilho e acessibilidade como leitura em todas as versões suportadas.
