plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.compression)
    implementation(libs.zxing.core)
    implementation(libs.zxing.javase)
}

application {
    mainClass.set("com.emilflach.web_previews.dev.MainKt")
    applicationDefaultJvmArgs = listOf(
        "-Dlogback.configurationFile=logback.xml",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Dio.netty.noUnsafe=true"
    )
}

tasks.withType<JavaExec> {
    standardInput = System.`in`
    // Use --console=plain when running to avoid Gradle progress bar noise
    if (project.hasProperty("plain")) {
        logging.captureStandardOutput(LogLevel.INFO)
    }
}

tasks.named<JavaExec>("run") {
    // Attempt to reduce noise when running the server
    logging.captureStandardOutput(LogLevel.INFO)
}
