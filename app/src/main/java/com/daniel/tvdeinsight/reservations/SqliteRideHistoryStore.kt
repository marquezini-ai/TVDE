package com.daniel.tvdeinsight.reservations

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Histórico persistente em SQLite, com migração transparente do JSON legado. */
object RideHistoryStore {
    private const val DATABASE_NAME = "historico_viagens.sqlite"
    private const val TABLE = "viagens"
    private const val MAX_ENTRIES = 500
    private const val LEGACY_PREFS = "historico_viagens"
    private const val LEGACY_MIGRATED = "sqlite_migrado"
    private val lock = Any()
    private var helper: HistoryDatabase? = null

    fun record(
        context: Context,
        candidate: RideCandidate,
        evaluation: RideEvaluation,
        pickupDistanceKm: Double? = null,
        @Suppress("UNUSED_PARAMETER") simulated: Boolean = false
    ): Boolean {
        val route = candidate.origin.trim() to candidate.destination.trim()
        val entry = PresentedRide(
            date = candidate.tripDate.ifBlank { TripDateResolver.resolve(candidate.sourceText) },
            time = candidate.timeText.ifBlank { formatTime(candidate.startMinutes) },
            category = candidate.displayedCategory.ifBlank { candidate.category },
            payout = candidate.payout,
            distanceKm = candidate.distanceKm,
            origin = route.first,
            destination = route.second,
            recordedAt = System.currentTimeMillis(),
            id = candidate.historyId,
            accepted = evaluation.accepted,
            refusalReason = evaluation.reasons.joinToString("; ").ifBlank { "aceita todos os critérios" },
            pickupDistanceKm = pickupDistanceKm,
            categoryPassed = evaluation.categoryPassed,
            tripValuePassed = evaluation.tripValuePassed,
            perKmPassed = evaluation.perKmPassed,
            tripDistancePassed = evaluation.tripDistancePassed,
            availabilityPassed = evaluation.availabilityPassed,
            pickupDistancePassed = evaluation.pickupDistancePassed,
            simulated = false
        )
        val inserted = synchronized(lock) {
            val db = database(context)
            val added = db.insertWithOnConflict(TABLE, null, values(entry), SQLiteDatabase.CONFLICT_IGNORE) != -1L
            if (added) {
                trim(db)
            } else {
                val previous = find(db, entry.id)
                if (previous != null) {
                    val merged = previous.copy(
                        origin = previous.origin.ifBlank { entry.origin },
                        destination = previous.destination.ifBlank { entry.destination },
                        accepted = entry.accepted,
                        refusalReason = entry.refusalReason,
                        pickupDistanceKm = entry.pickupDistanceKm ?: previous.pickupDistanceKm,
                        categoryPassed = entry.categoryPassed,
                        tripValuePassed = entry.tripValuePassed,
                        perKmPassed = entry.perKmPassed,
                        tripDistancePassed = entry.tripDistancePassed,
                        availabilityPassed = entry.availabilityPassed,
                        pickupDistancePassed = entry.pickupDistancePassed ?: previous.pickupDistancePassed,
                        simulated = false
                    )
                    if (merged != previous) db.update(TABLE, values(merged), "id = ?", arrayOf(entry.id))
                }
            }
            added
        }
        if (inserted) {
            DiagnosticLogger.log(
                "Histórico SQLite: viagem apresentada: id=${entry.id}, data=${entry.date}, " +
                    "hora=${entry.time}, categoria=${entry.category}, origem=${entry.origin}, destino=${entry.destination}"
            )
        }
        return inserted
    }

    fun list(context: Context): List<PresentedRide> = synchronized(lock) {
        val db = database(context)
        db.query(TABLE, null, null, null, null, null, "recorded_at DESC").use { cursor ->
            buildList { while (cursor.moveToNext()) add(fromCursor(cursor)) }
        }
    }

    fun updateOutcome(context: Context, historyId: String, accepted: Boolean, reason: String) {
        synchronized(lock) {
            val values = ContentValues().apply {
                put("accepted", if (accepted) 1 else 0)
                put("reason", reason)
                put("simulated", 0)
            }
            database(context).update(TABLE, values, "id = ?", arrayOf(historyId))
        }
        DiagnosticLogger.log("Histórico SQLite atualizado: id=$historyId, aceite=$accepted, motivo=$reason")
    }

    fun clear(context: Context) {
        synchronized(lock) { database(context).delete(TABLE, null, null) }
        DiagnosticLogger.log("Histórico de viagens SQLite limpo pelo utilizador")
    }

