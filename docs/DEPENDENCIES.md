# Dependências e manutenção futura

Revisão em 2026-10-04. Nenhuma dependência foi atualizada nesta fase porque não existe falha crítica comprovada e uma atualização ampla aumentaria o risco funcional.

## Crítico

Nenhuma vulnerabilidade crítica foi confirmada pela validação local. Isso não equivale a uma auditoria externa completa de CVEs.

## Importante

- `androidx.security:security-crypto 1.1.0` e suas APIs `MasterKey`/`EncryptedSharedPreferences` geram avisos de depreciação. Planejar migração separada, com teste de compatibilidade dos dados cifrados existentes.
- APIs `AccessibilityNodeInfo.obtain/recycle` usadas nos parsers geram avisos de depreciação nas SDKs atuais. Não substituir sem validação física, pois afetam leitura de ofertas.
- O backend usa intervalos de versão, não lockfile com hashes. Builds futuros podem resolver versões diferentes dentro do intervalo. Avaliar lock controlado em mudança própria.

## Manutenção futura

- AGP `8.7.3`, Kotlin `2.0.21`, Compose BOM `2024.12.01`, Room `2.6.1`, WorkManager `2.10.0` e demais versões devem ser atualizados em lotes pequenos.
- O backend aceita Python `3.12` a `3.14`; o baseline foi validado em `3.13.15`.
- Revisar avisos de ícones Compose e propriedades de status/navigation bar quando houver trabalho de UI.
- Executar auditoria periódica com ferramentas de dependência e advisories no CI ou em tarefa dedicada, sem atualização automática.

## Regra de atualização

Uma atualização deve indicar motivação, compatibilidade, testes executados e rollback. Não misturar atualização de dependências com correções de captura, OCR ou sync.
