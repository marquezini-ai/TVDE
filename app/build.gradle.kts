import java.util.Properties
import java.io.File

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val licenseProperties = Properties().apply {
    val localLicenseFile = rootProject.file(".gradle/license.properties")
    if (localLicenseFile.isFile) {
        localLicenseFile.inputStream().use(::load)
    }
}
val embeddedPublicKeyBase64 = rootProject.file("license-public-key.txt")
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    .orEmpty()
val licensePublicKeyBase64 = licenseProperties
    .getProperty("licensePublicKeyBase64", "")
    .ifBlank { embeddedPublicKeyBase64 }
val adminPrivateKeyBase64 = licenseProperties.getProperty("adminPrivateKeyBase64", "")
val releaseSigningPropertiesFile = rootProject.file("keystore.properties")
val releaseSigningProperties = Properties().apply {
    if (releaseSigningPropertiesFile.isFile) {
        releaseSigningPropertiesFile.inputStream().use(::load)
    }
}
val releaseStorePath = releaseSigningProperties.getProperty("storeFile", "").trim()
val hasReleaseSigning = releaseSigningPropertiesFile.isFile &&
    releaseStorePath.isNotBlank() &&
    releaseSigningProperties.getProperty("storePassword", "").isNotBlank() &&
    releaseSigningProperties.getProperty("keyAlias", "").isNotBlank() &&
    releaseSigningProperties.getProperty("keyPassword", "").isNotBlank() &&
    rootProject.file(releaseStorePath).isFile
fun String.asBuildConfigString(): String = "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""
val sheetsSpreadsheetId = localProperties.getProperty("googleSheetsSpreadsheetId", "").trim()
val serviceAccountPath = localProperties.getProperty("googleServiceAccountJsonPath", "").trim()
val serviceAccountFile = serviceAccountPath.takeIf { it.isNotBlank() }?.let { File(it) }
if (serviceAccountFile?.isFile == true) {
    android.sourceSets.getByName("main").assets.srcDir(serviceAccountFile.parentFile)
}

android {
    namespace = "com.daniel.tvdeinsight"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.daniel.tvdeinsight"
        minSdk = 29
        targetSdk = 35
        versionCode = 142
        versionName = "0.5.39-unified"

        buildConfigField("String", "GOOGLE_SHEETS_SPREADSHEET_ID", sheetsSpreadsheetId.asBuildConfigString())
        buildConfigField(
            "String",
            "GOOGLE_SERVICE_ACCOUNT_ASSET",
            (serviceAccountFile?.name.orEmpty()).asBuildConfigString()
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures { compose = true; buildConfig = true }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath)
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions += "applicationRole"
    productFlavors {
        create("client") {
            dimension = "applicationRole"
            buildConfigField("boolean", "IS_ADMIN_APP", "false")
            buildConfigField("String", "LICENSE_PUBLIC_KEY_BASE64", licensePublicKeyBase64.asBuildConfigString())
            buildConfigField("String", "ADMIN_LICENSE_PRIVATE_KEY_BASE64", "\"\"")
            resValue("string", "app_name", "TVDE Insight")
        }
        create("admin") {
            dimension = "applicationRole"
            applicationIdSuffix = ".admin"
            buildConfigField("boolean", "IS_ADMIN_APP", "true")
            buildConfigField("String", "LICENSE_PUBLIC_KEY_BASE64", licensePublicKeyBase64.asBuildConfigString())
            buildConfigField("String", "ADMIN_LICENSE_PRIVATE_KEY_BASE64", adminPrivateKeyBase64.asBuildConfigString())
            resValue("string", "app_name", "TVDE Insight Admin")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.runtime.saveable)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // ML Kit para leitura de textos na tela (OCR)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-effects:1.4.1")
    // Monta os segmentos temporários numa única gravação ao parar.
    implementation("androidx.media3:media3-transformer:1.9.0")
    implementation("androidx.media3:media3-effect:1.9.0")
    implementation("androidx.media3:media3-common:1.9.0")

    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
