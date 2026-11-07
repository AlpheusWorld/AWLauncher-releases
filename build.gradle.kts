import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

plugins {
    // Required by the jpackage build plugin. No Kotlin launcher sources are compiled.
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose)
}

group = "ru.aw"
version = "1.0.56"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

sourceSets["main"].java.setSrcDirs(listOf("tools/packaging/java"))

val microsoftClientId = providers.environmentVariable("AW_MS_CLIENT_ID")
    .orElse(providers.gradleProperty("awMsClientId"))
    .orElse("")
val signerThumbprint = providers.environmentVariable("AW_SIGNER_THUMBPRINT")
    .orElse(providers.gradleProperty("awSignerThumbprint"))
    .orElse("")
val updateFeedUrl = providers.environmentVariable("AW_UPDATE_URL")
    .orElse(providers.gradleProperty("awUpdateUrl"))
    .orElse("https://github.com/AlpheusWorld/AWLauncher-releases/releases/latest/download/update.json")
val discordAppId = providers.environmentVariable("AW_DISCORD_APP_ID")
    .orElse(providers.gradleProperty("awDiscordAppId"))
    .map { it.trim().ifBlank { "1555994295134326945" } }.orElse("1555994295134326945")

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
        mainClass = "ru.aw.launcher.packaging.JPackagePlaceholder"

        nativeDistributions {
            if (System.getProperty("os.name").lowercase().contains("linux")) targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            else targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "AWLauncher"
            packageVersion = project.version.toString()
            vendor = "AlpheusWorld"
            description = "Minecraft launcher"

            modules("java.base")

            windows {
                menuGroup = "AlpheusWorld"
                menu = true
                shortcut = true
                perUserInstall = true
                dirChooser = true
                upgradeUuid = "C46C0DBA-3FD0-47F9-84FB-61F746715307"
                console = false
                iconFile.set(project.file("branding/AWLauncher.ico"))
            }
            linux {
                packageName = "awlauncher"
                menuGroup = "Games"
                appCategory = "Game"
                shortcut = true
                debMaintainer = "contact@awlauncher.ru"
                rpmLicenseType = "MIT"
                iconFile.set(project.file("branding/AWLauncher-mark.png"))
            }
        }

    }
}

val desktopInstallDir = File(System.getProperty("user.home"), "AWLauncher")
val nativeProfile = if (providers.gradleProperty("awNativeDebug").orNull == "true") "debug" else "release"
val buildNativeUi = tasks.register<Exec>("buildNativeUi") {
    workingDir("rust/tauri")
    environment("AW_MS_CLIENT_ID", microsoftClientId.get())
    environment("AW_DISCORD_APP_ID", discordAppId.get())
    inputs.property("nativeDiscordAppId", discordAppId)
    environment("AW_UPDATE_URL", updateFeedUrl.get())
    environment("AW_SIGNER_THUMBPRINT", signerThumbprint.get())
    inputs.property("nativeUpdateUrl", updateFeedUrl)
    inputs.property("nativeSigner", signerThumbprint)
    inputs.property("nativeMicrosoftClientId", microsoftClientId)
    commandLine(listOf("cargo", "build", "--locked") + if (nativeProfile == "release") listOf("--release") else emptyList())
    inputs.files(fileTree("rust/tauri/src"),fileTree("rust/tauri/web"),fileTree("rust/ui/src"),fileTree("rust/ui/assets"),"rust/tauri/Cargo.toml","rust/tauri/Cargo.lock","rust/tauri/tauri.conf.json","rust/tauri/build.rs")
    inputs.files(fileTree("rust/engine/src"), "rust/engine/Cargo.toml")
    inputs.dir("src/main/resources/skins")
    inputs.dir("src/main/resources/update")
    inputs.dir("src/main/resources/brand")
    inputs.file("branding/AWLauncher.ico")
    inputs.dir("src/main/resources/fonts")
    inputs.dir("src/main/resources/instance-icons")
    inputs.dir("src/main/resources/flags")
    inputs.dir("src/main/resources/i18n")
    outputs.file("rust/tauri/target/$nativeProfile/${if(System.getProperty("os.name").lowercase().contains("win"))"awlauncher-tauri.exe" else "awlauncher-tauri"}")
}
tasks.register<Exec>("nativeBackendSmoke") {
    group = "verification"
    dependsOn(buildNativeUi)
    val windows = System.getProperty("os.name").lowercase().contains("win")
    val exe = file("rust/tauri/target/$nativeProfile/" + if (windows) "awlauncher-tauri.exe" else "awlauncher-tauri")
    commandLine(exe, "--native-task")
    environment("AW_NATIVE_DATA_HOME", temporaryDir.absolutePath)
    standardInput = ByteArrayInputStream("{\"op\":\"ping\"}\n".toByteArray())
    val captured = ByteArrayOutputStream()
    standardOutput = captured
    doLast { check(captured.toString().contains("\"protocol\":1")) { "Rust engine handshake failed: $captured" } }
}

