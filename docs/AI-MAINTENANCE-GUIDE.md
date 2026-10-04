# Guia de manutenção para futuras sessões de IA

## Contexto rápido

TVDE Insight avalia ofertas Uber/Bolt localmente e sincroniza eventos por uma API segura. O baseline é `0.6.0-unified`/`173`, com código da release no commit `dbbdbdc`. Comece pelo [README](../README.md), [arquitetura](ARCHITECTURE.md), [release](releases/0.6.0-unified.md) e ADRs.

## Regras obrigatórias

- Trabalhar incrementalmente, com mudanças pequenas e reversíveis.
- Preservar comportamento comprovado em produção.
- Não modificar Accessibility, OCR, parsers, RuleEngine, Overlay ou protocolos de sync sem compreender testes e impacto físico.
- Não alterar Bolt ao corrigir fluxo específico Uber, nem o inverso, sem evidência.
- Não tratar build ou teste unitário como validação física.
- Não apagar Room, outbox, credenciais, tags ou artefatos de recuperação por tentativa.
- Nunca imprimir ou versionar segredo.

## Antes de mudar

1. Confirmar repositório, branch, HEAD, status e versão.
2. Ler apenas os componentes e testes do fluxo afetado.
3. Identificar se a mudança exige dispositivo real.
4. Registrar suposição e risco.
5. Planejar rollback.

## Testes mínimos

- Mudança Android: testes Client e Admin afetados; release exige matriz do checklist.
- Mudança de captura: teste automatizado e validação física no Android aplicável.
- Mudança de sync: Android, backend, offline, idempotência e outbox.
- Mudança backend: pytest, OpenAPI, health e compatibilidade Android.
- Mudança de release: package, versão, assinatura, SHA-256 e scan de segredos.

Em máquina com pouca RAM, builds Android são sequenciais. Consulte o README.

## Git e release

Use issue, branch, implementação, testes, revisão e release. Commits devem ser lógicos. Não usar force push nem mover tags. APKs ficam fora do Git.

## Segurança e recuperação

Android Keystore contém identidade não exportável. Firestore é fonte de verdade cloud. Google Sheets é projeção. Restore e rollback estão em [RESTORE.md](RESTORE.md); problemas operacionais em [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

A credencial Google antiga está pendente de revogação administrativa e não pode voltar ao APK.
