# Troubleshooting

Colete sempre versão, pacote, horário, primeiro erro e estado dos serviços. Não apague dados como primeira ação.

## Bolt não aparece

- Confirmar Bolt online, Accessibility conectado e Notification Listener autorizado.
- Coletar log ao redor do gatilho, leitura da árvore e parser.
- Verificar se a oferta ficou completa após as tentativas de `750/1000/1250/1500 ms`.
- Não substituir o fluxo por polling ou OCR Bolt sem uma nova decisão técnica.

## Uber não aparece

- Android 12L ou inferior: conferir eventos e árvore de Accessibility.
- Android 13 ou superior: conferir permissão de captura, gatilho, frames completos, confirmação OCR e deduplicação.
- Registrar se Uber estava em primeiro plano, segundo plano, launcher, Radar ou Exclusiva.
- Não concluir “OCR falhou” sem distinguir ausência de gatilho, captura parcial, parser e deduplicação.

## Card não abre ou fica em posição errada

- Confirmar se o sistema detectou telefone normal, tela dividida ou DeX.
- Coletar dimensões da janela, insets, modo e coordenadas da pilha.
- Em telefone normal, a pilha deve manter topo fixo e novos cards não recalculam a origem.
- Verificar que o botão Recusar da Bolt permanece livre.

## Sync pendente

- Confirmar rede validada e backend `/_health`.
- Verificar WorkManager, backoff, quantidade da outbox e último código HTTP.
- Erros transitórios devem manter a outbox. Erros permanentes precisam de correção antes de nova tentativa.
- Não editar Room nem marcar eventos manualmente como enviados.

## `LICENSE_ALREADY_BOUND`

A activation key já está vinculada a outra instalação. Confirmar identidade do dispositivo e procedimento administrativo. Não reutilizar chave nem apagar vínculo sem prova de propriedade.

## `INVALID_SIGNATURE`

Confirmar canonicalização, relógio, chave pública registrada e identidade Keystore. Reinstalação pode ter criado uma nova chave. Nunca registrar ou imprimir assinatura completa ou chave privada.

## `TIMESTAMP_OUT_OF_RANGE`

Verificar hora automática, fuso e sincronização do dispositivo. Depois corrigir o relógio e gerar novo nonce/pedido; não reaproveitar o pedido antigo.

## Backend offline

- Consultar `/_health` e logs do Cloud Run.
- Distinguir DNS, timeout, 5xx e indisponibilidade do Firestore.
- Preservar outbox e evitar reinícios contínuos.
- Se uma revisão nova falhou, seguir [RESTORE.md](RESTORE.md).

## Projection atrasada

- Confirmar que eventos existem no Firestore.
- Inspecionar projection outbox, job, IAM e acesso à planilha.
- Repetir lote de forma idempotente; Google Sheets não é fonte de verdade.

## Build sem memória

Sintoma conhecido ao compilar quatro variantes juntas em máquina de 16 GB. Fechar processos pesados e usar os dois comandos sequenciais do [README](../README.md) com `--max-workers=1` e compilador Kotlin no processo. Isso não indica falha funcional.

## Release não instala sobre versão anterior

- Comparar package ID, certificado, `versionCode` e ABI.
- Android bloqueia downgrade e assinatura diferente.
- Não desinstalar antes de confirmar outbox e necessidade de preservar dados.

## Diagnóstico Android

Os logs devem permitir distinguir captura, OCR, parser, decisão, overlay, registro e sync. Nunca registrar activation key, private key, token, assinatura completa, morada, coordenada desnecessária ou payload integral sensível.

## Diagnóstico backend

Use health, Cloud Run logs, códigos de erro estruturados, métricas de rate limiting, Firestore e projection outbox. Pesquise por identificador técnico não sensível; não copie payloads reais para issues.
