package com.daniel.tvdeinsight.backup

import com.daniel.tvdeinsight.domain.model.NavigationApp
import com.daniel.tvdeinsight.domain.model.RuleSettings
import com.daniel.tvdeinsight.domain.model.VehicleType
import com.daniel.tvdeinsight.reservations.DailyAvailability
import com.daniel.tvdeinsight.reservations.PresentedRide
import com.daniel.tvdeinsight.reservations.ReservationSettings
import com.daniel.tvdeinsight.reservations.TripDistanceScale
import com.daniel.tvdeinsight.ui.theme.ThemeMode
import com.example.cameraseguranca.data.AutoDeleteInterval
import com.example.cameraseguranca.data.CameraLens
import com.example.cameraseguranca.data.RecordingSettings
import com.example.cameraseguranca.data.RecordingTimeLimit
import com.example.cameraseguranca.data.SegmentDuration
import com.example.cameraseguranca.data.StorageLocation
import com.example.cameraseguranca.data.TriggerMode
import com.example.cameraseguranca.data.VideoQuality
import org.json.JSONArray
import org.json.JSONObject

/** Ficheiro portável que preserva as preferências de todas as ferramentas da app. */
object AppDataBackup {
    private const val FORMAT_VERSION = 2

    data class RestoredData(
        val ruleSettings: RuleSettings,
        val themeMode: ThemeMode,
        val reservationSettings: ReservationSettings,
        val reservationHistory: List<PresentedRide>,
        val recordingSettings: RecordingSettings
    )

