# Relatório da Instrução 1 — estado parcial

## Resumo

1. O mecanismo real foi auditado; a Uber usa acessibilidade no Android ≤12 e OCR no Android ≥13, enquanto a Bolt usa notificação + acessibilidade.
2. O baseline oficial foi preservado sem alterações.
3. Dois fluxos Room eager com rematerialização integral são a hipótese comum mais forte para as duas workers e o churn.
4. A causa ainda não está provada por profiling runtime porque nenhum dispositivo ADB estava ligado.
5. Não foi aplicada correção de performance por palpite; a Instrução 2 permanece bloqueada.

## Trabalho concluído

- Build inicial correto por flavor: sucesso.
- Auditoria: `ANALISE_CAUSA_RAIZ.md`.
- Script de captura: `scripts/perf_capture.ps1`.
- Analisador: `scripts/perf_analyze.py`.
- Protocolo: `adb_protocolo.md`.
- Correção independente solicitada para posição dos cards: Bolt e Uber passam pela mesma política fixa em telefone normal; apenas split-screen real ou DeX usam âncora.
- Validação local posterior: 134 testes admin + 134 testes client, zero falhas; assemble admin/client debug e lint admin/client concluídos com sucesso.

## Pendente obrigatório

- Ligar o S10+ Android 12 por ADB.
- Capturar stacks/trace e contadores para provar ou refutar `RoomOfferAnalysisStore` como causa.
- Aplicar somente a correção comprovada.
- Executar três capturas AFTER de repouso, Uber e Bolt.
- Preencher comparação quantitativa BEFORE/AFTER e validar todas as metas.
- Só então iniciar a Instrução 2.

## Risco residual

A política visual foi coberta por testes puros, build e lint, mas ainda precisa ser observada no aparelho real com: Bolt isolada, Uber isolada, ambas simultâneas, tela dividida e DeX/display externo.
