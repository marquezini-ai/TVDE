# ADR-004: baseline de release, variantes e recuperação

Status: aceito em 2026-10-04.

## Contexto

O projeto produz aplicações Client e Admin, um backend cloud e artefatos Android grandes que não devem entrar no Git. Uma release precisa relacionar código, APKs, assinatura, backend, testes e procedimentos de rollback sem depender de credenciais antigas.

## Decisão

- Manter Client e Admin como product flavors separados, cada um com Debug e Release.
- Usar Git, commit anotado no relatório e tag imutável como origem do código.
- Armazenar APKs fora do Git e verificá-los por nome, pacote, versão, certificado e SHA-256.
- Considerar Firestore fonte de verdade e Google Sheets projeção reconstruível.
- Preservar outbox antes de reinstalação, downgrade ou limpeza de dados.
- Fazer rollback Android somente quando schema Room e identidade forem compatíveis.
- Fazer rollback Cloud Run por revisão conhecida, sem reverter Firestore automaticamente.
- Não depender da credencial Google legada em build, operação ou recuperação.

## Alternativas consideradas

- Versionar APKs no Git: rejeitado por tamanho, histórico e baixa auditabilidade.
- Usar Google Sheets como backup principal: rejeitado porque a projeção não contém todas as garantias transacionais.
- Tratar keystore Android como arquivo copiável: rejeitado porque a chave privada pode ser não exportável.
- Fazer deploy automático em todo push: rejeitado; produção continua controlada.

## Consequências

Cada release exige checklist, hashes e assinatura verificada. Backups de Firestore e testes de restore são operações separadas. Downgrade pode ser bloqueado por schema ou perda de identidade. A tag identifica o código da release, enquanto documentação posterior pode evoluir em commits próprios.