val distributionLegal = layout.buildDirectory.dir("distribution-legal")
val collectNativeNotices=tasks.register<Exec>("collectNativeNotices"){
    dependsOn(buildNativeUi)
    commandLine("python", "tools/collect_rust_notices.py")
    inputs.files("rust/tauri/Cargo.toml","rust/tauri/Cargo.lock","tools/collect_rust_notices.py")
    outputs.dir(distributionLegal.map{it.dir("rust")})
}
val generateDistributionLegal = tasks.register("generateDistributionLegal") {
    dependsOn(collectNativeNotices)
    inputs.dir("branding/legal")
    inputs.dir("src/main/resources/fonts")
    outputs.dir(distributionLegal)
    doLast {
        val output = distributionLegal.get().asFile.apply { mkdirs() }
        output.resolve("THIRD_PARTY_NOTICES.md").writeText(
            "# Third-party components\n\nRust dependency notices are under rust/. " +
                "Minecraft downloads its required Java runtime separately.\n"
        )
        copy { from("branding/legal"); into(output) }
        copy { from("src/main/resources/fonts") { include("*OFL*.txt") }; into(output.resolve("fonts")) }
        copy { from("src/main/resources/flags/LICENSE.txt"); into(output.resolve("flags")) }

    }
}

tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
    dependsOn(generateDistributionLegal)
    dependsOn(buildNativeUi)
    inputs.file(layout.projectDirectory.file("rust/tauri/target/$nativeProfile/${if(System.getProperty("os.name").lowercase().contains("win"))"awlauncher-tauri.exe" else "awlauncher-tauri"}"))
    doLast {
        val image = (this as AbstractJPackageTask).destinationDir.get().asFile.resolve("AWLauncher")
        val launcher = if (System.getProperty("os.name").lowercase().contains("win")) image.resolve("AWLauncher.exe") else image.resolve("bin/AWLauncher")
        check(launcher.exists()) { "Application image is missing" }
        copy { from(distributionLegal); into(image.resolve("legal")) }
        val windows=System.getProperty("os.name").lowercase().contains("win")
        val native=file("rust/tauri/target/$nativeProfile/${if(windows)"awlauncher-tauri.exe" else "awlauncher-tauri"}")
        check(native.isFile){"Native UI executable is missing"}
        native.copyTo(launcher,overwrite=true).setExecutable(true,false)
        // jpackage creates these placeholders before we install the Rust binary.
        val appDir = image.resolve(if (windows) "app" else "lib/app")
        val runtime = image.resolve(if (windows) "runtime" else "lib/runtime")
        val imageRoot = image.canonicalFile.toPath()
        check(runtime.canonicalFile.toPath().startsWith(imageRoot) && runtime.canonicalFile.toPath() != imageRoot)
        project.delete(runtime)
        appDir.listFiles { candidate -> candidate.isFile && candidate.extension == "jar" }
            ?.forEach { jar -> check(jar.canonicalFile.toPath().startsWith(imageRoot)); check(jar.delete()) }

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
    if (targetFormat == TargetFormat.Deb || targetFormat == TargetFormat.Rpm) {
        val imageTask = tasks.named<AbstractJPackageTask>(
            if (name.startsWith("packageRelease")) "createReleaseDistributable" else "createDistributable"
        )
        dependsOn(imageTask)
        appImage.set(imageTask.flatMap { it.destinationDir }.map { it.dir(packageName.get()) })
        actions.clear()
        doLast {
            val destination = destinationDir.get().asFile
            check(destination.toPath().toAbsolutePath().normalize().startsWith(
                layout.buildDirectory.get().asFile.toPath().toAbsolutePath().normalize()
            )) { "Installer output must stay inside the build directory" }
            project.delete(destination)
            destination.mkdirs()
            val deb = targetFormat == TargetFormat.Deb
            val options = mutableListOf(
                "--type", if (deb) "deb" else "rpm",
                "--app-image", appImage.get().asFile.absolutePath,
                "--dest", destination.absolutePath, "--name", packageName.get(),
                "--app-version", packageVersion.get(), "--vendor", packageVendor.get(),
                "--linux-package-name", "awlauncher", "--linux-menu-group", "Games",
                "--linux-app-category", "Game", "--linux-shortcut",
                "--linux-package-deps", if (deb) "libsecret-tools, gnome-keyring, pkexec, xdg-utils, libxkbcommon-x11-0, libegl1, libgl1, libwebkit2gtk-4.1-0" else "libsecret, gnome-keyring, polkit, xdg-utils, libxkbcommon-x11, mesa-libEGL, mesa-libGL, webkit2gtk4.1",
            )
            if (deb) options.addAll(listOf("--linux-deb-maintainer", "contact@awlauncher.ru"))
            else options.addAll(listOf("--linux-rpm-license-type", "MIT"))
            project.exec { executable = File(javaHome.get(), "bin/jpackage").absolutePath; args(options) }.assertNormalExitValue()
        }
    }
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
    tasks.named("createDistributable") { finalizedBy(deployDesktop) }
}
