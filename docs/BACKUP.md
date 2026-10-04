# Política de backup

## Código e releases

- Fonte oficial: repositório Git e remoto GitHub.
- Preservar commits e tags; nunca mover tag publicada.
- APKs ficam fora do Git em armazenamento controlado, junto com `SHA256SUMS.txt` e o relatório da release.
- Conservar ao menos a release atual e a última comprovadamente estável.
- Validar periodicamente clonagem, checkout da tag e comparação dos hashes.

## Android local

### Dados importantes

- Banco Room e outbox pendente.
- Preferências e critérios do motorista.
- Registro do backend, cursor e metadados de sincronização.
- Licença e configurações locais.

### Limites

- O backup comum do arquivo Room não garante cópia consistente enquanto a aplicação escreve.
- WAL e SHM pertencem ao mesmo estado do banco; copiar apenas o arquivo principal pode perder transações.
- A chave privada da identidade de instalação no Android Keystore não é um arquivo copiável. Backup do aplicativo não garante sua recuperação.
- Dados já confirmados pelo backend são recuperáveis pela arquitetura cloud; outbox não enviada pode existir apenas no dispositivo.

Antes de reinstalar, limpar dados ou trocar dispositivo, confirmar outbox vazia ou exportar diagnóstico. A aplicação não possui, neste baseline, um restore integral e validado do estado privado Android.

## Backend e Firestore

Firestore é a fonte de verdade cloud.

Política recomendada proporcional ao projeto:

- Export diário enquanto houver uso real.
- Retenção diária de 30 dias e mensal de 12 meses.
- Bucket dedicado, com versionamento, acesso restrito e região compatível.
- Registrar caminho do export, horário e resultado, sem copiar dados para o Git.
- Testar trimestralmente um import em projeto ou database isolado.

Exemplo, somente após configurar bucket e IAM autorizados:

```powershell
gcloud firestore export gs://BUCKET/PREFIX --database="(default)" --project=project-3fe6c5dd-dd46-4e67-b9c
```

O comando e a política estão **DOCUMENTADOS**. Um ciclo completo de export/import não foi executado nesta fase.

## Google Sheets

Google Sheets é projeção e relatório, não backup da fonte de verdade. Pode ser exportado por conveniência, mas a recuperação correta é reconstruir a projeção a partir do Firestore e da projection outbox.

## Configuração e segredos

Preservar, fora do Git e com acesso restrito:

- keystore de assinatura Android e senhas;
- configuração necessária para reproduzir a assinatura;
- configuração Cloud Run e nomes dos secrets;
- políticas IAM e contas gerenciadas;
- chave privada de assinatura de licenças mantida no Secret Manager;
- IDs operacionais necessários, sem copiar valores secretos para documentação pública.

Manter ao menos duas cópias cifradas do keystore de release em locais independentes. Testar acesso sem divulgar a senha.

## Validação do backup

Um backup só é válido quando possui data, origem, hash ou inventário, acesso testado e procedimento de restore correspondente. A existência de um arquivo não prova recuperação.
