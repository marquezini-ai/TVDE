package com.daniel.tvdeinsight.license

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Registo local, cifrado e exclusivo da aplicação Admin. Cada entrada é criada
 * no mesmo instante em que a respetiva chave de ativação é assinada.
 */
data class AdminLicenseRecord(
    val id: String,
    val fullName: String,
    val phone: String,
    val androidId: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val activationKey: String
) {
    val firstName: String
        get() = fullName.trim().substringBefore(' ').ifBlank { fullName }
}

@Singleton
class AdminLicenseRegistry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val preferences by lazy { createEncryptedPreferences() }
    private val lock = Any()
    private val _records = MutableStateFlow(loadRecords())
    val records: StateFlow<List<AdminLicenseRecord>> = _records.asStateFlow()

    fun add(
        fullName: String,
        phone: String,
        androidId: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        activationKey: String
    ): AdminLicenseRecord = synchronized(lock) {
        val record = AdminLicenseRecord(
            id = UUID.randomUUID().toString(),
            fullName = fullName.trim(),
            phone = phone.trim(),
            androidId = androidId.trim().lowercase(),
            createdAtMillis = createdAtMillis,
            expiresAtMillis = expiresAtMillis,
            activationKey = activationKey.trim()
        )
        val updated = (_records.value + record).sortedByDescending { it.createdAtMillis }
        preferences.edit().putString(RECORDS_KEY, updated.toJson()).apply()
        _records.value = updated
        record
    }

    fun renew(
        recordId: String,
        createdAtMillis: Long,
        expiresAtMillis: Long,
        activationKey: String
    ): AdminLicenseRecord = synchronized(lock) {
        val currentRecord = _records.value.firstOrNull { it.id == recordId }
            ?: throw IllegalArgumentException("Licença não encontrada")
        val renewedRecord = currentRecord.copy(
            createdAtMillis = createdAtMillis,
            expiresAtMillis = expiresAtMillis,
            activationKey = activationKey.trim()
        )
        val updated = _records.value.map { record ->
            if (record.id == recordId) renewedRecord else record
        }.sortedByDescending { it.createdAtMillis }
        preferences.edit().putString(RECORDS_KEY, updated.toJson()).apply()
        _records.value = updated
        renewedRecord
    }

    fun activeBackupJson(nowMillis: Long = System.currentTimeMillis()): String = synchronized(lock) {
        val activeRecords = _records.value.filter { it.expiresAtMillis > nowMillis }
        JSONObject().apply {
            put("format", "tvde-insight-active-licenses-v1")
            put("exportedAtMillis", nowMillis)
            put("licenses", activeRecords.toJsonArray())
        }.toString()
    }

    private fun loadRecords(): List<AdminLicenseRecord> = runCatching {
        val array = JSONArray(preferences.getString(RECORDS_KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val record = item.toRecordOrNull() ?: continue
                add(record)
            }
        }.sortedByDescending { it.createdAtMillis }
    }.getOrDefault(emptyList())

    private fun List<AdminLicenseRecord>.toJson(): String = toJsonArray().toString()

    private fun List<AdminLicenseRecord>.toJsonArray(): JSONArray = JSONArray().also { array ->
        forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("fullName", record.fullName)
                    put("phone", record.phone)
                    put("androidId", record.androidId)
                    put("createdAtMillis", record.createdAtMillis)
                    put("expiresAtMillis", record.expiresAtMillis)
                    put("activationKey", record.activationKey)
                }
            )
        }
    }

    private fun JSONObject.toRecordOrNull(): AdminLicenseRecord? = runCatching {
        AdminLicenseRecord(
            id = getString("id"),
            fullName = getString("fullName"),
            phone = getString("phone"),
            androidId = getString("androidId"),
            createdAtMillis = getLong("createdAtMillis"),
            expiresAtMillis = getLong("expiresAtMillis"),
            activationKey = getString("activationKey")
        )
    }.getOrNull()

    private fun createEncryptedPreferences() = EncryptedSharedPreferences.create(
        context,
        PREFERENCES_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private companion object {
        const val PREFERENCES_NAME = "admin_license_registry"
        const val RECORDS_KEY = "records"
    }
}
