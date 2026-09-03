# Entrega 0.1.4-alpha

- Corrigida a fila de captura/OCR da Uber no Android 13+ com serialização, polling e watchdog.
- A captura agora observa o ecrã completo para reconhecer um card Uber sobreposto a outra app.
- A Uber pode ser aberta automaticamente após 2 s quando o card é detetado fora do primeiro plano; nenhuma ação de toque é enviada.
- Removida toda a opção e implementação de guardar capturas de tela.
- Adicionado log persistente exportável pela tela de Configurações.
- Removidos métodos e dependência de navegação sem uso.

## Verificação

- `testDebugUnitTest`: passou.
- `assembleDebug`: passou.
- `assembleRelease`: passou.
