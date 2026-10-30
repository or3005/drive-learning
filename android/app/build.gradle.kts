plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Each CI run gets a higher version code, so a new APK always installs over the old one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.or3005.drivelearning"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.or3005.drivelearning"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // A fixed key in the repo, so updates keep the same signature and keep progress data.
        getByName("debug") {
            storeFile = file("debug-signing.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

/** Copies the course JSON files from the repo's content folder into the app's assets. */
abstract class CopyContentTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val src = source.get().asFile
        val out = outputDir.get().asFile
        out.deleteRecursively()
        src.walkTopDown()
            .filter { it.isFile && it.extension == "json" && "review" !in it.relativeTo(src).path.split(File.separatorChar) }
            .forEach { file ->
                val target = File(out, "content/" + file.relativeTo(src).path)
                target.parentFile.mkdirs()
                file.copyTo(target, overwrite = true)
            }
    }
}

val copyContent = tasks.register<CopyContentTask>("copyContent") {
    source.set(rootProject.layout.projectDirectory.dir("../content"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyContent, CopyContentTask::outputDir)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
