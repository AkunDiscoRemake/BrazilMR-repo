plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.brazilmr"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.brazilmr"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-foundation"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    androidResources { noCompress += "task" }
    packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/DEPENDENCIES") }
    sourceSets["main"].assets.srcDir(rootProject.file("sdk"))
    lint { abortOnError = true }
}
dependencies {
    implementation(project(":core"))
    implementation(project(":lua"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    implementation("com.google.ar:core:1.46.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Versioned public model, verified before inclusion. Never commit model binaries or generated APKs.
val prepareHandModel by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/models/hand_landmarker.task").asFile
    val checksum = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
    inputs.property("modelSha256", checksum)
    outputs.file(model)
    onlyIf { providers.gradleProperty("skipHandModel").orNull != "true" }
    doLast {
        fun digest(file: java.io.File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (!model.exists()) {
            model.parentFile.mkdirs()
            val temp = java.io.File(model.parentFile, "hand_landmarker.task.part")
            try {
                val connection = java.net.URI("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task").toURL().openConnection()
                connection.connectTimeout = 15000; connection.readTimeout = 60000
                connection.getInputStream().buffered().use { source -> temp.outputStream().buffered().use { destination -> source.copyTo(destination) } }
                check(digest(temp) == checksum) { "Checksum inválido para o modelo oficial MediaPipe" }
                java.nio.file.Files.move(temp.toPath(), model.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        check(digest(model) == checksum) { "Modelo modificado. Remova $model e execute prepareHandModel novamente." }
    }
}
tasks.named("preBuild") { dependsOn(prepareHandModel) }
