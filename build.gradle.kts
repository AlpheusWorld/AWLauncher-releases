import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

group = "ru.aw"
version = "1.0.11"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("net.raphimc:MinecraftAuth:5.0.2")
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.jsoup:jsoup:1.23.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("user.home", temporaryDir.absolutePath)
}

val microsoftClientId = providers.environmentVariable("AW_MS_CLIENT_ID")
    .orElse(providers.gradleProperty("awMsClientId"))
    .orElse("")
val signerThumbprint = providers.environmentVariable("AW_SIGNER_THUMBPRINT")
    .orElse(providers.gradleProperty("awSignerThumbprint"))
    .orElse("")
val updateFeedUrl = providers.environmentVariable("AW_UPDATE_URL")
    .orElse(providers.gradleProperty("awUpdateUrl"))
    .orElse("https://github.com/AlpheusWorld/AWLauncher-releases/releases/latest/download/update.json")
val generatedLauncherConfig = layout.buildDirectory.dir("generated/awlauncher-config")
val curseForgeApiKey = providers.environmentVariable("AW_CURSEFORGE_API_KEY").orElse("")
val discordAppId = providers.environmentVariable("AW_DISCORD_APP_ID")
    .orElse(providers.gradleProperty("awDiscordAppId"))
    .map { it.trim().ifBlank { "1555994295134326945" } }.orElse("1555994295134326945")

val generateLauncherConfig = tasks.register("generateLauncherConfig") {
    inputs.property("microsoftClientId", microsoftClientId)
    inputs.property("signerThumbprint", signerThumbprint)
    inputs.property("updateFeedUrl", updateFeedUrl)
    inputs.property("curseForgeApiKey", curseForgeApiKey)
    inputs.property("discordAppId", discordAppId)
    outputs.dir(generatedLauncherConfig)
    doLast {
        val dir = generatedLauncherConfig.get().asFile.apply { mkdirs() }
        dir.resolve("auth-client-id.txt").writeText(microsoftClientId.get().trim())
        dir.resolve("signer-thumbprint.txt").writeText(signerThumbprint.get().replace(" ", "").uppercase())
        dir.resolve("update-url.txt").writeText(updateFeedUrl.get().trim())
        dir.resolve("curseforge-api-key.txt").writeText(curseForgeApiKey.get().trim())
        val discordId = discordAppId.get().trim()
        check(discordId.isBlank() || Regex("^[0-9]{17,20}$").matches(discordId)) { "Invalid AW_DISCORD_APP_ID" }
        dir.resolve("discord-app-id.txt").writeText(discordId)
    }
}

sourceSets["main"].resources.srcDir(generatedLauncherConfig)
tasks.processResources.configure { dependsOn(generateLauncherConfig) }

val validateReleaseConfiguration = tasks.register("validateReleaseConfiguration") {
    doLast {
        val id = microsoftClientId.get().trim()
        check(id.isBlank() || Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$").matches(id)) {
            "AW_MS_CLIENT_ID must be a registered Microsoft Entra application ID"
        }
        val thumbprint = signerThumbprint.get().replace(" ", "").uppercase()
        check((thumbprint.isBlank() || Regex("^[0-9A-F]{40}$").matches(thumbprint)) &&
            (thumbprint.isNotBlank() || file("src/main/resources/update/public-key.txt").exists())) {
            "Configure an update verification key or an Authenticode certificate before packaging a release"
        }
        val feed = updateFeedUrl.get().trim()
        check(feed.startsWith("https://")) {
            "AW_UPDATE_URL must be a publicly reachable HTTPS update feed"
        }
    }
}

tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
    dependsOn(validateReleaseConfiguration)
}

compose.desktop {
    application {
        mainClass = "ru.aw.launcher.MainKt"

        jvmArgs += listOf(
            "-Xms24m",
            "-Xmx384m",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:+ExplicitGCInvokesConcurrent",
            "-XX:MaxMetaspaceSize=192m",
            "-Dfile.encoding=UTF-8",
            "-Dsun.java2d.dpiaware=true",
        )

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AWLauncher"
            packageVersion = project.version.toString()
            vendor = "AlpheusWorld"
            description = "Minecraft launcher"

            modules(
                "java.desktop",
                "java.instrument",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.management",
                "jdk.naming.dns",
                "jdk.unsupported",
                "jdk.zipfs",
            )

            windows {
                menuGroup = "AlpheusWorld"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "9F1D3B2A-7C4E-4E8B-9A21-5D6E8C0F3B71"
                console = false
                iconFile.set(project.file("branding/AWLauncher.ico"))
            }
        }

        buildTypes.release.proguard {
            version.set("7.7.0")
            isEnabled.set(true)
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("compose-desktop.pro"))
        }
    }
}

