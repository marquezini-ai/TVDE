# Disaster recovery

Os procedimentos abaixo são proporcionais ao projeto. Firestore continua sendo a fonte de verdade cloud; dados ainda não enviados podem existir somente na outbox Android.

| Cenário | Impacto e dados em risco | Procedimento e rollback | Verificação |
| --- | --- | --- | --- |
| Cloud Run indisponível | Sync interrompido; outbox cresce, captura local continua | Confirmar incidente e logs; recuperar serviço ou rotear para revisão conhecida | `/_health`, sync de evento sintético e redução da outbox |
| Firestore indisponível | Backend não consegue confirmar eventos | Não apagar outbox; aguardar recuperação; se corrupção, validar export em projeto isolado antes de import | Contagens, cursores, idempotência e health |
| Deploy quebrado | Erros HTTP ou incompatibilidade | Direcionar tráfego para revisão Cloud Run anterior; não reverter dados automaticamente | Health, auth, sync e logs sem erro |
| Credencial revogada incorretamente | Projetor ou serviço específico perde acesso | Restaurar IAM da identidade gerenciada correta; não recriar a chave Android legada | Projetor conclui lote e backend permanece saudável |
| Dispositivo perdido | Outbox local não enviada e identidade Keystore perdidas | Bloquear vínculo antigo quando suportado; registrar novo dispositivo; recuperar apenas dados já confirmados no backend | Nova licença/identidade e sync funcional |
| Room corrompido | Histórico local, critérios ou outbox podem ficar inacessíveis | Preservar diagnóstico e arquivos antes de qualquer limpeza; tentar recuperação em cópia; reinstalar só como último recurso | App inicia, schema válido e reconciliação com backend |
| Google Sheets apagado | Relatório some; Firestore permanece | Criar destino autorizado e reconstruir projection a partir da fonte de verdade | Linhas, deduplicação e amostras conferidas |
| Git local perdido | Ambiente de desenvolvimento perdido | Clonar remoto, buscar tags, configurar segredos locais e executar testes | Commit/tag corretos e build reproduzível |
| Backend retorna erro permanente | Outbox não avança | Classificar código: auth, assinatura, timestamp, vínculo ou schema; corrigir causa sem repetição agressiva | Pedido sintético aceito e outbox preservada |
| Release Android problemática | Captura, UI ou sync afetados | Suspender distribuição; comparar certificado/schema; usar build corretivo ou rollback compatível | Testes e validação física nos cenários afetados |

## Ordem de resposta

1. Preservar evidências e impedir perda adicional.
2. Identificar fonte de verdade e escopo.
3. Evitar limpeza de dados, import ou revogação por tentativa.
4. Recuperar primeiro em ambiente isolado quando houver dados envolvidos.
5. Verificar tecnicamente antes de liberar operação.
6. Registrar causa, ação, horário e resultado sem dados sensíveis.

## Estado dos ensaios

- Health, retry Android, outbox e testes de contrato: validados.
- Rollback real de tráfego Cloud Run: documentado, não executado nesta fase.
- Export/import Firestore: documentado, não executado nesta fase.
- Reconstrução integral da planilha: documentada, não executada nesta fase.
