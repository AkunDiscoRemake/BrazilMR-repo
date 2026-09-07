import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "com.brazilmr"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.brazilmr"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-spatial-mr"
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
    testOptions { unitTests.isIncludeAndroidResources = true }
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
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test:core:1.6.1")
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
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        if (!model.exists()) {
            model.parentFile.mkdirs()
            val temp = File(model.parentFile, "hand_landmarker.task.part")
            try {
                val connection = URI("https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task").toURL().openConnection()
                connection.connectTimeout = 15000; connection.readTimeout = 60000
                connection.getInputStream().buffered().use { source -> temp.outputStream().buffered().use { destination -> source.copyTo(destination) } }
                check(digest(temp) == checksum) { "Checksum inválido para o modelo oficial MediaPipe" }
                Files.move(temp.toPath(), model.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } finally { temp.delete() }
        }
        check(digest(model) == checksum) { "Modelo modificado. Remova $model e execute prepareHandModel novamente." }
    }
}
tasks.named("preBuild") { dependsOn(prepareHandModel) }