    /** Substitui o histórico local pelo conteúdo de um backup validado. */
    fun replaceAll(context: Context, entries: List<PresentedRide>) {
        synchronized(lock) {
            val db = database(context)
            db.beginTransaction()
            try {
                db.delete(TABLE, null, null)
                entries.sortedByDescending { it.recordedAt }.take(MAX_ENTRIES).forEach { entry ->
                    db.insertWithOnConflict(TABLE, null, values(entry.copy(simulated = false)), SQLiteDatabase.CONFLICT_REPLACE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        DiagnosticLogger.log("Histórico de Reservas restaurado do backup: entradas=${entries.size}")
    }

    private fun database(context: Context): SQLiteDatabase {
        val current = synchronized(lock) {
            helper ?: HistoryDatabase(context.applicationContext).also { helper = it }
        }
        val db = current.writableDatabase
        migrateLegacy(context.applicationContext, db)
        return db
    }

    private fun migrateLegacy(context: Context, db: SQLiteDatabase) {
        val prefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_MIGRATED, false)) return
        runCatching {
            LegacyRideHistoryStore.list(context).forEach { entry ->
                db.insertWithOnConflict(TABLE, null, values(entry.copy(simulated = false, tripDistancePassed = true)), SQLiteDatabase.CONFLICT_IGNORE)
            }
            trim(db)
            prefs.edit().putBoolean(LEGACY_MIGRATED, true).apply()
            DiagnosticLogger.log("Migração do histórico legado para SQLite concluída")
        }.onFailure { DiagnosticLogger.log("Falha na migração do histórico legado", it) }
    }

    private fun find(db: SQLiteDatabase, id: String): PresentedRide? =
        db.query(TABLE, null, "id = ?", arrayOf(id), null, null, null).use {
            if (it.moveToFirst()) fromCursor(it) else null
        }

    private fun trim(db: SQLiteDatabase) {
        db.execSQL("DELETE FROM $TABLE WHERE id NOT IN (SELECT id FROM $TABLE ORDER BY recorded_at DESC LIMIT $MAX_ENTRIES)")
    }

    private fun values(entry: PresentedRide) = ContentValues().apply {
        put("id", entry.id)
        put("date", entry.date)
        put("time", entry.time)
        put("category", entry.category)
        put("payout", entry.payout)
        put("distance_km", entry.distanceKm)
        put("origin", entry.origin)
        put("destination", entry.destination)
        put("recorded_at", entry.recordedAt)
        put("accepted", if (entry.accepted) 1 else 0)
        put("reason", entry.refusalReason)
        if (entry.pickupDistanceKm == null) putNull("pickup_distance_km") else put("pickup_distance_km", entry.pickupDistanceKm)
        put("category_passed", if (entry.categoryPassed) 1 else 0)
        put("trip_passed", if (entry.tripValuePassed) 1 else 0)
        put("per_km_passed", if (entry.perKmPassed) 1 else 0)
        put("trip_distance_passed", if (entry.tripDistancePassed) 1 else 0)
        put("availability_passed", if (entry.availabilityPassed) 1 else 0)
        if (entry.pickupDistancePassed == null) putNull("pickup_passed") else put("pickup_passed", if (entry.pickupDistancePassed) 1 else 0)
        put("simulated", if (entry.simulated) 1 else 0)
    }

    private fun fromCursor(cursor: Cursor): PresentedRide = PresentedRide(
        date = cursor.text("date"),
        time = cursor.text("time"),
        category = cursor.text("category"),
        payout = cursor.double("payout"),
        distanceKm = cursor.double("distance_km"),
        origin = cursor.text("origin"),
        destination = cursor.text("destination"),
        recordedAt = cursor.long("recorded_at"),
        id = cursor.text("id"),
        accepted = cursor.int("accepted") != 0,
        refusalReason = cursor.text("reason"),
        pickupDistanceKm = cursor.optionalDouble("pickup_distance_km"),
        categoryPassed = cursor.int("category_passed") != 0,
        tripValuePassed = cursor.int("trip_passed") != 0,
        perKmPassed = cursor.int("per_km_passed") != 0,
        tripDistancePassed = cursor.int("trip_distance_passed") != 0,
        availabilityPassed = cursor.int("availability_passed") != 0,
        pickupDistancePassed = cursor.optionalBoolean("pickup_passed"),
        simulated = cursor.int("simulated") != 0
    )

    private fun formatTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)
}

private class HistoryDatabase(context: Context) : SQLiteOpenHelper(context, "historico_viagens.sqlite", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE viagens (
                id TEXT PRIMARY KEY NOT NULL,
                date TEXT NOT NULL,
                time TEXT NOT NULL,
                category TEXT NOT NULL,
                payout REAL NOT NULL,
                distance_km REAL NOT NULL,
                origin TEXT NOT NULL,
                destination TEXT NOT NULL,
                recorded_at INTEGER NOT NULL,
                accepted INTEGER NOT NULL,
                reason TEXT NOT NULL,
                pickup_distance_km REAL,
                category_passed INTEGER NOT NULL,
                trip_passed INTEGER NOT NULL,
                per_km_passed INTEGER NOT NULL,
                trip_distance_passed INTEGER NOT NULL,
                availability_passed INTEGER NOT NULL,
                pickup_passed INTEGER,
                simulated INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) db.execSQL("UPDATE viagens SET simulated = 0")
        if (oldVersion < 3) db.execSQL("ALTER TABLE viagens ADD COLUMN trip_distance_passed INTEGER NOT NULL DEFAULT 1")
    }
}

private fun Cursor.text(column: String): String = getString(getColumnIndexOrThrow(column)).orEmpty()
private fun Cursor.double(column: String): Double = getDouble(getColumnIndexOrThrow(column))
private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))
private fun Cursor.optionalDouble(column: String): Double? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getDouble(index)
}
private fun Cursor.optionalBoolean(column: String): Boolean? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getInt(index) != 0
}

