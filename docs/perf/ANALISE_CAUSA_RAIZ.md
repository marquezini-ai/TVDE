# Fase 2 — auditoria e estado da causa raiz

## Mecanismo real de leitura

| Fluxo | Entrada e leitura | Extração e decisão | Persistência e apresentação |
|---|---|---|---|
| Uber Android 12 ou inferior | `UberOfferAccessibilityService.kt:328`; resolução da janela/raiz; `processUberAccessibilityTree()` em `:731` | `UberOfferParser`; `EvaluateOfferUseCase` | `handleOffer()` em `:2356`; `RoomOfferAnalysisStore.publish()`; `DecisionOverlayManager` |
| Uber Android 13 ou superior | evento semântico e polling adaptativo; `takeScreenshotOfWindow` em `:1214`; Bitmap; ML Kit em `:1397/:1421`; OpenCV | `UberOfferCardTextExtractor`; `UberOfferParser`; `EvaluateOfferUseCase` | mesmo caminho comum |
| Bolt | `BoltOfferNotificationListener.kt:34` aciona a acessibilidade; `consumeBoltRoot()` em `UberOfferAccessibilityService.kt:487` | `BoltOfferParser`; `EvaluateOfferUseCase` | mesmo caminho comum |

O serviço de acessibilidade usa um `CoroutineScope(SupervisorJob() + Dispatchers.Default)`. Em `onServiceConnected()` inicia um coletor de configurações, limpeza periódica e, só no Android 13+, polling adaptativo de OCR. Há ainda um executor único para visão. A decisão, persistência e overlay são partilhados por Uber e Bolt.

Fluxo textual comum: callback/gatilho → pré-filtro barato → árvore de acessibilidade (Uber ≤12/Bolt) ou screenshot/OCR (Uber ≥13) → parser → `EvaluateOfferUseCase` → `RoomOfferAnalysisStore.publish()` (`:2413`) → `DecisionOverlayManager.showDecision()` (`:2427`) → ação automática já existente quando configurada.

## Concorrência, duplicação, backlog e realimentação

- Serviço de acessibilidade: scope Default em `UberOfferAccessibilityService.kt:79`; coletor de settings em `:282`; limpeza suspensa em `:299`; polling OCR, apenas API 33+, em `:311`. O scope é cancelado em `onDestroy`, mas os jobs não têm handles e `onServiceConnected()` não é idempotente. Uma reconexão da mesma instância pode duplicá-los; isto precisa de contador/runtime para confirmação.
- Persistência: scope IO em `RoomOfferAnalysisStore.kt:34`; dois `stateIn(... Eagerly ...)` em `:44/:48`; ambos permanecem ativos mesmo sem História/Estatísticas visível.
- Overlay: scope Main em `DecisionOverlayManager.kt:65`; um coletor de tema e um único hide job substituível por plataforma. Não há worker Default aqui.
- Reserva Bolt: executor único em `BoltReservationCoordinator`; não cria paralelismo ilimitado.
- Não existem `Channel.UNLIMITED`, buffers ilimitados ou `SharedFlow` com capacidade extra no código auditado. O OCR tem um gate e fila latest-wins; as novas tentativas usam um único `Runnable` substituível.
- `onAccessibilityEvent()` pode receber rajadas, mas a Bolt não analisa eventos comuns: só a notificação inicia a leitura. Uber ≤12 usa pré-filtro/deduplicação; Uber ≥13 usa gate visual e de screenshot.
- O próprio overlay gera eventos de janela, porém eventos do pacote TVDE não entram nos parsers Uber/Bolt. O relayout também compara os parâmetros antes de chamar `updateViewLayout`. Não foi demonstrado ciclo de realimentação.

## Evidência estática relevante

1. `RoomOfferAnalysisStore.kt:42-48` cria dois fluxos Room ilimitados e eagerly active: histórico do dispositivo e histórico global. Cada `publish()` em `:63` insere na mesma tabela e invalida os dois `SELECT *` de `TripDao.kt:13/:16`.
2. Cada emissão converte novamente todas as linhas em objetos de domínio. `TripEntityMapper.toDomain()` (`TripEntity.kt:99-139`) cria conjuntos/mapas por `splitToSequence` (`:124/:127`), pares, enums, strings normalizadas e um objeto completo por linha, duas vezes.
3. Os dois fluxos correm no dispatcher IO, que usa workers do scheduler de coroutines apresentados como `DefaultDispatcher-worker-*`. Isto é coerente com as duas threads saturadas do baseline e com o facto de ocorrer tanto para Uber como para Bolt.
4. O baseline mostra dois workers saturados e centenas de milhares de objetos pequenos por segundo. A combinação de duas consultas integrais, duas rematerializações integrais e invalidação a cada inserção é a explicação comum mais forte encontrada no código.
5. Parsers e travessias também alocam; há Regex criadas dentro de `UberOfferParser.parse()` e muitos wrappers `AccessibilityNodeInfo` na Bolt. Porém são caminhos curtos/acionados por oferta e, sem profiling, não explicam sozinhos CPU média sustentada durante cinco minutos.
6. O overlay tem listeners de layout e relayout, mas evita `updateViewLayout` quando os parâmetros não mudam. Não foi encontrada prova estática de loop permanente.

## Conclusão honesta

O defeito dos dois fluxos Room eager é um candidato de alta confiança à causa comum, mas **a causa raiz ainda não está demonstrada** pelos dois tipos de evidência runtime exigidos. O baseline não contém stacks, trace de métodos ou allocation recording que ligue os workers a `RoomOfferAnalysisStore`/`TripEntityMapper`. Sem dispositivo ligado, não é correto aplicar a correção de performance e declarar sucesso.

Antes de alterar esse hot path é obrigatório capturar, no S10+, pelo menos: stacks/trace das duas threads durante a carga e contagem/taxa de queries/mapeamentos por inserção. Depois da correção serão necessárias três execuções por cenário (repouso, Uber e Bolt) com o mesmo protocolo.

## Plano mínimo condicionado à prova

Se o profiling ligar as duas workers aos dois fluxos Room, mudar apenas esses fluxos de `SharingStarted.Eagerly` para subscrição pelo ciclo de vida da UI e impedir rematerialização integral quando História/Estatísticas não estão visíveis. Se apontar para jobs duplicados, tornar `onServiceConnected()` idempotente com handles/cancelamento. Regex, travessia e OCR só serão alterados se aparecerem entre os call sites dominantes; nenhuma espera artificial será usada.

## Problema independente confirmado: posição dos cards

O código atual decide “split-screen/DeX” apenas pela proporção da janela-alvo. Um card/janela transitória pequena da Bolt pode, portanto, ser confundido com uma pane real, ativando posicionamento por âncora. Isso explica por que cards Bolt ainda usam o comportamento antigo enquanto os Uber ficam na faixa fixa. A correção deve detectar o modo do sistema, não inferi-lo pelo tamanho do alvo, e aplicar a mesma faixa fixa às duas plataformas em telefone normal.

## Estado da Instrução 2

Não iniciada. A sua pré-condição exige causa raiz corrigida, instrumentação, scripts e comparação BEFORE/AFTER validada em dispositivo. Essas condições ainda não existem.
