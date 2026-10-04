# Checklist de release

## Código

- [ ] Branch e commit corretos.
- [ ] Working tree limpa e `origin` sincronizado.
- [ ] `versionName` e `versionCode` confirmados.
- [ ] Changelog e relatório da release atualizados.

## Android

- [ ] Testes Client Debug aprovados.
- [ ] Testes Admin Debug aprovados.
- [ ] Client Debug compilado.
- [ ] Client Release compilado.
- [ ] Admin Debug compilado.
- [ ] Admin Release compilado.
- [ ] Em máquina com pouca RAM, variantes compiladas sequencialmente com `--max-workers=1`.

## Segurança

- [ ] Scan de segredos em arquivos rastreados.
- [ ] APKs inspecionados sem credenciais privadas.
- [ ] `apksigner verify --verbose --print-certs` aprovado em cada APK.
- [ ] Certificados comparados com o baseline esperado.
- [ ] Endpoint do backend e configuração de release revisados.
- [ ] Nenhum segredo, token, senha, activation key ou payload sensível em logs e documentos.

## Backend

- [ ] Testes backend aprovados.
- [ ] OpenAPI exportado e sem diferença.
- [ ] `/_health` retorna `status=ok`.
- [ ] Configuração Cloud Run e Secret Manager revisada sem imprimir valores.
- [ ] Deploy, quando necessário, realizado de forma controlada.

## Artefatos

- [ ] Nomes contêm papel, tipo e versão.
- [ ] Package IDs, `versionName` e `versionCode` validados com `aapt`.
- [ ] Tamanho registrado.
- [ ] SHA-256 registrado em `SHA256SUMS.txt` e no relatório.
- [ ] APKs armazenados fora do Git.

## Validação física

- [ ] Accessibility conectado.
- [ ] Notification Listener conectado.
- [ ] Bolt validada no cenário aplicável.
- [ ] Uber validada no cenário aplicável.
- [ ] Offline, retorno da rede e outbox validados quando o sync mudou.
- [ ] Overlay e botão Recusar verificados quando posicionamento mudou.
- [ ] Limitações não testadas registradas, sem inferência.

## Release

- [ ] Relatório relaciona versão, commit, APK, hash, certificado, backend e testes.
- [ ] Critérios da tag confirmados.
- [ ] Tag anotada criada uma única vez e enviada sem force push.

## Pós-release

- [ ] Health e logs observados.
- [ ] Procedimento de rollback conhecido.
- [ ] Backup recente confirmado antes de mudanças de dados.
- [ ] Pendências administrativas registradas.
