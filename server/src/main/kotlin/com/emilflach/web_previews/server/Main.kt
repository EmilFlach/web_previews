package com.emilflach.web_previews.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.ktor.server.plugins.compression.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.http.*
import io.ktor.server.request.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.net.NetworkInterface
import java.nio.file.*
import java.util.concurrent.atomic.AtomicBoolean
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.common.BitMatrix

fun main() {
    val port = 8080
    val localIp = getLocalIpAddress()
    val localUrl = "http://localhost:$port"
    val networkUrl = localIp?.let { "http://$it:$port" }

    println("\n" + "=".repeat(50))
    println("Server started!")
    println("Local:   $localUrl")
    networkUrl?.let {
        println("Network: $it")
        println("\nScan the QR code below for network access:")
        println(generateQrCodeAscii(it))
    }
    println("=".repeat(50) + "\n")

    embeddedServer(Netty, port = port) {
        install(WebSockets)
        install(Compression) {
            gzip {
                priority = 1.0
            }
            deflate {
                priority = 10.0
                minimumSize(1024)
            }
        }
        
        val buildInProgress = AtomicBoolean(false)
        val clients = java.util.Collections.synchronizedSet(mutableSetOf<DefaultWebSocketServerSession>())

        val distDir = File("composeApp/build/dist/wasmJs/developmentExecutable").let {
            if (it.exists()) it else File("../composeApp/build/dist/wasmJs/developmentExecutable")
        }.canonicalFile
        
        routing {
            webSocket("/dev-server") {
                clients.add(this)
                try {
                    if (buildInProgress.get()) {
                        send("rebuilding")
                    }
                    for (frame in incoming) {
                        // Keep alive
                    }
                } finally {
                    clients.remove(this)
                }
            }

            staticFiles("/", distDir) {
                enableAutoHeadResponse()
                default("index.html")
                // Add headers to encourage browser to use the background-fetched files
                cacheControl {
                    // Use a short max-age for the transition
                    listOf(CacheControl.MaxAge(maxAgeSeconds = 10))
                }
            }
        }

    launch(Dispatchers.IO) {
        val changeChannel = Channel<Unit>(Channel.CONFLATED)
        
        launch {
            for (change in changeChannel) {
                delay(1000) // Debounce
                // Consume all pending signals that might have accumulated during delay
                while (changeChannel.tryReceive().isSuccess) { /* keep skipping */ }
                
                if (buildInProgress.compareAndSet(false, true)) {
                    print("\rFile change detected, rebuilding...")
                    System.out.flush()
                    clients.forEach { 
                        launch {
                            try { it.send("rebuilding") } catch (e: Exception) {}
                        }
                    }
                    
                    val gradlewPath = if (File("./gradlew").exists()) "./gradlew" else "../gradlew"
                    val process = ProcessBuilder(gradlewPath, ":composeApp:wasmJsBrowserDevelopmentExecutableDistribution", "--console=plain")
                        .redirectErrorStream(true)
                        .start()
                    
                    val output = process.inputStream.bufferedReader().use { it.readText() }
                    val exitCode = process.waitFor()
                    buildInProgress.set(false)
                    
                    if (exitCode == 0) {
                        print("\rRebuild successful, notifying clients.          ")
                        System.out.flush()
                        val filesList = distDir.listFiles()?.filter { 
                            it.name.endsWith(".js") || it.name.endsWith(".wasm") || it.name == "app.html"
                        }?.joinToString(",") { it.name } ?: ""
                        
                        clients.forEach { 
                            launch {
                                try { it.send("reload:$filesList") } catch (e: Exception) {}
                            }
                        }
                    } else {
                        println("\rRebuild failed with exit code $exitCode          ")
                        println("\n--- Gradle Output ---")
                        println(output)
                        println("----------------------\n")
                        clients.forEach { 
                            launch {
                                try { it.send("error") } catch (e: Exception) {}
                            }
                        }
                    }
                }
            }
        }

        watchFiles {
            changeChannel.send(Unit)
        }
    }
    }.start(wait = true)
}

fun watchFiles(onChange: suspend () -> Unit) {
    val watchService = FileSystems.getDefault().newWatchService()
    val root = Paths.get(".").let {
        if (Files.exists(it.resolve("composeApp"))) it else Paths.get("..")
    }.toAbsolutePath().normalize()
    
    fun registerRecursive(path: Path) {
        val file = path.toFile()
        if (file.isDirectory) {
            val name = file.name
            if (name == "build" || name.startsWith(".") || name == "node_modules" || name == "kotlin-js-store") return
            
            path.register(
                watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_DELETE,
                StandardWatchEventKinds.ENTRY_MODIFY
            )
            file.listFiles()?.forEach { registerRecursive(it.toPath()) }
        }
    }

    registerRecursive(root)

    runBlocking {
        while (true) {
            val key = watchService.take()
            var changed = false
            for (event in key.pollEvents()) {
                val context = event.context() as? Path ?: continue
                val watchable = key.watchable() as Path
                val resolvedPath = watchable.resolve(context).toAbsolutePath().normalize()
                
                val pathString = resolvedPath.toString()
                if (pathString.contains("composeApp/src") || pathString.endsWith(".gradle.kts")) {
                    changed = true
                }
                
                if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE) {
                    registerRecursive(resolvedPath)
                }
            }
            if (changed) {
                onChange()
            }
            if (!key.reset()) break
        }
    }
}

fun getLocalIpAddress(): String? {
    return NetworkInterface.getNetworkInterfaces().asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .filter { !it.isLoopbackAddress && it.hostAddress.indexOf(':') < 0 }
        .map { it.hostAddress }
        .firstOrNull()
}

fun generateQrCodeAscii(text: String): String {
    val qrCodeWriter = QRCodeWriter()
    val bitMatrix = qrCodeWriter.encode(text, BarcodeFormat.QR_CODE, 0, 0)
    val width = bitMatrix.width
    val height = bitMatrix.height
    val sb = StringBuilder()
    for (y in 0 until height) {
        for (x in 0 until width) {
            sb.append(if (bitMatrix.get(x, y)) "██" else "  ")
        }
        sb.append("\n")
    }
    return sb.toString()
}
