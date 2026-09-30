# Protocolo ADB comparável

Executar no mesmo S10+, com o mesmo modo de energia, brilho, ecrã ligado e temperaturas iniciais semelhantes. Fazer pelo menos três execuções de repouso, Uber e Bolt; deixar arrefecer entre execuções. Para Uber/Bolt, o script usa 240 s de warm-up e 60 s de captura, totalizando a janela mínima de cinco minutos antes do `dumpsys cpuinfo`.

```powershell
.\scripts\perf_capture.ps1 -Label before -Scenario repouso -Run 1
.\scripts\perf_capture.ps1 -Label before -Scenario uber -Run 1
.\scripts\perf_capture.ps1 -Label before -Scenario bolt -Run 1
py -3 .\scripts\perf_analyze.py .\docs\perf\before\uber\run-1-<data>
```

Repetir com `-Run 2` e `-Run 3`; depois da correção usar `-Label after`. Registar atividade comparável de ofertas, CPU dos processos Uber/Bolt, temperatura AP/PA/BAT/USB/SKIN e sequência SIOP. Temperatura e SIOP são contexto/consequência; o protocolo não permite atribuir causalidade térmica isolada.

## Estado desta entrega

Os scripts foram preparados, mas não executados no aparelho porque não havia dispositivo ADB ligado. O analisador também não pôde ser validado contra raw BEFORE porque esses ficheiros não estavam no workspace.
