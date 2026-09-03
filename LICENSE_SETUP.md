# Licenciamento offline

O projeto gera dois APKs distintos:

- `clientDebug`: aplicação TVDE Insight para o motorista, com `applicationId` `com.daniel.tvdeinsight`.
- `adminDebug`: aplicação TVDE Insight Admin, com `applicationId` `com.daniel.tvdeinsight.admin`.

O Cliente tem apenas a chave pública ECDSA P-256 em `license-public-key.txt`. A chave de ativação contém, em Base64URL, `v1|ANDROID_ID|EXPIRACAO_EM_MILISSEGUNDOS|TIPO` e uma assinatura `SHA256withECDSA`.

## Chave privada do Administrador

O APK Admin deve ser mantido apenas pelo administrador. A chave privada é lida durante o build em `.gradle/license.properties`, que não entra no ZIP de código-fonte:

```properties
licensePublicKeyBase64=... # opcional: já existe uma chave pública no projeto
adminPrivateKeyBase64=...  # PKCS#8 Base64, secreto
```

Guarde esse ficheiro numa localização privada e com cópia de segurança. Não o envie por WhatsApp, não o adicione ao Git e não o inclua no APK Cliente. Quem obtiver um APK Admin configurado, ou a chave privada, poderá gerar licenças: esta é a limitação inevitável de qualquer emissor offline distribuído num telemóvel. Para uma autoridade de licença resistente à extração, use um serviço remoto/HSM para assinar as ativações.

## Funcionamento no Cliente

1. O motorista toca em **Solicitar** no topo da Home; o WhatsApp abre diretamente na conversa de suporte, com o respetivo `Android ID` já preenchido.
2. O administrador cola o ID na app Admin, indica de 1 a 99 dias, nome completo e telefone, e gera a chave.
3. O motorista cola a chave e toca em **Ativar**.

No Admin, cada chave gerada é guardada localmente em armazenamento cifrado juntamente com o nome, telefone, Android ID, momento de geração, expiração e chave. O botão **Licenças ativas** apresenta apenas as licenças ainda válidas; a contagem de validade inicia no momento em que a chave é gerada.

Em **Licenças ativas**, é possível pesquisar pelo telefone, renovar uma licença para o mesmo Android ID e exportar um backup JSON das licenças válidas. O backup contém dados pessoais e chaves de ativação; guarde-o numa localização privada.

A ativação é guardada em `EncryptedSharedPreferences`. Em cada arranque é validada a assinatura, o ID Android, a expiração e o último horário conhecido. Se o relógio do dispositivo for recuado, a licença é bloqueada até ser renovada com a data/hora correta.

## Builds

```powershell
.\gradlew.bat assembleClientDebug assembleAdminDebug
```

Os APKs ficam em `app/build/outputs/apk/client/debug` e `app/build/outputs/apk/admin/debug`.
