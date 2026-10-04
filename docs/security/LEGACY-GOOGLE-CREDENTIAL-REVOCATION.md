# Revogação da credencial Google legada

Estado: **PENDING ADMINISTRATIVE ACTION**.

## Identificação

- Projeto legado: `tvde-insight-sync`.
- Service Account: `tvde-insight-sync@tvde-insight-sync.iam.gserviceaccount.com`.
- Key ID: `c85ad9713e04d864648501e625390978e87b0b44`.

O material privado não está documentado nem versionado. Os APKs `0.6.0-unified` e o backend atual não dependem dessa chave.

## Motivo

A arquitetura anterior colocava uma credencial permanente no Android para escrever diretamente no Google Sheets. A arquitetura atual usa backend, identidade gerenciada e Secret Manager. A chave antiga deve ser removida para eliminar o risco residual.

## Bloqueio atual

A conta autenticada `marquezini@outlook.pt` não possui `iam.serviceAccountKeys.delete` no projeto legado. A tentativa de listar e remover a chave foi recusada por IAM. Isso não é falha do código atual.

## Procedimento autorizado

1. Acessar `tvde-insight-sync` com uma conta autorizada.
2. Confirmar exatamente a Service Account e o Key ID acima.
3. Confirmar que os APKs e o backend atuais continuam sem essa dependência.
4. Remover somente essa chave, sem excluir a Service Account ou alterar o projeto cloud atual.
5. Confirmar que a chave não autentica mais.
6. Registrar data, operador e evidência sem guardar tokens ou material privado.

Permissão mínima necessária para a operação: `iam.serviceAccountKeys.delete`, normalmente fornecida por `roles/iam.serviceAccountKeyAdmin`. A concessão deve ser temporária e feita por administrador autorizado.