    fun create(
        ruleSettings: RuleSettings,
        themeMode: ThemeMode,
        reservationSettings: ReservationSettings,
        reservationHistory: List<PresentedRide>,
        recordingSettings: RecordingSettings
    ): String = JSONObject().apply {
        put("type", "tvde-insight-backup")
        put("version", FORMAT_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("ruleSettings", ruleSettings.toJson())
        put("themeMode", themeMode.name)
        put("reservationSettings", reservationSettings.toJson())
        put("reservationHistory", JSONArray().apply { reservationHistory.forEach { put(it.toJson()) } })
        put("recordingSettings", recordingSettings.toJson())
    }.toString(2)

    fun parse(raw: String): RestoredData = JSONObject(raw).let { root ->
        require(root.optString("type") == "tvde-insight-backup") { "Este ficheiro não é um backup TVDE Insight." }
        require(root.optInt("version", 0) in 1..FORMAT_VERSION) { "Versão de backup não suportada." }
        RestoredData(
            ruleSettings = root.getJSONObject("ruleSettings").toRuleSettings(),
            themeMode = ThemeMode.entries.firstOrNull { it.name == root.optString("themeMode") } ?: ThemeMode.AUTOMATIC,
            reservationSettings = root.getJSONObject("reservationSettings").toReservationSettings(),
            reservationHistory = root.optJSONArray("reservationHistory")?.toPresentedRides().orEmpty(),
            recordingSettings = root.optJSONObject("recordingSettings")?.toRecordingSettings() ?: RecordingSettings()
        )
    }

    private fun RecordingSettings.toJson(): JSONObject = JSONObject().apply {
        put("lens", lens.name); put("quality", quality.name); put("segment", segment.name)
        put("timeLimit", timeLimit.name); put("storageLocation", storageLocation.name)
        put("triggerMode", triggerMode.name); put("audioEnabled", audioEnabled)
        put("autoDeleteInterval", autoDeleteInterval.name); put("floatingControlEnabled", floatingControlEnabled)
    }

    private fun JSONObject.toRecordingSettings(): RecordingSettings = RecordingSettings(
        lens = enumOrDefault("lens", CameraLens.BACK),
        quality = enumOrDefault("quality", VideoQuality.HIGH),
        segment = enumOrDefault("segment", SegmentDuration.MINUTES_5),
        timeLimit = enumOrDefault("timeLimit", RecordingTimeLimit.HOUR_1),
        storageLocation = enumOrDefault("storageLocation", StorageLocation.LOCAL),
        triggerMode = enumOrDefault("triggerMode", TriggerMode.TRIPLE_TAP),
        audioEnabled = optBoolean("audioEnabled", false),
        autoDeleteInterval = enumOrDefault("autoDeleteInterval", AutoDeleteInterval.DAYS_7),
        floatingControlEnabled = optBoolean("floatingControlEnabled", false)
    )

    private inline fun <reified T : Enum<T>> JSONObject.enumOrDefault(key: String, fallback: T): T =
        optString(key).takeIf(String::isNotBlank)?.let { saved ->
            enumValues<T>().firstOrNull { it.name == saved }
        } ?: fallback

    private fun RuleSettings.toJson(): JSONObject = JSONObject().apply {
        put("minEurPerKm", minEurPerKm); put("goodEurPerKm", goodEurPerKm)
        put("minEurPerHour", minEurPerHour); put("goodEurPerHour", goodEurPerHour)
        put("idealPickupDistanceKm", idealPickupDistanceKm); put("acceptablePickupDistanceKm", acceptablePickupDistanceKm)
        put("isPickupCriterionEnabled", isPickupCriterionEnabled); put("isKmCriterionEnabled", isKmCriterionEnabled)
        put("isHourCriterionEnabled", isHourCriterionEnabled); put("areEvaluationCriteriaLocked", areEvaluationCriteriaLocked)
        put("longTripMinimumKm", longTripMinimumKm); put("isLongTripCriterionEnabled", isLongTripCriterionEnabled)
        put("minimumTripValue", minimumTripValue); put("isMinimumTripValueCriterionEnabled", isMinimumTripValueCriterionEnabled)
        put("rejectTripsWithStops", rejectTripsWithStops); put("isAppRunning", isAppRunning)
        put("isUberEnabled", isUberEnabled); put("isBoltEnabled", isBoltEnabled); put("isOfferScreenshotCaptureEnabled", isOfferScreenshotCaptureEnabled); put("screenshotRetentionHours", screenshotRetentionHours); put("navigationApp", navigationApp.name)
        put("isVehicleCostPerKmEnabled", isVehicleCostPerKmEnabled); put("vehicleType", vehicleType.name)
        put("vehicleConsumptionPer100Km", vehicleConsumptionPer100Km); put("vehiclePricePerUnit", vehiclePricePerUnit)
    }

    private fun JSONObject.toRuleSettings(): RuleSettings {
        val defaults = RuleSettings()
        return RuleSettings(
            minEurPerKm = optDouble("minEurPerKm", defaults.minEurPerKm), goodEurPerKm = optDouble("goodEurPerKm", defaults.goodEurPerKm),
            minEurPerHour = optDouble("minEurPerHour", defaults.minEurPerHour), goodEurPerHour = optDouble("goodEurPerHour", defaults.goodEurPerHour),
            idealPickupDistanceKm = optDouble("idealPickupDistanceKm", defaults.idealPickupDistanceKm), acceptablePickupDistanceKm = optDouble("acceptablePickupDistanceKm", defaults.acceptablePickupDistanceKm),
            isPickupCriterionEnabled = optBoolean("isPickupCriterionEnabled", defaults.isPickupCriterionEnabled), isKmCriterionEnabled = optBoolean("isKmCriterionEnabled", defaults.isKmCriterionEnabled),
            isHourCriterionEnabled = optBoolean("isHourCriterionEnabled", defaults.isHourCriterionEnabled), areEvaluationCriteriaLocked = optBoolean("areEvaluationCriteriaLocked", defaults.areEvaluationCriteriaLocked),
            longTripMinimumKm = optDouble("longTripMinimumKm", defaults.longTripMinimumKm), isLongTripCriterionEnabled = optBoolean("isLongTripCriterionEnabled", defaults.isLongTripCriterionEnabled),
            minimumTripValue = optDouble("minimumTripValue", defaults.minimumTripValue), isMinimumTripValueCriterionEnabled = optBoolean("isMinimumTripValueCriterionEnabled", defaults.isMinimumTripValueCriterionEnabled),
            rejectTripsWithStops = optBoolean("rejectTripsWithStops", defaults.rejectTripsWithStops), isAppRunning = optBoolean("isAppRunning", defaults.isAppRunning),
            isUberEnabled = optBoolean("isUberEnabled", defaults.isUberEnabled), isBoltEnabled = optBoolean("isBoltEnabled", defaults.isBoltEnabled),
            isOfferScreenshotCaptureEnabled = optBoolean("isOfferScreenshotCaptureEnabled", defaults.isOfferScreenshotCaptureEnabled),
            screenshotRetentionHours = optInt("screenshotRetentionHours", defaults.screenshotRetentionHours),
            navigationApp = NavigationApp.entries.firstOrNull { it.name == optString("navigationApp") } ?: defaults.navigationApp,
            isVehicleCostPerKmEnabled = optBoolean("isVehicleCostPerKmEnabled", defaults.isVehicleCostPerKmEnabled),
            vehicleType = VehicleType.entries.firstOrNull { it.name == optString("vehicleType") } ?: defaults.vehicleType,
            vehicleConsumptionPer100Km = optDouble("vehicleConsumptionPer100Km", defaults.vehicleConsumptionPer100Km), vehiclePricePerUnit = optDouble("vehiclePricePerUnit", defaults.vehiclePricePerUnit)
        ).normalizedThresholds()
    }

    private fun ReservationSettings.toJson(): JSONObject = JSONObject().apply {
        put("categories", JSONArray(categories.toList()))
        put("minimumPerKm", minimumPerKm); put("minimumTripValue", minimumTripValue); put("homeAddress", homeAddress); put("maxPickupDistanceKm", maxPickupDistanceKm); put("maxTripDistanceKm", maxTripDistanceKm)
        put("startMinutes", startMinutes); put("endMinutes", endMinutes); put("refreshDelayMillis", refreshDelayMillis); put("searchWaitMillis", searchWaitMillis)
        put("dryRun", false); put("dailyLimitEnabled", dailyLimitEnabled); put("maxDailyReservations", maxDailyReservations)
        put("criteriaLocked", criteriaLocked); put("availabilityLocked", availabilityLocked); put("refreshLocked", refreshLocked)
        put("weeklyAvailability", JSONObject().apply { weeklyAvailability.forEach { (day, schedule) -> put(day.toString(), JSONObject().put("start", schedule.startMinutes).put("end", schedule.endMinutes)) } })
        put("enabledDays", JSONArray(enabledDays.sorted()))
    }

    private fun JSONObject.toReservationSettings(): ReservationSettings {
        val defaults = ReservationSettings()
        val schedules = optJSONObject("weeklyAvailability")?.let { raw -> buildMap {
            for (day in 1..7) raw.optJSONObject(day.toString())?.let { value -> put(day, DailyAvailability(value.optInt("start"), value.optInt("end"))) }
        } }.orEmpty()
        return ReservationSettings(
            categories = optJSONArray("categories")?.let { array -> buildSet { for (i in 0 until array.length()) array.optString(i).takeIf(String::isNotBlank)?.let(::add) } }.orEmpty(),
            minimumPerKm = optDouble("minimumPerKm", defaults.minimumPerKm), minimumTripValue = optDouble("minimumTripValue", defaults.minimumTripValue), homeAddress = optString("homeAddress", defaults.homeAddress), maxPickupDistanceKm = optInt("maxPickupDistanceKm", defaults.maxPickupDistanceKm).coerceIn(0, 10), maxTripDistanceKm = TripDistanceScale.nearest(optDouble("maxTripDistanceKm", defaults.maxTripDistanceKm)),
            startMinutes = optInt("startMinutes", defaults.startMinutes), endMinutes = optInt("endMinutes", defaults.endMinutes), weeklyAvailability = schedules, enabledDays = optJSONArray("enabledDays")?.let { days -> buildSet { for (index in 0 until days.length()) days.optInt(index).takeIf { it in 1..7 }?.let(::add) } } ?: (1..7).toSet(),
            refreshDelayMillis = optLong("refreshDelayMillis", defaults.refreshDelayMillis).coerceIn(50L, 5000L), searchWaitMillis = optLong("searchWaitMillis", defaults.searchWaitMillis).coerceIn(100L, 10000L),
            dryRun = false, dailyLimitEnabled = optBoolean("dailyLimitEnabled", defaults.dailyLimitEnabled), maxDailyReservations = optInt("maxDailyReservations", defaults.maxDailyReservations).coerceIn(1, 30),
            criteriaLocked = optBoolean("criteriaLocked", defaults.criteriaLocked), availabilityLocked = optBoolean("availabilityLocked", defaults.availabilityLocked), refreshLocked = optBoolean("refreshLocked", defaults.refreshLocked)
        )
    }

    private fun PresentedRide.toJson(): JSONObject = JSONObject().apply {
        put("date", date); put("time", time); put("category", category); put("payout", payout); put("distanceKm", distanceKm)
        put("origin", origin); put("destination", destination); put("recordedAt", recordedAt); put("id", id); put("accepted", accepted); put("refusalReason", refusalReason)
        put("pickupDistanceKm", pickupDistanceKm); put("categoryPassed", categoryPassed); put("tripValuePassed", tripValuePassed); put("perKmPassed", perKmPassed); put("tripDistancePassed", tripDistancePassed); put("availabilityPassed", availabilityPassed); put("pickupDistancePassed", pickupDistancePassed); put("simulated", simulated)
    }

    private fun JSONArray.toPresentedRides(): List<PresentedRide> = buildList {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            add(PresentedRide(
                date = item.optString("date"), time = item.optString("time"), category = item.optString("category"), payout = item.optDouble("payout"), distanceKm = item.optDouble("distanceKm"),
                origin = item.optString("origin"), destination = item.optString("destination"), recordedAt = item.optLong("recordedAt"), id = id,
                accepted = item.optBoolean("accepted"), refusalReason = item.optString("refusalReason", "critérios não avaliados"),
                pickupDistanceKm = item.optionalDouble("pickupDistanceKm"), categoryPassed = item.optBoolean("categoryPassed"), tripValuePassed = item.optBoolean("tripValuePassed"), perKmPassed = item.optBoolean("perKmPassed"), tripDistancePassed = item.optBoolean("tripDistancePassed", true), availabilityPassed = item.optBoolean("availabilityPassed"), pickupDistancePassed = item.optionalBoolean("pickupDistancePassed"), simulated = item.optBoolean("simulated")
            ))
        }
    }

    private fun JSONObject.optionalDouble(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key) else null
    private fun JSONObject.optionalBoolean(key: String): Boolean? = if (has(key) && !isNull(key)) optBoolean(key) else null
}
