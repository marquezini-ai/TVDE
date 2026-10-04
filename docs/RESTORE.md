# Restore e rollback

Legenda:

- **TESTADO**: executado no baseline atual.
- **DOCUMENTADO**: procedimento definido, sem ensaio completo nesta fase.
- **NÃO TESTADO**: depende de infraestrutura, backup ou dispositivo específico.

## A. Recuperar código

Status: **DOCUMENTADO**.

1. Clonar o repositório oficial.
2. Buscar tags e confirmar o remoto.
3. Fazer checkout da tag ou commit desejado sem reescrever histórico.
4. Comparar `versionName`, `versionCode` e relatório da release.
5. Configurar arquivos locais a partir dos exemplos, sem copiar segredos para o Git.
6. Executar testes antes de gerar artefatos.

## B. Recuperar backend

Status: rollback por revisão **DOCUMENTADO**; health atual **TESTADO**.

1. Verificar `/_health` e logs antes de alterar tráfego.
2. Listar revisões do serviço Cloud Run.
3. Identificar a última revisão comprovadamente funcional.
4. Direcionar tráfego para essa revisão.
5. Validar health, autenticação, sync e métricas.

```powershell
gcloud run revisions list --service tvde-backend-test-api --region europe-southwest1 --project project-3fe6c5dd-dd46-4e67-b9c
gcloud run services update-traffic tvde-backend-test-api --region europe-southwest1 --to-revisions REVISION=100 --project project-3fe6c5dd-dd46-4e67-b9c
```

Rollback de código não reverte dados do Firestore.

## C. Recuperar Firestore

Status: **NÃO TESTADO** ponta a ponta.

1. Parar escrita ou colocar o backend em condição controlada.
2. Confirmar projeto, database, horário e integridade do export.
3. Importar primeiro em ambiente isolado.
4. Validar schema, número de eventos, cursores, idempotência e outbox.
5. Só então planejar import no ambiente alvo com janela e rollback definidos.

```powershell
gcloud firestore import gs://BUCKET/PREFIX --database="(default)" --project=PROJECT_ISOLADO
```

Não importar cegamente sobre dados atuais.

## D. Reconstruir projection

Status: mecanismo de outbox **TESTADO** por testes; reconstrução integral **NÃO TESTADA**.

1. Preservar Firestore.
2. Corrigir acesso ou destino da planilha.
3. Identificar itens `PENDING` ou reabrir projeção por ferramenta aprovada.
4. Executar projetor em lote pequeno.
5. Conferir deduplicação, contagem e amostras sem expor dados pessoais.

Apagar a planilha não deve apagar eventos do Firestore.

## E. Recuperar dispositivo Android

Status: **DOCUMENTADO**.

1. Não limpar dados antes de verificar outbox e diagnóstico.
2. Confirmar versão instalada, licença, acessibilidade, notificações e sobreposição.
3. Se a identidade Keystore ainda existir, permitir que o sync conclua.
4. Se o dispositivo foi perdido ou o Keystore sumiu, tratar como nova instalação e novo vínculo autorizado.

## F. Reinstalar aplicativo

Status: instalação do baseline em dispositivo foi **TESTADA** antes do fechamento; restore integral do estado local é **NÃO TESTADO**.

1. Verificar pacote e certificado do APK.
2. Confirmar compatibilidade da versão com o banco existente.
3. Fazer atualização sobre a instalação quando certificado e package ID coincidirem.
4. Após reinstalação limpa, reconfigurar permissões e novo registro.

## G. Perda do Android Keystore

Status: **DOCUMENTADO**.

A chave privada não pode ser restaurada por cópia comum. Revogar ou desativar o vínculo antigo conforme política administrativa, criar nova identidade no dispositivo e realizar novo registro autorizado. Não inserir chave exportável como atalho.

## H. Outbox pendente

Status: retenção e repetição **TESTADAS**.

- Manter app e dados instalados.
- Recuperar rede e observar WorkManager.
- Diagnosticar erros permanentes antes de repetir manualmente.
- Não marcar item como enviado nem apagar banco para “destravar”.

## I. Restaurar versão anterior

Status: **DOCUMENTADO**.

O baseline anterior preservado é `0.5.69-unified`, commit `9cc4ae4`, mas não deve ser instalado sobre schema ou identidade novos sem análise.

Downgrade não é seguro quando:

- a versão anterior não conhece o schema Room atual;
- existe outbox pendente no formato novo;
- o backend antigo distribui ou espera credenciais removidas;
- o Android rejeita downgrade de `versionCode`;
- certificado ou package ID diferem.

Preferir corrigir ou distribuir um novo build compatível. Nunca depender da credencial Google antiga.
