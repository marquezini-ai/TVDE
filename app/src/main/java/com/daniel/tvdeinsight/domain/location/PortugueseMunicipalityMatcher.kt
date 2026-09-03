package com.daniel.tvdeinsight.domain.location

import java.text.Normalizer
import java.util.Locale

/**
 * Identifica municípios portugueses numa morada de recolha.
 *
 * A pesquisa é deliberadamente restrita ao texto após a última vírgula e antes
 * do primeiro número. A correspondência ignora acentos, caixa e pontuação.
 */
class PortugueseMunicipalityMatcher(names: Iterable<String>) {
    private data class Candidate(
        val displayName: String,
        val normalizedName: String
    )

    private val candidates = names
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map { name -> Candidate(name.toMunicipalityDisplayName(), normalize(name)) }
        .filter { it.normalizedName.isNotEmpty() }
        .distinctBy(Candidate::normalizedName)
        .sortedByDescending { it.normalizedName.length }

    fun findInPickupAddress(address: String?): String? {
        val locality = PortugueseAddressFormatter.lastLocalityBeforeNumber(address) ?: return null
        val normalizedLocality = normalize(locality).takeIf(String::isNotEmpty) ?: return null

        return candidates.firstOrNull { candidate ->
            normalizedLocality.findWholePhrase(candidate.normalizedName) >= 0
        }
            ?.displayName
    }

    private fun String.findWholePhrase(phrase: String): Int {
        var start = indexOf(phrase)
        while (start >= 0) {
            val beforeIsBoundary = start == 0 || this[start - 1] == ' '
            val end = start + phrase.length
            val afterIsBoundary = end == length || this[end] == ' '
            if (beforeIsBoundary && afterIsBoundary) return start
            start = indexOf(phrase, start + 1)
        }
        return -1
    }

    private fun normalize(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_LETTERS, " ")
        .replace(MULTIPLE_SPACES, " ")
        .trim()

    private fun String.toMunicipalityDisplayName(): String {
        var firstWord = true
        return lowercase(PORTUGUESE_LOCALE).replace(WORD) { match ->
            val word = match.value
            val keepLowercase = !firstWord && word in LOWERCASE_CONNECTORS
            firstWord = false
            if (keepLowercase) word else word.replaceFirstChar { it.titlecase(PORTUGUESE_LOCALE) }
        }
    }

    private companion object {
        val PORTUGUESE_LOCALE = Locale("pt", "PT")
        val COMBINING_MARKS = Regex("\\p{M}+")
        val NON_LETTERS = Regex("[^\\p{L}]+")
        val MULTIPLE_SPACES = Regex("\\s+")
        val WORD = Regex("\\p{L}+")
        val LOWERCASE_CONNECTORS = setOf("a", "à", "da", "das", "de", "do", "dos", "e")
    }
}

