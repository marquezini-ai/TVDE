## 1. BASELINE OFICIAL (BEFORE) — DADOS MEDIDOS POR ADB NO APARELHO REAL

Copie esta secção, sem alterações, para `docs/perf/baseline/BASELINE_OFICIAL.md`. Se os ficheiros brutos originais (`tvde-work-*.txt`, `tvde-bolt-work-*.txt`) estiverem no repositório ou workspace, copie-os para `docs/perf/baseline/raw/` e use-os para validar o parser da Fase 6. Se não estiverem, os números abaixo são o baseline oficial.

Processo: `com.daniel.tvdeinsight`. PID durante os testes originais: 16033 (o PID muda entre execuções; obtenha-o sempre com `pidof`).

### 1.A REPOUSO (TVDE Insight ativo, sem processamento)
- CPU: 0% user + 0% kernel. `top -H`: ~47 threads, 0 running, todas sleeping, 0,0%. Sem busy-loop permanente em repouso.
- TOTAL PSS 290.564 KB; TOTAL RSS 426.308 KB; SWAP PSS 209 KB.
- Java Heap: PSS ≈54.276 KB; RSS ≈69.484 KB.
- Native Heap: PSS ≈187.776 KB; RSS ≈188.896 KB; Heap Size 343.336 KB; Heap Alloc 117.899 KB; Heap Free 220.883 KB.
- Dalvik: Heap Size 75.138 KB; Heap Alloc 50.562 KB; Heap Free 24.576 KB.

### 1.B UBER DRIVER + TVDE INSIGHT (durante receção/leitura/classificação de propostas)
- `top` (amostras): TVDE Insight ≈225%, ≈252%, ≈240%. Uber Driver ≈108–140% em diferentes amostras.
- `dumpsys cpuinfo`, janela de 5 minutos: Load 17.67 / 15.79 / 10.42. TVDE Insight 149% total (144% user + 5.8% kernel); page faults 1.997.047 minor e 9.106 major. Ou seja, ≈149% de CPU MÉDIA em 5 minutos, não apenas pico.
- `top -H` (amostra principal): DefaultDispatch TID 16207 = 100%; DefaultDispatch TID 16082 = 96,9%; HeapTaskDaemon TID 16047 = 51,5%; ReferenceQueueD TID 16048 = 45,4%. Outras amostras: DefaultDispatch ≈90% e ≈88% com HeapTaskDaemon ≈40% e ReferenceQueueD ≈26%; DefaultDispatch 100% e 98% com HeapTaskDaemon ≈32% e ReferenceQueueD ≈17%. Padrão repetido: DUAS DefaultDispatcher a consumir ≈1 core cada durante o processamento.
- GC (logcat): `NativeAlloc concurrent copying GC` ≈1 vez por segundo. Exemplos: 355.956 objetos/9.393 KB/314 ms; 343.887/9.297 KB/456 ms; 386.255/10.210 KB/411 ms; ≈426k–450k objetos/≈10–11 MB/≈449–459 ms; ≈468k–469k/≈12 MB/≈456–487 ms; ≈467k–472k/≈11–12 MB/≈497–500 ms; 444.571/≈11 MB/897 ms; 568.369/≈14 MB/638 ms. Padrão: ≈300.000–568.000 objetos e ≈8–14 MB descartados por GC, duração tipicamente ≈250–500 ms, com eventos maiores.
- Memória sob carga: TOTAL PSS 356.338 KB; RSS 488.096 KB; SWAP PSS 5.580 KB; Java Heap ≈78.632 KB PSS; Native Heap ≈221.440 KB PSS; Code ≈29.416 KB; Graphics ≈6.316 KB. Native Heap: Heap Size 551.208 KB; Heap Alloc 163.111 KB; Heap Free 383.475 KB. Dalvik: Heap Size 96.584 KB; Heap Alloc 72.008 KB.
- Repouso → Uber: PSS 290.564 → 356.338 KB; RSS 426.308 → 488.096 KB; Native Heap PSS 187.776 → 221.440 KB; Java Heap PSS 54.276 → 78.632 KB.

### 1.C BOLT DRIVER + TVDE INSIGHT (mesmo fenómeno reproduzido)
- `dumpsys cpuinfo` (janela não registada): Load 17.27 / 17.21 / 12.56. TVDE Insight 252% (245% user + 7,6% kernel), 101.461 minor faults. Bolt Driver 11%; Uber Driver (ainda presente no snapshot) 8,3%.
- `top -H`: DefaultDispatch TID 16081 = 117% e TID 16208 = 113% (leituras >100% podem ocorrer por arredondamento/amostragem), HeapTaskDaemon TID 16047 = 113%, ReferenceQueueD 3,4%. Outras amostras: DefaultDispatch 99%/99% com HeapTaskDaemon 33% e ReferenceQueueD 29%; 96%/93% com HTD 43% e RQD 18%; 90%/90% com HTD 39% e RQD 23%; 100%/100% com ReferenceQueueD 75% e HeapTaskDaemon 50%. Padrão das duas DefaultDispatcher saturadas repetido com Bolt.
- GC: `NativeAlloc concurrent copying GC` praticamente contínuo. Exemplos: 310.445 objetos/8.143 KB/262 ms; 346.872/9.078 KB/420 ms; 328.881/8.694 KB/260 ms; 340.685/8.874 KB/433 ms; 369.212/9.761 KB/447 ms; 373.703/9.945 KB/427 ms; 332.987/8.823 KB/501 ms; 366.630/9.741 KB/670 ms; 389.593/≈10 MB/421 ms; 423.119/≈10 MB/464 ms; 463.458/≈11 MB/470 ms; 476.472/≈12 MB/479 ms; 490.417/≈12 MB/477 ms. Depois continua ≈300k–430k objetos e ≈8–11 MB por GC, intervalo típico ≈1 s.
- Memória sob carga: TOTAL PSS 444.747 KB; RSS 574.476 KB; SWAP PSS 5.669 KB. Java Heap 85.220 KB PSS / 106.548 KB RSS. Native Heap 303.780 KB PSS / 304.856 KB RSS; Native Heap Size 553.000 KB, Alloc 173.666 KB, Free 374.434 KB. Dalvik: PSS 83.756 KB; RSS 88.128 KB; Heap Size 102.998 KB; Alloc 78.422 KB; Free 24.576 KB. Graphics 6.320 KB.

