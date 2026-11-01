import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.baguettestudio"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        // Build against the locally installed Android Studio.
        local("/Applications/Android Studio.app")
    }
}

kotlin { jvmToolchain(25) }

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
}

// Fetch the pinned Baguette release into resources (not committed to git).
val baguetteVersion = "0.2.12"
val baguetteSha256 = "32c21cc91c1d3b930bb01e9404139608e6d1ae1d274ef83b4f9dc68d99d8eeac"
val fetchBaguette by tasks.registering {
    val out = layout.projectDirectory.dir("src/main/resources/baguette")
    outputs.dir(out)
    doLast {
        val dir = out.asFile.also { it.mkdirs() }
        val tgz = File(dir, "baguette-macos-arm64.tar.gz")
        fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
        if (!tgz.exists() || sha(tgz) != baguetteSha256) {
            uri("https://github.com/tddworks/baguette/releases/download/v$baguetteVersion/baguette_v${baguetteVersion}_macOS_arm64.tar.gz")
                .toURL().openStream().use { i -> tgz.outputStream().use { i.copyTo(it) } }
            check(sha(tgz) == baguetteSha256) { "Baguette checksum mismatch" }
        }
        File(dir, "VERSION").writeText(baguetteVersion)
        File(dir, "LICENSE-baguette.txt").takeIf { !it.exists() }?.writeText("Apache-2.0, see https://github.com/tddworks/baguette/blob/main/LICENSE\n")
    }
}
tasks.processResources { dependsOn(fetchBaguette) }