tasks.register<JavaExec>("renderScreens") {
    group = "verification"
    description = "Renders the launcher screens to PNG files in build/preview"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.aw.launcher.dev.RenderScreensKt")
    systemProperty("user.home", temporaryDir.absolutePath)
    args(layout.buildDirectory.dir("preview").get().asFile.absolutePath, "1.25", (findProperty("screens") as String?).orEmpty())
}

tasks.register<JavaExec>("benchmarkUi") {
    group = "verification"
    description = "Measures offscreen page creation and animation frame CPU time with synthetic data"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.aw.launcher.dev.UiPerformanceKt")
    systemProperty("user.home", temporaryDir.absolutePath)
    args(layout.buildDirectory.file((findProperty("benchmarkReport") as String?) ?: "ui-performance.tsv").get().asFile.absolutePath)
}

tasks.register<JavaExec>("smokeLoaders") {
    group = "verification"
    description = "Installs each version:loader from -Ptargets and starts the game up to the main menu"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.aw.launcher.dev.LoaderSmokeKt")
    (findProperty("smokeHome") as String?)?.let { systemProperty("user.home", it) }
    args((findProperty("targets") as String? ?: "").split(',').filter { it.isNotBlank() })
}

tasks.register<JavaExec>("smokeCompanion") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.aw.launcher.dev.CompanionSmokeKt")
    systemProperty("user.home", layout.buildDirectory.dir("companion-smoke-home").get().asFile.absolutePath)
    args((findProperty("gameVersion") as String?) ?: "26.1.2", file("client-mod/build/libs").absolutePath)
}

tasks.register<JavaExec>("smokeMicrosoftAuth") {
    group = "verification"
    description = "Checks Microsoft sign-in and stores the confirmed Minecraft account securely"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.aw.launcher.dev.MicrosoftAuthSmokeKt")
    systemProperty("user.home", System.getProperty("user.home"))
    systemProperty("file.encoding", "UTF-8")
    environment("AW_DEBUG", "1")
    if (project.hasProperty("authRefresh")) args("--refresh")
}

val desktopInstallDir = File(System.getProperty("user.home"), "AWLauncher")

val distributionLegal = layout.buildDirectory.dir("distribution-legal")
val generateDistributionLegal = tasks.register("generateDistributionLegal") {
    inputs.files(configurations.runtimeClasspath)
    inputs.dir("branding/legal")
    inputs.dir("src/main/resources/fonts")
    outputs.dir(distributionLegal)
    doLast {
        val output = distributionLegal.get().asFile.apply { mkdirs() }
        val dependencies = configurations.runtimeClasspath.get().resolvedConfiguration.resolvedArtifacts.sortedBy { it.moduleVersion.id.toString() }
        val rows = dependencies.map { artifact ->
            val id = artifact.moduleVersion.id
            ZipFile(artifact.file).use { jar ->
                jar.entries().asSequence().filter { !it.isDirectory &&
                    Regex("(?i)(?:^|/)(?:LICENSE|NOTICE|COPYING|COPYRIGHT)(?:[.-][^/]*)?$").containsMatchIn(it.name) }.forEach { entry ->
                    val target = output.resolve("dependencies/${id.group}.${id.name}/${entry.name.substringAfterLast('/')}")
                    target.parentFile.mkdirs()
                    jar.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                }
            }
            "| `${id.group}:${id.name}:${id.version}` | [Maven metadata](https://search.maven.org/artifact/${id.group}/${id.name}/${id.version}/jar) |"
        }
        output.resolve("THIRD_PARTY_NOTICES.md").writeText(
            "# Third-party components\n\nAWLauncher uses separately licensed libraries. Author notices remain in the JARs and are also copied alongside this file. " +
                "MinecraftAuth is an LGPL-3.0 replaceable shared Java library; corresponding sources accompany the release. " +
                "OpenJDK/Temurin notices remain under runtime/legal; its source archive accompanies the release.\n\n" +
                "| Component | Upstream metadata |\n| --- | --- |\n" + rows.joinToString("\n") + "\n"
        )
        copy { from("branding/legal"); into(output) }
        copy { from("src/main/resources/fonts") { include("*OFL*.txt") }; into(output.resolve("fonts")) }
        copy { from("src/main/resources/flags/LICENSE.txt"); into(output.resolve("flags")) }
        val inventory = layout.buildDirectory.file("publication/runtime-dependencies.tsv").get().asFile
        inventory.parentFile.mkdirs()
        inventory.writeText(dependencies.joinToString("\n") { "${it.moduleVersion.id.group}\t${it.moduleVersion.id.name}\t${it.moduleVersion.id.version}\t${it.file.absolutePath}" })
    }
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    dependsOn(generateDistributionLegal)
    doLast {
        val image = (this as AbstractJPackageTask).destinationDir.get().asFile.resolve("AWLauncher")
        check(image.resolve("AWLauncher.exe").exists()) { "Application image is missing" }
        copy { from(distributionLegal); into(image.resolve("legal")) }
    }
}