/** Catálogo fechado de municípios fornecido para a aplicação. */
object PortugueseMunicipalityCatalog {
    val names: List<String> by lazy {
        RAW_NAMES.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    val matcher: PortugueseMunicipalityMatcher by lazy {
        PortugueseMunicipalityMatcher(names)
    }

    private const val RAW_NAMES = """ABRANTES
ÁGUEDA
AGUIAR DA BEIRA
ALANDROAL
ALBERGARIA-A-VELHA
ALBUFEIRA
ALCÁCER DO SAL
ALCANENA
ALCOBAÇA
ALCOCHETE
ALCOUTIM
ALENQUER
ALFÂNDEGA DA FÉ
ALIJÓ
ALJEZUR
ALJUSTREL
ALMADA
ALMEIDA
ALMEIRIM
ALMODÔVAR
ALPIARÇA
ALTER DO CHÃO
ALVAIÁZERE
ALVITO
AMADORA
AMARANTE
AMARES
ANADIA
ANGRA DO HEROÍSMO
ANSIÃO
ARCOS DE VALDEVEZ
ARGANIL
ARMAMAR
AROUCA
ARRAIOLOS
ARRONCHES
ARRUDA DOS VINHOS
AVEIRO
AVIS
AZAMBUJA
BAIÃO
BARCELOS
BARRANCOS
BARREIRO
BATALHA
BEJA
BELMONTE
BENAVENTE
BOMBARRAL
BORBA
BOTICAS
BRAGA
BRAGANÇA
CABECEIRAS DE BASTO
CADAVAL
CALDAS DA RAINHA
CALHETA (MADEIRA)
CALHETA (SÃO JORGE)
CÂMARA DE LOBOS
CAMINHA
CAMPO MAIOR
CANTANHEDE
CARRAZEDA DE ANSIÃES
CARREGAL DO SAL
CARTAXO
CASCAIS
CASTANHEIRA DE PÊRA
CASTELO BRANCO
CASTELO DE PAIVA
CASTELO DE VIDE
CASTRO DAIRE
CASTRO MARIM
CASTRO VERDE
CELORICO DA BEIRA
CELORICO DE BASTO
CHAMUSCA
CHAVES
CINFÃES
COIMBRA
CONDEIXA-A-NOVA
CONSTÂNCIA
CORUCHE
COVILHÃ
CRATO
CUBA
ELVAS
ENTRONCAMENTO
ESPINHO
ESPOSENDE
ESTARREJA
ESTREMOZ
ÉVORA
FAFE
FARO
FELGUEIRAS
FERREIRA DO ALENTEJO
FERREIRA DO ZÊZERE
FIGUEIRA DA FOZ
FIGUEIRA DE CASTELO RODRIGO
FIGUEIRÓ DOS VINHOS
FORNOS DE ALGODRES
FREIXO DE ESPADA À CINTA
FRONTEIRA
FUNCHAL
FUNDÃO
GAVIÃO
GÓIS
GOLEGÃ
GONDOMAR
GOUVEIA
GRÂNDOLA
GUARDA
GUIMARÃES
HORTA
IDANHA-A-NOVA
ÍLHAVO
LAGOA (ALGARVE)
LAGOA (SÃO MIGUEL)
LAGOS
LAJES DAS FLORES
LAJES DO PICO
LAMEGO
LEIRIA
LISBOA
LOULÉ
LOURES
LOURINHÃ
LOUSÃ
LOUSADA
MAÇÃO
MACEDO DE CAVALEIROS
MACHICO
MADALENA
MAFRA
MAIA
MANGUALDE
MANTEIGAS
MARCO DE CANAVESES
MARINHA GRANDE
MARVÃO
MATOSINHOS
MEALHADA
MÊDA
MELGAÇO
MÉRTOLA
MESÃO FRIO
MIRA
MIRANDA DO CORVO
MIRANDA DO DOURO
MIRANDELA
MOGADOURO
MOIMENTA DA BEIRA
MOITA
MONÇÃO
MONCHIQUE
MONDIM DE BASTO
MONFORTE
MONTALEGRE
MONTEMOR-O-NOVO
MONTEMOR-O-VELHO
MONTIJO
MORA
MORTÁGUA
MOURA
MOURÃO
MURÇA
MURTOSA
NAZARÉ
NELAS
NISA
NORDESTE
ÓBIDOS
ODEMIRA
ODIVELAS
OEIRAS
OLEIROS
OLHÃO
OLIVEIRA DE AZEMÉIS
OLIVEIRA DE FRADES
OLIVEIRA DO BAIRRO
OLIVEIRA DO HOSPITAL
OURÉM
OURIQUE
OVAR
PAÇOS DE FERREIRA
PALMELA
PAMPILHOSA DA SERRA
PAREDES
PAREDES DE COURA
PEDRÓGÃO GRANDE
PENACOVA
PENAFIEL
PENALVA DO CASTELO
PENAMACOR
PENEDONO
PENELA
PENICHE
PESO DA RÉGUA
PINHEL
POMBAL
PONTA DELGADA
PONTA DO SOL
PONTE DA BARCA
PONTE DE LIMA
PONTE DE SOR
PORTALEGRE
PORTEL
PORTIMÃO
PORTO
PORTO DE MÓS
PORTO MONIZ
PORTO SANTO
PÓVOA DE LANHOSO
PÓVOA DE VARZIM
POVOAÇÃO
PRAIA DA VITÓRIA
PROENÇA-A-NOVA
REDONDO
REGUENGOS DE MONSARAZ
RESENDE
RIBEIRA BRAVA
RIBEIRA DE PENA
RIBEIRA GRANDE
RIO MAIOR
SABROSA
SABUGAL
SALVATERRA DE MAGOS
SANTA COMBA DÃO
SANTA CRUZ
SANTA CRUZ DA GRACIOSA
SANTA CRUZ DAS FLORES
SANTA MARIA DA FEIRA
SANTA MARTA DE PENAGUIÃO
SANTANA
SANTARÉM
SANTIAGO DO CACÉM
SANTO TIRSO
SÃO BRÁS DE ALPORTEL
SÃO JOÃO DA MADEIRA
SÃO JOÃO DA PESQUEIRA
SÃO PEDRO DO SUL
SÃO ROQUE DO PICO
SÃO VICENTE
SARDOAL
SÁTÃO
SEIA
SEIXAL
SERNANCELHE
SERPA
SERTÃ
SESIMBRA
SETÚBAL
SEVER DO VOUGA
SILVES
SINES
SINTRA
SOBRAL DE MONTE AGRAÇO
SOURE
SOUSEL
TÁBUA
TABUAÇO
TAROUCA
TAVIRA
TERRAS DE BOURO
TOMAR
TONDELA
TORRE DE MONCORVO
TORRES NOVAS
TORRES VEDRAS
TRANCOSO
TROFA
VAGOS
VALE DE CAMBRA
VALENÇA
VALONGO
VALPAÇOS
VELAS
VENDAS NOVAS
VIANA DO ALENTEJO
VIANA DO CASTELO
VIDIGUEIRA
VIEIRA DO MINHO
VILA DE REI
VILA DO BISPO
VILA DO CONDE
VILA DO PORTO
VILA FLOR
VILA FRANCA DE XIRA
VILA FRANCA DO CAMPO
VILA NOVA DA BARQUINHA
VILA NOVA DE CERVEIRA
VILA NOVA DE FAMALICÃO
VILA NOVA DE FOZ CÔA
VILA NOVA DE GAIA
VILA NOVA DE PAIVA
VILA NOVA DE POIARES
VILA POUCA DE AGUIAR
VILA REAL
VILA REAL DE SANTO ANTÓNIO
VILA VELHA DE RÓDÃO
VILA VERDE
VILA VIÇOSA
VIMIOSO
VINHAIS
VISEU
VIZELA
VOUZELA"""
}
