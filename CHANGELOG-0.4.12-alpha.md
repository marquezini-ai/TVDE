# TVDE Insight 0.4.12-alpha

- Extração de município validada pelo catálogo português e pelo trecho após a última vírgula/antes do primeiro número.
- País omitido das moradas apresentadas e exportadas.
- Rota Google Maps com posição no momento da oferta, recolha e destino.
- OCR Uber com aquecimento, gatilho visual antecipado, nova tentativa de leituras parciais e diagnóstico imediato no log.
- Tolerância a caixas OCR sobrepostas e métricas divididas em até quatro linhas.
- Endereços de recolha/destino com até quatro linhas.
- Ciclo de vida do overlay libertado corretamente ao fechar o card.
- Testes unitários e lint executados nas variantes Cliente e Administrador.
