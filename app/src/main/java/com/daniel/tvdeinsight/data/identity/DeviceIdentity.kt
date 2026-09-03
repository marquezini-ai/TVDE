package com.daniel.tvdeinsight.data.identity

import android.provider.Settings
import com.daniel.tvdeinsight.logging.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Identifica a origem de uma linha no Sheets quando existem vários clientes. */
@Singleton
class DeviceIdentity @Inject constructor(
    @ApplicationContext context: Context
) {
    val sourceId: String = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )?.trim().takeUnless { it.isNullOrBlank() } ?: UUID.randomUUID().toString().also {
        AppLogger.warn("ANDROID_ID indisponível; foi criado um identificador local de sincronização")
    }
}