val installerResources = layout.buildDirectory.dir("installer/resources")
val prepareInstallerResources = tasks.register<Copy>("prepareInstallerResources") {
    from(layout.projectDirectory.dir("branding/installer"))
    exclude("Setup.cs")
    into(installerResources)
    filteringCharset = "UTF-8"
    filesMatching("overrides.wxi") {
        val assets = installerResources.get().asFile.absolutePath.replace('\\', '/')
            .replace("&", "&amp;").replace("\"", "&quot;")
        filter { line: String -> line.replace("@AW_INSTALLER_ASSETS@", assets) }
    }
}

// Compose 1.7 clears --resource-dir inside its packaging action. Use the same
// jpackage/WiX engine with our resources, keeping its task names and app images.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Msi || targetFormat == TargetFormat.Exe) {
        val imageTask = tasks.named<AbstractJPackageTask>(
            if (name.startsWith("packageRelease")) "createReleaseDistributable" else "createDistributable"
        )
        dependsOn(prepareInstallerResources, imageTask)
        appImage.set(imageTask.flatMap { it.destinationDir }.map { it.dir(packageName.get()) })
        inputs.dir(installerResources)
        if (targetFormat == TargetFormat.Exe) {
            dependsOn(if (name.startsWith("packageRelease")) "packageReleaseMsi" else "packageMsi")
            inputs.files("branding/installer/Setup.cs", "src/main/resources/update/splash.cs", "branding/AWLauncher-mark.png",
                "branding/AWLauncher.ico", "src/main/resources/fonts/Onest-Regular.ttf", "src/main/resources/fonts/Onest-Bold.ttf")
            inputs.dir(imageTask.flatMap { it.destinationDir }.map { it.asFile.parentFile.resolve("msi") })
        }
        actions.clear()
        doLast {
            val destination = destinationDir.get().asFile
            check(destination.toPath().toAbsolutePath().normalize().startsWith(
                layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            )) { "Installer output must stay inside the build directory" }
            project.delete(destination)
            destination.mkdirs()
            if (targetFormat == TargetFormat.Exe) {
                val framework = File(System.getenv("WINDIR") ?: "C:/Windows", "Microsoft.NET/Framework64/v4.0.30319")
                val compiler = framework.resolve("csc.exe")
                check(compiler.exists()) { "The Windows .NET Framework compiler is required to build the installer" }
                val image = appImage.get().asFile
                val msi = image.parentFile.parentFile.resolve("msi/${packageName.get()}-${packageVersion.get()}.msi")
                check(msi.exists()) { "MSI payload not found: $msi" }
                val metadata = layout.buildDirectory.file("installer/${name}-metadata.txt").get().asFile
                val product = UUID.nameUUIDFromBytes(
                    "ProductCode/${packageVendor.get()}/${packageName.get()}/${packageVersion.get()}".toByteArray(Charsets.UTF_8)
                )
                val payloadHash = MessageDigest.getInstance("SHA-256").digest(msi.readBytes()).joinToString("") { "%02x".format(it) }
                metadata.writeText(listOf(packageVersion.get(), "{${product.toString().uppercase()}}", "{${winUpgradeUuid.get()}}",
                    image.walkTopDown().filter { it.isFile }.sumOf { it.length() }.toString(), payloadHash).joinToString("\n"))
                val installer = destination.resolve("${packageName.get()}-${packageVersion.get()}.exe")
                val compile = mutableListOf("/nologo", "/target:winexe", "/platform:x64", "/optimize+", "/main:AwSetup",
                    "/out:${installer.absolutePath}", "/win32icon:${iconFile.get().asFile.absolutePath}",
                    "/reference:System.Windows.Forms.dll", "/reference:System.Drawing.dll",
                    "/resource:${msi.absolutePath},AWSetup.Payload", "/resource:${metadata.absolutePath},AWSetup.Metadata")
                listOf("branding/AWLauncher-mark.png" to "Mark", "src/main/resources/fonts/Onest-Regular.ttf" to "Regular",
                    "src/main/resources/fonts/Onest-Bold.ttf" to "Bold").forEach { (path, id) ->
                    compile.add("/resource:${project.file(path).absolutePath},AWSetup.$id")
                }
                compile.add(project.file("branding/installer/Setup.cs").absolutePath)
                compile.add(project.file("src/main/resources/update/splash.cs").absolutePath)
                project.exec { executable = compiler.absolutePath; args(compile) }.assertNormalExitValue()
                logger.lifecycle("Installer written to $installer")
                return@doLast
            }
            val options = mutableListOf(
                "--type", if (targetFormat == TargetFormat.Msi) "msi" else "exe",
                "--app-image", appImage.get().asFile.absolutePath,
                "--dest", destination.absolutePath,
                "--name", packageName.get(),
                "--app-version", packageVersion.get(),
                "--resource-dir", installerResources.get().asFile.absolutePath,
            )
            fun option(name: String, value: String?) { if (!value.isNullOrBlank()) options.addAll(listOf(name, value)) }
            option("--vendor", packageVendor.orNull)
            option("--description", packageDescription.orNull)
            option("--copyright", packageCopyright.orNull)
            option("--install-dir", installationPath.orNull)
            option("--license-file", licenseFile.orNull?.asFile?.absolutePath)
            option("--win-upgrade-uuid", winUpgradeUuid.orNull)
            option("--win-menu-group", winMenuGroup.orNull)
            if (winPerUserInstall.orNull == true) options.add("--win-per-user-install")
            if (winDirChooser.orNull == true) options.add("--win-dir-chooser")
            if (winShortcut.orNull == true) options.add("--win-shortcut")
            if (winMenu.orNull == true) options.add("--win-menu")
            if (project.hasProperty("installerVerbose")) options.add("--verbose")
            project.exec {
                executable = File(javaHome.get(), "bin/jpackage.exe").absolutePath
                args(options)
                environment("PATH", wixToolsetDir.get().asFile.absolutePath + File.pathSeparator + System.getenv("PATH"))
            }.assertNormalExitValue()
            logger.lifecycle("Branded installer written to $destination")
        }
    }
}

