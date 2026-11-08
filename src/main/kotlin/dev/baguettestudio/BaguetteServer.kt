package dev.baguettestudio

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/** Owns the `baguette serve` sidecar. Reuses an already-running server if one exists. */
@Service(Service.Level.APP)
class BaguetteServer : Disposable {
    private var process: Process? = null

    val port = 8421
    val baseUrl get() = "http://localhost:$port"
    val simulatorsUrl get() = "$baseUrl/simulators"

    fun isUp(): Boolean = try {
        (URI(simulatorsUrl).toURL().openConnection() as HttpURLConnection).run {
            connectTimeout = 400
            readTimeout = 800
            responseCode in 200..399
        }
    } catch (_: Exception) {
        false
    }

    /** Bundled copy (extracted once per version), else a user-installed one. */
    fun findBinary(): File? {
        extractBundled()?.let { return it }
        val candidates = listOf("/opt/homebrew/bin/baguette", "/usr/local/bin/baguette")
        candidates.map(::File).firstOrNull { it.canExecute() }?.let { return it }
        // Fall back to the user's login shell PATH (Studio launched from Dock has a minimal PATH).
        return try {
            val p = ProcessBuilder("/bin/zsh", "-lc", "command -v baguette").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim().lines().lastOrNull().orEmpty()
            p.waitFor()
            File(out).takeIf { out.isNotEmpty() && it.canExecute() }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractBundled(): File? {
        val res = "/baguette/baguette-macos-arm64.tar.gz"
        val version = javaClass.getResourceAsStream("/baguette/VERSION")?.bufferedReader()?.readText()?.trim() ?: return null
        if (javaClass.getResource(res) == null) return null
        val dir = File(System.getProperty("user.home"), "Library/Application Support/BaguetteStudio/$version")
        val ready = File(dir, ".ready")
        val bin = { File(dir.listFiles { f -> f.isDirectory }?.firstOrNull(), "baguette") }
        if (ready.exists() && bin().canExecute()) return bin()
        return try {
            dir.deleteRecursively()
            dir.mkdirs()
            val tar = File(dir, "baguette.tar.gz")
            javaClass.getResourceAsStream(res)!!.use { i -> tar.outputStream().use { i.copyTo(it) } }
            val rc = ProcessBuilder("/usr/bin/tar", "-xzf", tar.path, "-C", dir.path).start().waitFor()
            tar.delete()
            if (rc != 0 || !bin().canExecute()) return null
            ready.writeText(version)
            bin()
        } catch (e: Exception) {
            thisLogger().warn("Extracting bundled baguette failed", e)
            null
        }
    }

    /** Returns null on success, or a human-readable error. Blocking: call off the EDT. */
    @Synchronized
    fun ensureRunning(): String? {
        if (isUp()) return null
        val arch = System.getProperty("os.arch")
        if (arch != "aarch64" && arch != "arm64") {
            return "Unsupported CPU ($arch). Baguette ships Apple Silicon (arm64) builds only; Intel Macs are not supported."
        }
        val bin = findBinary() ?: return "baguette not found. bundled copy missing and none installed (brew install baguette)"
        thisLogger().info("Starting ${bin.path} serve")
        process = try {
            ProcessBuilder(bin.path, "serve", "--port", port.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        } catch (e: Exception) {
            return "Failed to start baguette: ${e.message}"
        }
        repeat(40) {
            if (isUp()) return null
            if (process?.isAlive == false) return "baguette exited with code ${process?.exitValue()}. Try running `baguette serve` in a terminal."
            Thread.sleep(250)
        }
        return "baguette did not come up on port $port within 10s."
    }

    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
    }

    override fun dispose() = stop()
}
