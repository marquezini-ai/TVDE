# Changelog

Este arquivo registra apenas mudanças comprovadas pelo Git e pela documentação preservada.

## [0.6.0-unified] - 2026-10-03

### Adicionado

- Backend FastAPI intermediário com Cloud Run e Firestore.
- Contrato de sync API v1 com autenticação ECDSA P-256 por instalação.
- Identidade privada não exportável no Android Keystore.
- Registro seguro, idempotência, proteção contra replay, cursor/delta e agregados.
- Room schema 5 com outbox durável e sincronização pelo WorkManager.
- Projection outbox e Google Sheets como projeção reconstruível.
- Emissão de licença Client pelo backend para a variante Admin.
- Recuperação controlada após indisponibilidade de rede.

### Segurança

- Removido dos novos APKs o acesso direto ao Google Sheets e a credencial Google legada.
- Dados brutos de outros motoristas não são distribuídos aos clientes.
- Quatro APKs inspecionados e assinaturas verificadas.

### Verificação

- 308 testes Android aprovados.
- 59 testes backend aprovados; 3 integrações cloud permaneceram opt-in.
- Client/Admin, Debug/Release compilados e registrados por SHA-256.

### Pendente

- Revogação administrativa da chave Google antiga, bloqueada por IAM no projeto legado.

## [0.5.69-unified] - 2026-09-30

- Estado funcional preservado no commit `9cc4ae4`.
- Baseline anterior à migração do sync direto com Google para o backend seguro.
- Detalhes comprovados em [docs/preservation/0.5.69-functional-state.md](docs/preservation/0.5.69-functional-state.md).

Versões anteriores possuem changelogs históricos separados. Eles não foram consolidados além do que o Git comprova.