val deployDesktop = tasks.register("deployDesktop") {
    group = "distribution"
    description = "Replaces the desktop copy of the launcher with the latest build"
    dependsOn("createDistributable")
    onlyIf { System.getProperty("os.name").lowercase().contains("win") && !project.hasProperty("noDeploy") }
    doLast {
        val built = layout.buildDirectory.dir("compose/binaries/main/app/AWLauncher").get().asFile
        check(built.resolve("AWLauncher.exe").exists()) { "No distributable in $built" }

        val running = ProcessHandle.allProcesses()
            .filter { process ->
                process.info().command()
                    .map { it.startsWith(desktopInstallDir.absolutePath, ignoreCase = true) }
                    .orElse(false)
            }
            .map { it.pid() }
            .toList()
        if (running.isNotEmpty()) {
            throw GradleException(
                "AWLauncher from $desktopInstallDir is running (PID ${running.joinToString()}): " +
                    "close it and run `gradlew deployDesktop`"
            )
        }

        val icon = compose.desktop.application.nativeDistributions.windows.iconFile.get().asFile
        val iconHash = MessageDigest.getInstance("SHA-1").digest(icon.readBytes()).joinToString("") { "%02x".format(it) }
        val shortcutIcon = "AWLauncher-${iconHash.take(10)}.ico"
        val iconChanged = !desktopInstallDir.resolve(shortcutIcon).exists()

        val staging = File(desktopInstallDir.parentFile, "${desktopInstallDir.name}.new")
        val previous = File(desktopInstallDir.parentFile, "${desktopInstallDir.name}.old")
        forceDelete(staging)
        forceDelete(previous)
        built.copyRecursively(staging)
        icon.copyTo(staging.resolve(shortcutIcon))
        if (desktopInstallDir.exists()) {
            check(desktopInstallDir.renameTo(previous)) { "Could not move the old $desktopInstallDir aside" }
        }
        check(staging.renameTo(desktopInstallDir)) { "Could not put the new build in $desktopInstallDir" }
        forceDelete(previous)

        ensureDesktopShortcut(
            desktopInstallDir.resolve("AWLauncher.exe"),
            desktopInstallDir.resolve(shortcutIcon),
            rewrite = iconChanged,
        )
        if (iconChanged) logger.lifecycle("New icon: the desktop shortcut now takes it from $shortcutIcon")
        logger.lifecycle("AWLauncher deployed to $desktopInstallDir")
    }
}

