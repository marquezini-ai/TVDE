package com.example.cameraseguranca.data

/** Configurações que a UI grava no DataStore e o serviço lê ao ser armado. */
data class RecordingSettings(
    val lens: CameraLens = CameraLens.BACK,
    val quality: VideoQuality = VideoQuality.HIGH,
    val fps: RecordingFps = RecordingFps.FPS_30,
    val segment: SegmentDuration = SegmentDuration.MINUTES_5,
    val timeLimit: RecordingTimeLimit = RecordingTimeLimit.HOUR_1,
    val storageLocation: StorageLocation = StorageLocation.LOCAL,
    val triggerMode: TriggerMode = TriggerMode.TRIPLE_TAP,
    val audioEnabled: Boolean = false,
    val autoDeleteInterval: AutoDeleteInterval = AutoDeleteInterval.DAYS_7,
    val floatingControlEnabled: Boolean = false
)

enum class CameraLens(val label: String) {
    FRONT("Frontal"),
    BACK("Traseira")
}

enum class VideoQuality(val label: String) {
    LOW("Baixa"),
    MEDIUM("Média"),
    HIGH("Alta"),
    VERY_HIGH("Muito Alta")
}

enum class RecordingFps(val label: String, val value: Int) {
    FPS_15("15 fps", 15),
    FPS_20("20 fps", 20),
    FPS_30("30 fps", 30)
}

enum class SegmentDuration(val label: String, val milliseconds: Long?) {
    MINUTE_1("1 minuto", 60_000L),
    MINUTES_3("3 minutos", 3 * 60_000L),
    MINUTES_5("5 minutos", 5 * 60_000L),
    MINUTES_10("10 minutos", 10 * 60_000L),
    MINUTES_15("15 minutos", 15 * 60_000L),
    NONE("Sem cortes", null)
}

enum class RecordingTimeLimit(val label: String, val milliseconds: Long?) {
    MINUTES_15("15 minutos", 15 * 60_000L),
    MINUTES_30("30 minutos", 30 * 60_000L),
    HOUR_1("1 hora", 60 * 60_000L),
    HOURS_3("3 horas", 3 * 60 * 60_000L),
    HOURS_8("8 horas", 8 * 60 * 60_000L),
    UNLIMITED("Ilimitado", null)
}

/** Local é interno ao app; Cartão SD usa o diretório privado do app no volume removível. */
enum class StorageLocation(val label: String) {
    LOCAL("Local"),
    SD_CARD("Cartão SD")
}

/** Tempo máximo que um MP4 privado permanece no aparelho antes da limpeza automática. */
enum class AutoDeleteInterval(val label: String, val milliseconds: Long) {
    HOURS_24("24 horas", 24L * 60L * 60L * 1000L),
    DAYS_7("7 dias", 7L * 24L * 60L * 60L * 1000L)
}

enum class TriggerMode(val label: String, val description: String) {
    TRIPLE_TAP(
        label = "3 toques",
        description = "Toque três vezes rapidamente no botão flutuante."
    ),
    DOUBLE_TAP_AND_SWIPE(
        label = "2 toques + arrastar",
        description = "Toque duas vezes e, no segundo toque, arraste para a esquerda ou direita."
    )
}
