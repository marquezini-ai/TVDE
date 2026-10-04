# Baseline profissional do TVDE Insight

## Identidade

- Produto: TVDE Insight.
- Versão funcional: `0.6.0-unified`.
- `versionCode`: `173`.
- Commit dos APKs: `dbbdbdcaec17799c8212b2d565117c95ea59aac9`.
- Tag anotada: `v0.6.0-unified`, apontando para o commit dos APKs.
- Backend validado: Cloud Run revision `tvde-backend-test-api-00008-c94`.

## Evidência

- Quatro APKs com pacote, versão, certificado e SHA-256 registrados.
- 308 testes Android aprovados.
- 59 testes backend aprovados e 3 integrações cloud opt-in ignoradas.
- Lint Client/Admin com zero erros; avisos de manutenção documentados.
- OpenAPI sem diferença e scan de arquivos rastreados sem segredo detectado.
- Validação física registrada no relatório da release.

## Fonte de verdade e recuperação

- Código: Git/GitHub, commits e tag imutável.
- Dados cloud: Firestore.
- Relatório: Google Sheets como projeção reconstruível.
- Dados ainda não enviados: outbox local Android.
- Identidade Android: Keystore não exportável.
- Artefatos: fora do Git, conferidos por SHA-256.

## Documentação operacional

- [Arquitetura](ARCHITECTURE.md)
- [Release](releases/0.6.0-unified.md)
- [Checklist](RELEASE-CHECKLIST.md)
- [Backup](BACKUP.md)
- [Restore](RESTORE.md)
- [Disaster recovery](DISASTER-RECOVERY.md)
- [Troubleshooting](TROUBLESHOOTING.md)
- [Manutenção por IA](AI-MAINTENANCE-GUIDE.md)

## Pendência administrativa

A chave Google legada está **PENDING ADMINISTRATIVE ACTION**. Ela não é usada pelos APKs ou backend atuais. O procedimento seguro está em [LEGACY-GOOGLE-CREDENTIAL-REVOCATION.md](security/LEGACY-GOOGLE-CREDENTIAL-REVOCATION.md).

## Regra para desenvolvimento futuro

Trabalhar por issue, branch, implementação, testes, revisão e release. Não criar novas fases de profissionalização. Mudanças em captura, OCR, Accessibility, parsers, RuleEngine, overlay ou sync exigem análise focada e validação proporcional ao risco.
