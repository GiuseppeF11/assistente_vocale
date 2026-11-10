plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.assistente.vocale"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.assistente.vocale"
        minSdk = 29
        targetSdk = 35
        // numero della build su GitHub (1 per le build locali)
        versionCode = providers.environmentVariable("VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = "1.$versionCode"
        ndk {
            // telefoni reali + emulatore
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    // Firma di produzione: la chiave privata NON è nel repository, arriva dai segreti di GitHub.
    // In locale (Android Studio) senza variabili d'ambiente si usa la normale firma di debug.
    val keystorePath = providers.environmentVariable("KEYSTORE_FILE").orNull
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull ?: "assistente"
                keyPassword = providers.environmentVariable("KEYSTORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (keystorePath != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    // i controlli lint non devono bloccare la build di produzione
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // riconoscimento vocale offline; nessun'altra libreria (niente AndroidX: l'app resta leggera)
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")

    testImplementation("junit:junit:4.13.2")
}

// ---------------------------------------------------------------------------------------------
// Modello vocale italiano (~50 MB): scaricato una sola volta in src/main/assets/model-it
// ---------------------------------------------------------------------------------------------
val voskModel = "vosk-model-small-it-0.22"
val voskModelDir = layout.projectDirectory.dir("src/main/assets/model-it")

val downloadVoskModel by tasks.registering {
    description = "Scarica il modello Vosk italiano negli assets"
    val zip = layout.buildDirectory.file("tmp/$voskModel.zip")
    val marker = voskModelDir.file("am/final.mdl").asFile
    onlyIf { !marker.exists() }
    doLast {
        val zipFile = zip.get().asFile
        zipFile.parentFile.mkdirs()
        ant.invokeMethod(
            "get",
            mapOf("src" to "https://alphacephei.com/vosk/models/$voskModel.zip", "dest" to zipFile, "verbose" to true),
        )
        project.copy {
            from(project.zipTree(zipFile)) {
                // toglie la cartella radice dello zip
                eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray()) }
                includeEmptyDirs = false
            }
            into(voskModelDir)
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadVoskModel) }
