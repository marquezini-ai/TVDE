# Decisões do projeto

## 001 — 2026-08-02
O Rule Engine não conhece interface, Accessibility Service ou overlay.

**Motivo:** separar a decisão de negócio das formas de obter e apresentar dados.

## 002 — 2026-08-02
Regras bloqueantes são avaliadas antes das regras financeiras.

**Motivo:** uma oferta com paragem configurada para rejeição não deve gastar tempo em cálculos.

## 003 — 2026-08-02
O resultado interno usa `ACCEPT`, `REVIEW` e `REJECT`, e não cores.

**Motivo:** a apresentação poderá mudar sem alterar o motor de decisão.

## 004 — 2026-08-02
Na primeira versão, a prioridade escolhida pelo utilizador define a métrica financeira que classifica a oferta.

**Motivo:** evita regras implícitas entre €/km e €/hora até termos critérios de negócio explícitos para combiná-las.

## 005 — 2026-08-02
O Serviço de Acessibilidade apenas recolhe texto visível da Uber Driver em memória e entrega uma `TripOffer` ao Rule Engine.

**Motivo:** mantém captura, decisão e apresentação independentes, sem guardar conteúdo do ecrã nem controlar ações na Uber.

## 006 — 2026-08-02
O overlay apenas apresenta a decisão já calculada. Pode receber um toque para se fechar, mas nunca envia comandos para qualquer outra aplicação.

**Motivo:** preservar uma resposta rápida e visual sem interferir na utilização da Uber Driver.

## 007 — 2026-08-03
O cálculo financeiro de uma oferta Uber soma o percurso até ao passageiro e a viagem com o passageiro.

**Motivo:** ambos consomem tempo e quilómetros, pelo que avaliar apenas um dos trechos distorceria a rentabilidade.

## 008 — 2026-08-03
A captura reconhece uma oferta pelo seu formato visível, além do nome do pacote da Uber.

**Motivo:** algumas versões podem apresentar a oferta através de componentes cujo evento não usa um identificador de pacote Uber.

## 009 — 2026-08-03 (substituída)
A experiência inicial lia periodicamente a árvore da Uber em todas as versões Android.

**Substituída por 011:** a Uber deixou de expor uma árvore útil a partir do Android 13.

## 010 — 2026-08-03
Depois de uma decisão válida, o overlay mantém a cor durante um período curto e ignora eventos subsequentes sem texto.

**Motivo:** a interface da Uber pode emitir eventos vazios durante a animação da oferta; esses eventos não podem apagar uma decisão já calculada.

## 011 — 2026-08-23
Android 12L e inferiores usam acessibilidade para Uber e Bolt; Android 13 e superiores usam OCR apenas para Uber e acessibilidade apenas para Bolt.

**Motivo:** evita tentativas inúteis e atrasos entre tecnologias incompatíveis com cada versão/plataforma.

## 012 — 2026-08-23
O OCR da Uber exige duas leituras completas e coincidentes, com intervalo de 250 ms, e nunca persiste a captura de ecrã.

**Motivo:** impedir decisões baseadas num cartão ainda em animação sem aumentar desnecessariamente o tempo de resposta.

## 013 — 2026-08-23
O município de recolha é procurado apenas no texto depois da última vírgula e antes do primeiro número, contra um catálogo fechado de municípios portugueses.

**Motivo:** impedir que nomes de ruas, freguesias ou códigos postais sejam apresentados como município.

## 014 — 2026-08-23
A rota do histórico usa a posição no momento da oferta como origem, a recolha como paragem intermédia e o destino como fim.

**Motivo:** mostrar no Google Maps todo o percurso que influencia a avaliação económica da oferta.
