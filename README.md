# TVDE Insight 0.4.12-alpha

Aplicação Android para ajudar motoristas TVDE a classificar ofertas Uber Driver e Bolt Driver.

## Aplicações

- `client`: aplicação licenciada para os motoristas.
- `admin`: aplicação de administração e emissão/renovação de licenças.

## Deteção de ofertas

- Android 12L e inferiores: Uber e Bolt exclusivamente por acessibilidade.
- Android 13 e superiores: Bolt por acessibilidade e Uber exclusivamente por captura global + OCR.
- O OCR não guarda imagens. Uma oferta Uber só é publicada depois de duas leituras completas e coincidentes, separadas por 250 ms.
- Releituras rápidas, diagnóstico de capturas parciais e aquecimento do ML Kit reduzem atrasos e tornam falhas visíveis no log.

## Funcionalidades principais

- Critérios independentes para quilómetros, hora, recolha, valor mínimo, viagens longas e viagens com paradas.
- Custo do veículo, quilómetro livre, valor líquido e portagens Bolt.
- Histórico detalhado, deduplicado e com localização no momento da oferta.
- Rota Google Maps: localização no momento da oferta → recolha → destino.
- Estatísticas por plataforma, categoria, período, turno, tipo de card e município de recolha.
- Exportação das ofertas filtradas para Excel.
- Licenciamento cliente/admin e backup das licenças.
- Log persistente e partilha do diagnóstico completo.

## Compilar

Abra o projeto no Android Studio com JDK 17 ou execute:

```text
gradlew.bat testClientDebugUnitTest testAdminDebugUnitTest assembleClientDebug assembleAdminDebug
```

O serviço precisa das permissões de sobreposição, acessibilidade e localização. A aplicação nunca aceita nem rejeita corridas e não envia cliques para Uber/Bolt.

Consulte [LICENSE_SETUP.md](LICENSE_SETUP.md) antes de distribuir as variantes licenciadas.
