import java.security.MessageDigest
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Секреты: android-app/secrets.properties (вне git), шаблон — secrets.properties.example.
// Нет файла или ключа -> пустая строка, сборка не падает.
// ---------------------------------------------------------------------------
val secrets = Properties().apply {
    val file = rootProject.file("secrets.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun secret(name: String): String = secrets.getProperty(name, "").trim()

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val agentAppId = secret("AGENT_APP_ID")

// PIN экрана диагностики: в APK попадает только итерированный SHA-256 с солью, не сам PIN.
// Алгоритм должен совпадать с kz.astanahub.robot.security.DiagPin.hash.
val diagPinSalt = secret("DIAG_PIN_SALT").ifEmpty { "kz.astanahub.robot/diag-pin/v1" }

fun diagPinHash(pin: String, salt: String): String {
    if (pin.isEmpty()) return ""
    if (!pin.matches(Regex("\\d{4,12}"))) {
        throw GradleException("DIAG_PIN в secrets.properties: только цифры, 4–12 знаков")
    }
    val md = MessageDigest.getInstance("SHA-256")
    var digest = md.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
    repeat(9_999) { digest = md.digest(digest) }
    return digest.joinToString("") { "%02x".format(it) }
}

val diagPinHashValue = diagPinHash(secret("DIAG_PIN"), diagPinSalt)

android {
    namespace = "kz.astanahub.robot"
    compileSdk = 36

    defaultConfig {
        applicationId = "kz.astanahub.robot"
        minSdk = 26
        // Уточнить по версии Android на роботе (roadmap, шаг 2.1).
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "AGENT_APP_ID", agentAppId.asBuildConfigString())
        buildConfigField("String", "ROBOT_TOKEN", secret("ROBOT_TOKEN").asBuildConfigString())
        buildConfigField("String", "BACKEND_URL", secret("BACKEND_URL").asBuildConfigString())
        // Хосты, на которые можно переключить сервер с экрана диагностики (через запятую), плюс хост BACKEND_URL.
        buildConfigField("String", "BACKEND_ALLOWED_HOSTS", secret("BACKEND_ALLOWED_HOSTS").asBuildConfigString())
        // PIN экрана диагностики (хеш). Пусто: robot/robotos — диагностика недоступна, emulator — без PIN.
        buildConfigField("String", "DIAG_PIN_HASH", diagPinHashValue.asBuildConfigString())
        buildConfigField("String", "DIAG_PIN_SALT", diagPinSalt.asBuildConfigString())
        // Голос (меняется и без пересборки — экран диагностики): builtin | server.
        // Распознавание: по умолчанию сервер (все языки). Синтез: все языки — сервер (ElevenLabs).
        buildConfigField("String", "VOICE_ASR", secret("VOICE_ASR").ifEmpty { "server" }.asBuildConfigString())
        buildConfigField("String", "VOICE_TTS_RU", secret("VOICE_TTS_RU").ifEmpty { "server" }.asBuildConfigString())
        buildConfigField("String", "VOICE_TTS_KK", secret("VOICE_TTS_KK").ifEmpty { "server" }.asBuildConfigString())
        buildConfigField("String", "VOICE_TTS_EN", secret("VOICE_TTS_EN").ifEmpty { "server" }.asBuildConfigString())
        // Сбой /v1/tts -> встроенный голос робота (true) или текст без звука + фраза из кеша ElevenLabs (false, по умолчанию).
        buildConfigField("boolean", "VOICE_BUILTIN_FALLBACK", (secret("VOICE_BUILTIN_FALLBACK").lowercase() == "true").toString())
        // Конец фразы посетителя после стольких мс тишины (VAD приложения), по умолчанию 500.
        buildConfigField("int", "VAD_END_SILENCE_MS", secret("VAD_END_SILENCE_MS").toIntOrNull()?.toString() ?: "500")
        // Потоковый /v1/tts (звук с первых байт), по умолчанию true.
        buildConfigField("boolean", "TTS_STREAMING", (secret("TTS_STREAMING").lowercase() != "false").toString())
        // Заставка и сброс сессии после N секунд бездействия (шаг 7.4); 0 — выключено.
        // Разговор: agent — POST /v1/chat (агент на сервере), legacy — локальные правила + /v1/ask,
        // live — живой голосовой режим WS /v1/live (сбой → agent). Меняется в диагностике.
        buildConfigField("String", "CHAT_MODE", secret("CHAT_MODE").ifEmpty { "agent" }.asBuildConfigString())
        buildConfigField("int", "SCREENSAVER_SECONDS", secret("SCREENSAVER_SECONDS").toIntOrNull()?.toString() ?: "60")
    }

    flavorDimensions += "target"
    productFlavors {
        // Обычный Android-эмулятор: железо робота подменяется заглушками (шаг 1.5).
        create("emulator") {
            dimension = "target"
            buildConfigField("boolean", "IS_ROBOT", "false")
            buildConfigField("String", "PLATFORM", "\"emulator\"")
        }
        // Реальный робот GreetingBot Nova (AgentOS + RobotAPI).
        create("robot") {
            dimension = "target"
            buildConfigField("boolean", "IS_ROBOT", "true")
            buildConfigField("String", "PLATFORM", "\"agentos\"")
        }
        // Робот на RobotOS 10.4 (без AgentOS): RobotApi + SkillApi из robotservice.jar (OrionStar RobotSample).
        create("robotos") {
            dimension = "target"
            buildConfigField("boolean", "IS_ROBOT", "true")
            buildConfigField("String", "PLATFORM", "\"robotos\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // android.util.Log и прочие заглушки android.jar в JVM-тестах возвращают значения по умолчанию.
        unitTests.isReturnDefaultValues = true
    }

    sourceSets {
        // Общий код для роботов (RobotApi, SurfaceShareApi): пакеты одинаковы в AgentOS core и robotservice.jar.
        getByName("robot") { java.srcDir("src/robotShared/java") }
        getByName("robotos") { java.srcDir("src/robotShared/java") }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        // UI-слой нового интерфейса (docs/ui-redesign/DESIGN.md, «Стек»): Compose поверх прежних XML-экранов.
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// ---------------------------------------------------------------------------
// assets/actionRegistry.json генерируется при сборке из AGENT_APP_ID
// (сам файл с appId в git не хранится, см. docs/vendor/AgentOS_SDK_Doc_v0.4.12_en.md, 1.2.3).
// actionList пока пуст — Actions появятся в следующих шагах.
// ---------------------------------------------------------------------------
abstract class GenerateActionRegistryTask : DefaultTask() {
    @get:Input
    abstract val appId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val id = appId.get().replace("\\", "\\\\").replace("\"", "\\\"")
        if (id.isEmpty()) {
            logger.warn("AGENT_APP_ID пуст (android-app/secrets.properties): actionRegistry.json будет с пустым appId")
        }
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("actionRegistry.json").writeText(
            """
            |{
            |  "appId": "$id",
            |  "platform": "apk",
            |  "actionList": []
            |}
            |""".trimMargin()
        )
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<GenerateActionRegistryTask>(
            "generate${variant.name.replaceFirstChar { it.uppercase() }}ActionRegistry"
        ) {
            appId.set(agentAppId)
        }
        // AGP сам назначает outputDir для каждого варианта.
        variant.sources.assets?.addGeneratedSourceDirectory(task, GenerateActionRegistryTask::outputDir)
    }
}

dependencies {
    // AgentOS SDK (включает RobotService.jar с v0.4.4) — только flavor robot (AgentOS V12.6).
    "robotImplementation"(libs.orionstar.agent.sdk)
    // RobotOS 10.4: jar из https://github.com/OrionStarGIT/RobotSample (RobotOS-Version 5.2.2026051505), ему нужен gson.
    "robotosImplementation"(files("libs/robotos/robotservice.jar"))
    "robotosImplementation"(libs.gson)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.timber)

    // Compose: экран лица (M1), дальше экран 2 (M3). Версии — из BOM.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Копия APK для раздачи роботу через http-сервер: ./gradlew publishTestApks
tasks.register<Copy>("publishTestApks") {
    dependsOn("assembleEmulatorDebug", "assembleRobotosDebug")
    val outDir = File(System.getProperty("user.home"), "astana-hub-apk")
    from(layout.buildDirectory.file("outputs/apk/emulator/debug/app-emulator-debug.apk")) {
        rename { "astana-hub-test.apk" }
    }
    from(layout.buildDirectory.file("outputs/apk/robotos/debug/app-robotos-debug.apk")) {
        rename { "astana-hub-robotos.apk" }
    }
    into(outDir)
}
