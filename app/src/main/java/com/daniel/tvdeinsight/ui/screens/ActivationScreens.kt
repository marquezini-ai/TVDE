package com.daniel.tvdeinsight.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.daniel.tvdeinsight.BuildConfig
import com.daniel.tvdeinsight.R
import com.daniel.tvdeinsight.license.ActivationKeyCrypto
import com.daniel.tvdeinsight.license.AdminLicenseRecord
import com.daniel.tvdeinsight.license.LicenseState
import com.daniel.tvdeinsight.license.LicenseStatus
import com.daniel.tvdeinsight.license.LicenseType
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ClientActivationInline(licenseViewModel: LicenseViewModel = hiltViewModel()) {
    val licenseState by licenseViewModel.licenseState.collectAsState()
    val context = LocalContext.current
    var activationKey by rememberSaveable { mutableStateOf("") }
    val hasActivationCode = ActivationKeyCrypto.looksLikeActivationKey(activationKey)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Image(
                painter = painterResource(R.drawable.promo_subscription),
                contentDescription = "Promoção TVDE Insight: assinatura mensal por 2,99 euros",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.FillWidth
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = activationKey,
                    onValueChange = { activationKey = it.trim() },
                    modifier = Modifier.weight(1f),
                    label = { Text("Código de ativação") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Button(
                    onClick = {
                        if (hasActivationCode) licenseViewModel.activate(activationKey)
                        else context.openActivationWhatsApp(licenseState.androidId)
                    },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text(if (hasActivationCode) "Ativar" else "Solicitar") }
            }
            if (licenseState.status != LicenseStatus.NOT_ACTIVATED) {
                Text(
                    text = licenseState.statusLabel(),
                    color = licenseState.statusColor(),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun ClientLicenseRemaining(licenseState: LicenseState) {
    Text(
        text = licenseState.remainingLicenseText(),
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
        color = Color(0xFF2FAE70),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
fun AdminActivationCard() {
    val context = LocalContext.current
    val licensesViewModel: AdminLicensesViewModel = hiltViewModel()
    val records by licensesViewModel.records.collectAsState()
    val scope = rememberCoroutineScope()
    val backupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val exported = runCatching {
                val output = requireNotNull(context.contentResolver.openOutputStream(uri))
                output.bufferedWriter().use { writer -> writer.write(licensesViewModel.activeBackupJson()) }
            }.isSuccess
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (exported) "Backup das licenças ativas concluído." else "Não foi possível criar o backup.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    var androidId by rememberSaveable { mutableStateOf("") }
    var validityDays by rememberSaveable { mutableStateOf("") }
    var fullName by rememberSaveable { mutableStateOf("") }
    var phone by rememberSaveable { mutableStateOf("") }
    var generatedKey by rememberSaveable { mutableStateOf("") }
    var resultMessage by rememberSaveable { mutableStateOf("") }
    var showActiveLicenses by rememberSaveable { mutableStateOf(false) }
    val isKeyConfigured = BuildConfig.ADMIN_LICENSE_PRIVATE_KEY_BASE64.isNotBlank()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Ativação", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                if (!isKeyConfigured) {
                    Text(
                        "Chave privada do Administrador não configurada neste APK.",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp
                    )
                }
                OutlinedTextField(
                    value = androidId,
                    onValueChange = { androidId = it.trim().lowercase() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("ID Android do cliente") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = validityDays,
                    onValueChange = { value ->
                        validityDays = value.filter(Char::isDigit).take(2)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quantidade de dias") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nome completo") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Telefone") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = androidId.isNotBlank() && validityDays.isNotBlank() && fullName.isNotBlank() &&
                        phone.isNotBlank() && isKeyConfigured,
                    onClick = {
                        resultMessage = runCatching {
                            val days = requireNotNull(validityDays.toIntOrNull()) {
                                "Indique uma validade entre 1 e 99 dias."
                            }
                            require(days in 1..99) { "Indique uma validade entre 1 e 99 dias." }
                            val createdAt = System.currentTimeMillis()
                            val expiry = ActivationKeyCrypto.expirationFromDays(createdAt, days)
                            val key = ActivationKeyCrypto.generate(
                                androidId = androidId,
                                expiresAtMillis = expiry,
                                licenseType = LicenseType.CUSTOM,
                                privateKeyBase64 = BuildConfig.ADMIN_LICENSE_PRIVATE_KEY_BASE64
                            )
                            generatedKey = key
                            licensesViewModel.registerGeneratedLicense(
                                fullName = fullName,
                                phone = phone,
                                androidId = androidId,
                                createdAtMillis = createdAt,
                                expiresAtMillis = expiry,
                                activationKey = key
                            )
                            "Chave gerada. Expira em ${expiry.toDateTimeText()}."
                        }.getOrElse { error ->
                            generatedKey = ""
                            error.message ?: "Não foi possível gerar a chave."
                        }
                    }
                ) { Text("Gerar Chave") }

                if (resultMessage.isNotBlank()) {
                    Text(
                        resultMessage,
                        color = if (generatedKey.isBlank()) MaterialTheme.colorScheme.error else Color(0xFF2FAE70),
                        fontSize = 13.sp
                    )
                }
                if (generatedKey.isNotBlank()) {
                    OutlinedTextField(
                        value = generatedKey,
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Chave gerada") },
                        readOnly = true,
                        minLines = 3,
                        maxLines = 4
                    )
                    Button(
                        onClick = { context.copyToClipboard("Chave de ativação", generatedKey) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copiar") }
                }
            }
        }
        Button(
            onClick = { showActiveLicenses = true },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Licenças ativas") }
    }

    if (showActiveLicenses) {
        AdminActiveLicensesDialog(
            records = records,
            onDismiss = { showActiveLicenses = false },
            onCopyKey = { key -> context.copyToClipboard("Chave de ativação", key) },
            onCreateBackup = {
                backupLauncher.launch("TVDE-Insight-licencas-ativas-${System.currentTimeMillis()}.json")
            },
            onRenewLicense = { record, days ->
                val createdAt = System.currentTimeMillis()
                val expiresAt = ActivationKeyCrypto.expirationFromDays(createdAt, days)
                val key = ActivationKeyCrypto.generate(
                    androidId = record.androidId,
                    expiresAtMillis = expiresAt,
                    licenseType = LicenseType.CUSTOM,
                    privateKeyBase64 = BuildConfig.ADMIN_LICENSE_PRIVATE_KEY_BASE64
                )
                licensesViewModel.renewGeneratedLicense(
                    recordId = record.id,
                    createdAtMillis = createdAt,
                    expiresAtMillis = expiresAt,
                    activationKey = key
                )
            }
        )
    }
}

@Composable
private fun AdminActiveLicensesDialog(
    records: List<AdminLicenseRecord>,
    onDismiss: () -> Unit,
    onCopyKey: (String) -> Unit,
    onCreateBackup: () -> Unit,
    onRenewLicense: (AdminLicenseRecord, Int) -> AdminLicenseRecord
) {
    var selectedRecord by remember { mutableStateOf<AdminLicenseRecord?>(null) }
    var phoneQuery by rememberSaveable { mutableStateOf("") }
    val normalizedPhoneQuery = phoneQuery.filter(Char::isDigit)
    val allActiveRecords = records.filter { it.expiresAtMillis > System.currentTimeMillis() }
    val activeRecords = allActiveRecords.filter { record ->
        normalizedPhoneQuery.isBlank() || record.phone.filter(Char::isDigit).contains(normalizedPhoneQuery)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.86f),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            val record = selectedRecord
            if (record == null) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Licenças ativas", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Button(onClick = onDismiss) { Text("Fechar") }
                    }
                    OutlinedTextField(
                        value = phoneQuery,
                        onValueChange = { phoneQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Pesquisar por telefone") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true
                    )
                    Button(onClick = onCreateBackup, modifier = Modifier.fillMaxWidth()) {
                        Text("Fazer backup das licenças ativas")
                    }
                    if (allActiveRecords.isEmpty()) {
                        Text(
                            "Ainda não existem licenças ativas registadas neste Admin.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    } else if (activeRecords.isEmpty()) {
                        Text(
                            "Nenhum cliente encontrado para este telefone.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp
                        )
                    } else {
                        activeRecords.forEach { record ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(MaterialTheme.shapes.medium),
                                onClick = { selectedRecord = record },
                                shape = MaterialTheme.shapes.medium,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    Text(record.firstName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    Text(
                                        record.remainingTimeText(),
                                        color = Color(0xFF2FAE70),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                AdminLicenseDetails(
                    record = record,
                    onBack = { selectedRecord = null },
                    onCopyKey = { onCopyKey(record.activationKey) },
                    onRenewLicense = { days -> onRenewLicense(record, days) },
                    onRecordRenewed = { renewedRecord -> selectedRecord = renewedRecord }
                )
            }
        }
    }
}

@Composable
private fun AdminLicenseDetails(
    record: AdminLicenseRecord,
    onBack: () -> Unit,
    onCopyKey: () -> Unit,
    onRenewLicense: (Int) -> AdminLicenseRecord,
    onRecordRenewed: (AdminLicenseRecord) -> Unit
) {
    var showRenewalDialog by rememberSaveable(record.id, record.createdAtMillis) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onBack) { Text("Voltar") }
            Text("Licença", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        AdminLicenseDetailField("Nome completo", record.fullName)
        AdminLicenseDetailField("Telefone", record.phone)
        AdminLicenseDetailField("Android ID", record.androidId)
        AdminLicenseDetailField("Data de ativação", record.createdAtMillis.toDateTimeText())
        AdminLicenseDetailField("Expira em", record.expiresAtMillis.toDateTimeText())
        Button(onClick = { showRenewalDialog = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Renovar licença")
        }
        Text("Chave gerada", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        SelectionContainer {
            Text(record.activationKey, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onCopyKey, modifier = Modifier.fillMaxWidth()) { Text("Copiar chave") }
    }

    if (showRenewalDialog) {
        AdminLicenseRenewalDialog(
            onDismiss = { showRenewalDialog = false },
            onRenew = { days ->
                val renewal = runCatching { onRenewLicense(days) }
                renewal.onSuccess(onRecordRenewed)
                renewal.exceptionOrNull()?.message
                    ?: if (renewal.isSuccess) null else "Não foi possível renovar a licença."
            }
        )
    }
}

@Composable
private fun AdminLicenseRenewalDialog(
    onDismiss: () -> Unit,
    onRenew: (Int) -> String?
) {
    var daysText by rememberSaveable { mutableStateOf("") }
    var errorMessage by rememberSaveable { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Renovar licença", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                OutlinedTextField(
                    value = daysText,
                    onValueChange = { daysText = it.filter(Char::isDigit).take(2) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Quantidade de dias") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                if (errorMessage.isNotBlank()) {
                    Text(errorMessage, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Button(
                    onClick = {
                        val days = daysText.toIntOrNull()
                        errorMessage = when {
                            days == null || days !in 1..99 -> "Indique um período entre 1 e 99 dias."
                            else -> onRenew(days).also { error -> if (error == null) onDismiss() }.orEmpty()
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Gerar nova chave") }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
            }
        }
    }
}

@Composable
private fun AdminLicenseDetailField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}

private fun AdminLicenseRecord.remainingTimeText(nowMillis: Long = System.currentTimeMillis()): String {
    val remainingHours = ((expiresAtMillis - nowMillis).coerceAtLeast(0L)) / (60L * 60L * 1_000L)
    val days = remainingHours / 24L
    val hours = remainingHours % 24L
    return if (days > 0) "Restam $days dia${if (days == 1L) "" else "s"} e $hours h" else "Restam $hours h"
}

private fun LicenseState.statusLabel(): String = when (status) {
    LicenseStatus.VALID -> "Licença ativa"
    LicenseStatus.NOT_ACTIVATED -> "Ative a aplicação para utilizar as funcionalidades."
    LicenseStatus.EXPIRED -> "Licença expirada. Solicite uma renovação."
    LicenseStatus.DEVICE_MISMATCH -> "Esta chave pertence a outro dispositivo."
    LicenseStatus.CLOCK_ROLLBACK -> "A data/hora do dispositivo foi recuada. Corrija-a para renovar a licença."
    LicenseStatus.CRYPTO_NOT_CONFIGURED -> "A verificação de licença não está configurada."
    LicenseStatus.INVALID_KEY -> "Chave inválida. Verifique e tente novamente."
}

private fun LicenseState.statusColor(): Color = when (status) {
    LicenseStatus.VALID -> Color(0xFF2FAE70)
    else -> Color(0xFFE15252)
}

private fun LicenseState.remainingLicenseText(nowMillis: Long = System.currentTimeMillis()): String {
    val expiration = expiresAtMillis ?: return "Licença ativa"
    val remainingMillis = (expiration - nowMillis).coerceAtLeast(0L)
    val remainingHours = remainingMillis / (60L * 60L * 1_000L)
    val days = remainingHours / 24L
    val hours = remainingHours % 24L
    return if (days > 0) {
        "Licença ativa · restam $days dia${if (days == 1L) "" else "s"} e $hours h"
    } else {
        "Licença ativa · restam $hours h"
    }
}

private fun Long.toDateTimeText(): String = DateFormat.getDateTimeInstance(
    DateFormat.SHORT,
    DateFormat.SHORT,
    Locale("pt", "PT")
).format(Date(this))

private fun Context.copyToClipboard(label: String, value: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
}

private fun Context.openActivationWhatsApp(androidId: String) {
    val requestMessage = "Android ID: ${androidId.ifBlank { "Não disponível" }}"
    val chatUri = Uri.parse(
        "https://wa.me/$TVDE_INSIGHT_WHATSAPP_NUMBER?text=${Uri.encode(requestMessage)}"
    )
    val whatsappIntent = Intent(Intent.ACTION_VIEW, chatUri).apply {
        `package` = WHATSAPP_PACKAGE
    }
    try {
        startActivity(whatsappIntent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Intent.ACTION_VIEW, chatUri))
    }
}

private const val WHATSAPP_PACKAGE = "com.whatsapp"
private const val TVDE_INSIGHT_WHATSAPP_NUMBER = "351912521498"