### 1.D TÉRMICO / SAMSUNG SIOP (contexto e consequência, NÃO a causa)
- `dumpsys thermalservice` no momento da leitura: Thermal Status 1; AP 47,7 °C; PA 40,9 °C; BAT 37,4 °C; USB 37,1 °C; SKIN 37,8 °C (status 1).
- Samsung Device Health Manager: SIOP chegou ao nível 4 (`siop_level = 4`; `[#CMH#] SIOP level is high = 4`). Depois, com menos carga/temperatura: `siop_level = 3` e depois `siop_level = 2`.
- Throttling registado no log: `limitGPUFreq:: freq = 377000`, `SIOP_GPU_FREQ_MAX`, `SIOP_ARM_MAX`, `HYPER-HAL: [GPUMaxFreq / 377000]`, `HYPER-HAL: [CPUMaxFreq / 1378000]`.
- Cadeia causal a testar DEPOIS da correção (não afirmar mais do que os logs demonstram; a contribuição térmica de cada componente NÃO foi isolada experimentalmente):
  TVDE Insight com carga excessiva → CPU muito alta → allocation churn → GC contínuo → maior consumo/aquecimento → SIOP Samsung intervém → limites CPU/GPU → degradação adicional do desempenho disponível.
- O SIOP é CONSEQUÊNCIA. Não o trate como causa nem como coisa a "corrigir".

### 1.E FATOS MEDIDOS (o que os dados permitem afirmar)
1. Em repouso ≈0% CPU. 2. Com o pipeline de leitura/processamento ativo, o processo sobe para ≈200–260% em várias amostras. 3. Duas threads DefaultDispatcher ficam repetidamente perto de 100%. 4. HeapTaskDaemon e ReferenceQueueDaemon apresentam atividade elevada. 5. O runtime executa `NativeAlloc concurrent copying GC` ≈1 vez por segundo. 6. Cada ciclo pode descartar centenas de milhares de objetos e ≈8–14 MB. 7. Ocorre com Uber e com Bolt. Logo o problema está provavelmente em código comum ou comportamento partilhado do pipeline de monitoramento/processamento. NÃO está provado qual função ou API concreta produz as alocações.

### 1.F LEITURAS ARITMÉTICAS DOS DADOS (INFERÊNCIAS A VALIDAR, NÃO FATOS)
Trate estas observações apenas como pistas para orientar a investigação; confirme ou refute cada uma com evidência:
- Tamanho médio por objeto liberado ≈25–27 bytes (ex.: 9.393 KB / 355.956 ≈ 26 B; 12 MB / 468k ≈ 26 B; 14 MB / 568k ≈ 25 B). Isso é compatível com MUITOS objetos pequenos de vida curta, e menos com poucos buffers grandes. Se houver buffers grandes, eles não dominam a contagem de objetos.
- Taxa de alocação ≈10 MB/s e ≈350k objetos/s durante o processamento. O custo do GC (≈250–900 ms por ciclo, ≈1/s) é compatível com HeapTaskDaemon ≈30–50%.
- O motivo do GC no log é "NativeAlloc". Descubra o que, no código, faz o runtime contabilizar alocações nativas. Isso pode incluir tipos que registam memória nativa ou usam referências/limpeza (o que também seria coerente com a atividade elevada de ReferenceQueueDaemon). Não assuma qual; demonstre.
- Native Heap PSS subiu de ≈188 MB (repouso) para ≈221 MB (Uber) e ≈304 MB (Bolt); o Java Heap PSS subiu de ≈54 MB para ≈79–85 MB. Verifique se isso é retenção (leak) ou só pico transitório do churn; não os confunda.
- A saturação de 2 workers Default sugere dois fluxos de trabalho concorrentes (ou duplicados) a ocupar o dispatcher.

### 1.G COMANDOS ORIGINAIS DO BASELINE (a reutilizar no AFTER)
```
adb shell pidof com.daniel.tvdeinsight
adb shell top -H -p <PID> -d 1 -n 60 > tvde-work-top.txt
adb shell top -d 1 -n 60 > tvde-work-system-top.txt
adb logcat -c
adb logcat --pid=<PID> -v threadtime > tvde-work-logcat.txt
# no final do teste:
adb shell dumpsys cpuinfo > tvde-work-cpuinfo.txt
adb shell dumpsys meminfo com.daniel.tvdeinsight > tvde-work-mem.txt
adb shell ps -T -p <PID> > tvde-work-threads.txt
# térmico:
adb shell dumpsys thermalservice
# Samsung SIOP/HYPER-HAL:
adb logcat -d | findstr /I "SIOP HYPER thermal GPUFreq ARM_MAX GPUMaxFreq"
```
Para Bolt foram usados os mesmos comandos, com ficheiros `tvde-bolt-work-*.txt`.