fun forceDelete(dir: File) {
    if (!dir.exists()) return
    dir.walkBottomUp().forEach { it.setWritable(true) }
    check(dir.deleteRecursively()) { "Could not delete $dir" }
}

fun ensureDesktopShortcut(exe: File, icon: File, rewrite: Boolean) {
    val desktops = listOfNotNull(System.getenv("USERPROFILE"), System.getenv("OneDrive")).map { File(it, "Desktop") }
    if (!rewrite && desktops.any { it.resolve("AWLauncher.lnk").exists() }) return

    fun literal(value: String) = "'" + value.replace("'", "''") + "'"
    val script = listOf(
        "\$path = Join-Path ([Environment]::GetFolderPath('Desktop')) 'AWLauncher.lnk'",
        "\$link = (New-Object -ComObject WScript.Shell).CreateShortcut(\$path)",
        "\$link.TargetPath = ${literal(exe.absolutePath)}",
        "\$link.WorkingDirectory = ${literal(exe.parent)}",
        "\$link.IconLocation = ${literal(icon.absolutePath + ",0")}",
        "\$link.Save()",
    ).joinToString("\n")
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder(
        "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "Desktop shortcut not written: $output" }
}

afterEvaluate {
    tasks.named("createRuntimeImage") {
        doLast {
            val image = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
            val borrowed = image.resolve("bin/java.exe")
            File(compose.desktop.application.javaHome, "bin/java.exe").copyTo(borrowed, overwrite = true)
            try {
                val dump = ProcessBuilder(borrowed.absolutePath, "-Xshare:dump")
                    .redirectErrorStream(true)
                    .start()
                val output = dump.inputStream.bufferedReader().readText()
                check(dump.waitFor() == 0) { "CDS dump failed:\n$output" }
            } finally {
                borrowed.delete()
            }
            check(image.resolve("bin/server/classes.jsa").exists()) { "CDS dump produced no archive" }
        }
    }

    val classArchiveKey = provider {
        val digest = MessageDigest.getInstance("SHA-1")
        (listOf(tasks.jar.get().archiveFile.get().asFile) + configurations.runtimeClasspath.get().files.sortedBy { it.name })
            .forEach { digest.update(it.readBytes()) }
        digest.digest().joinToString("") { "%02x".format(it) }.take(10)
    }

    tasks.withType<AbstractJPackageTask>().configureEach {
        launcherJvmArgs.addAll(classArchiveKey.map { key ->
            listOf("-XX:SharedArchiveFile=\$APPDIR\\\\aw-$key.jsa", "-XX:+AutoCreateSharedArchive")
        })
    }

    tasks.named("createDistributable") { finalizedBy(deployDesktop) }
}
