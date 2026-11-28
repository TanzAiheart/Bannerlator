package com.winlator.star

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentProfile
import com.winlator.star.contents.ContentsManager
import com.winlator.star.core.TarCompressorUtils
import com.winlator.star.core.WineInfo
import com.winlator.star.core.WinePath
import com.winlator.star.inputcontrols.InputControlsManager
import com.winlator.star.ui.screens.launchShortcutNow
import com.winlator.star.xenvironment.ImageFs
import com.winlator.star.xenvironment.ImageFsInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume

internal class Ch3xpStandalone(private val activity: MainActivity) {
    private val status = TextView(activity)
    private val progress = ProgressBar(activity)
    private val play = Button(activity)
    private val importGame = Button(activity)
    private val pickZip = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepare(uri)
    }

    fun show() {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(status.apply { gravity = Gravity.CENTER; textSize = 18f })
            addView(progress.apply { visibility = View.GONE })
            addView(importGame.apply {
                isAllCaps = false
                text = "Import game ZIP"
                setOnClickListener { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
            })
            addView(play.apply {
                isAllCaps = false
                text = "Play Chivalrous Heroes XP"
                setOnClickListener { prepare(null) }
            })
            addView(Button(activity).apply {
                isAllCaps = false
                text = "Copy launch report"
                setOnClickListener { copyLaunchReport(activity) }
            })
        }
        activity.setContentView(page)
        updateLauncher()
    }

    private fun updateLauncher() {
        val container = activity.containerManager.containers.firstOrNull { it.name == TITLE }
        val installed = container != null && File(container.rootDir, ".wine/drive_c/CH3XP/Shkxp.exe").isFile
        play.isEnabled = installed
        importGame.isEnabled = !installed
        status.text = if (installed) TITLE else "Select your Chivalrous Heroes XP game ZIP."
    }

    private fun prepare(importUri: Uri?) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        play.isEnabled = false
        importGame.isEnabled = false
        progress.visibility = View.VISIBLE
        status.text = TITLE
        activity.lifecycleScope.launch {
            var result: String? = null
            try {
                installRuntime()
                val container = getOrCreateContainer()
                status.text = if (importUri == null) "Preparing Chivalrous Heroes XP…" else "Importing game files…"
                val shortcut = withContext(Dispatchers.IO) { installGame(container, importUri) }
                if (importUri == null) launchShortcutNow(activity, shortcut)
                else result = "Game files imported. Tap Play to start."
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("CH3XP", "Could not prepare the game", error)
                result = error.message ?: "Could not prepare Chivalrous Heroes XP."
            } finally {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.GONE
                updateLauncher()
                result?.let { status.text = it }
            }
        }
    }

    companion object {
        private const val TITLE = "Chivalrous Heroes XP"
        private const val BUNDLED_BOX64 = "0.4.5-gst"
        private const val TOUCH_PROFILE = "CH3XP Touch Controls"
        private val OVERRIDES = listOf("Shkxp.exe", "Shkxp.exe.manifest", "chxp-sdl3.dll", "SDL3.dll",
            "SDL3-LICENSE.txt", "ddraw.dll", "ddraw.ini")

        fun copyLaunchReport(context: Context) {
            val log = File(context.filesDir, "ch3xp-last-launch.txt")
            val report = runCatching {
                RandomAccessFile(log, "r").use { input ->
                    val size = minOf(input.length(), 24_000L).toInt()
                    input.seek(input.length() - size)
                    ByteArray(size).also { input.readFully(it) }.toString(Charsets.UTF_8)
                }
            }.getOrDefault("")
            if (report.isBlank()) {
                Toast.makeText(context, "No Windows launch report yet.", Toast.LENGTH_SHORT).show()
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("CH3XP launch report", report))
            Toast.makeText(context, "Launch report copied", Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun installRuntime() {
        val image = ImageFs.find(activity)
        if (image.isValid && image.version >= ImageFsInstaller.LATEST_VERSION) return
        status.text = "Installing the Windows runtime…"
        suspendCancellableCoroutine<Unit> { continuation ->
            ImageFsInstaller.installFromAssetsWithCallback(activity, { percent ->
                activity.runOnUiThread {
                    if (continuation.isActive) status.text = "Installing the Windows runtime… $percent%"
                }
            }, {
                if (continuation.isActive) continuation.resume(Unit)
            })
        }
        check(image.isValid && image.version >= ImageFsInstaller.LATEST_VERSION) {
            "The bundled Windows runtime could not be installed."
        }
    }

    private suspend fun getOrCreateContainer(): Container {
        val manager = activity.containerManager
        status.text = "Preparing the bundled Proton layer…"
        val (contents, wineVersion) = withContext(Dispatchers.IO) { prepareWineLayer() }
        manager.containers.firstOrNull { it.name == TITLE }?.let { container ->
            if (container.box64Version.isNullOrBlank()) {
                container.box64Version = BUNDLED_BOX64
                container.saveData()
            }
            return container
        }
        status.text = "Creating the Windows game container…"
        val settings = JSONObject()
            .put("name", TITLE)
            .put("wineVersion", wineVersion)
            .put("box64Version", BUNDLED_BOX64)
            .put("screenSize", "800x600")
            .put("dxwrapper", "wined3d")
            .put("drives", "")
        return suspendCancellableCoroutine<Container?> { continuation ->
            manager.createContainerAsync(settings, contents) { container ->
                if (continuation.isActive) continuation.resume(container)
            }
        } ?: error("Could not create the Windows container using $wineVersion. Check that the device has free storage and retry.")
    }

    private fun prepareWineLayer(): Pair<ContentsManager, String> {
        val contents = ContentsManager(activity)
        val profile = activity.assets.open("ch3xp/proton-profile.json").bufferedReader().use {
            JSONObject(it.readText())
        }
        val expected = ContentProfile().apply {
            type = ContentProfile.ContentType.CONTENT_TYPE_PROTON
            verName = profile.getString("versionName")
            verCode = profile.getInt("versionCode")
        }
        val entry = ContentsManager.getEntryName(expected)
        val finalDir = ContentsManager.getInstallDir(activity, expected)
        fun complete(directory: File): Boolean {
            val actual = contents.readProfile(File(directory, ContentsManager.PROFILE_NAME)) ?: return false
            return actual.type == expected.type && ContentsManager.getEntryName(actual) == entry &&
                File(directory, actual.wineBinPath).isDirectory &&
                File(directory, actual.wineLibPath).isDirectory &&
                File(directory, "prefixPack.txz").isFile
        }

        if (!complete(finalDir)) {
            check(!finalDir.exists()) { "The bundled Proton layer is incomplete." }
            check(finalDir.parentFile!!.isDirectory || finalDir.parentFile!!.mkdirs()) {
                "Could not create the Proton installation directory."
            }
            val stage = Files.createTempDirectory(finalDir.parentFile!!.toPath(), ".ch3xp-proton-").toFile()
            try {
                val asset = "ch3xp/proton.wcp"
                val magic = activity.assets.open(asset).use { it.read() }
                val compression = if (magic == 0xfd) TarCompressorUtils.Type.XZ else TarCompressorUtils.Type.ZSTD
                check(TarCompressorUtils.extract(compression, activity, asset, stage) && complete(stage)) {
                    "Could not unpack the bundled Proton binaries and Windows prefix."
                }
                Files.move(stage.toPath(), finalDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (stage.exists()) com.winlator.star.core.FileUtils.delete(stage)
            }
        }
        repairProtonChildLoaders(finalDir)
        contents.syncContents()
        val selected = WineInfo.fromIdentifier(activity, contents, entry)
        check(selected.path == finalDir.path && selected.arch == "x86_64") {
            "Bannerlator could not select the bundled x86 Wine layer."
        }
        return contents to entry
    }

    private fun repairProtonChildLoaders(layer: File) {
        // This x86_64-only Proton build still looks for these two AMD64 child
        // loaders in i386-unix. Point those names at the shipped binaries.
        val wineLib = File(layer, "lib/wine")
        val childDir = File(wineLib, "i386-unix")
        Files.createDirectories(childDir.toPath())
        for (name in listOf("wine64", "wine64-preloader")) {
            val alias = File(childDir, name).toPath()
            val loader = File(wineLib, "x86_64-unix/$name").toPath()
            check(Files.isRegularFile(loader) && Files.isExecutable(loader)) {
                "The bundled Proton layer is missing executable $name."
            }
            if (!Files.exists(alias, NOFOLLOW_LINKS)) {
                Files.createSymbolicLink(alias, Paths.get("../x86_64-unix", name))
            }
            check(Files.isExecutable(alias)) { "Could not initialise Proton's child loader $name." }
        }
    }

    private fun installGame(container: Container, importUri: Uri?): Shortcut {
        val driveC = File(container.rootDir, ".wine/drive_c")
        val game = File(driveC, "CH3XP")
        val executable = File(game, "Shkxp.exe")
        if (!game.exists()) {
            checkNotNull(importUri) { "Import your game ZIP before starting the game." }
            val staging = File(driveC, ".ch3xp-install")
            if (staging.exists()) check(staging.deleteRecursively()) { "Could not clear the interrupted setup." }
            check(staging.mkdir()) { "Could not open the game installation folder." }
            try {
                val input = activity.contentResolver.openInputStream(importUri)
                    ?: error("Could not open the selected ZIP.")
                val imported = input.use { Ch3xpPack.unpack(it, staging) }
                for (name in OVERRIDES) {
                    imported.listFiles()?.firstOrNull { it.name != name && it.name.equals(name, ignoreCase = true) }
                        ?.let { check(it.delete()) { "Could not replace ${it.name}." } }
                    activity.assets.open("ch3xp/patch/$name").use { input ->
                        File(imported, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                Files.move(imported.toPath(), game.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                if (staging.exists()) staging.deleteRecursively()
            }
        }
        check((OVERRIDES + "Sh3DiskImg.dat").all { File(game, it).isFile }) {
            "The installed CH3XP files are incomplete."
        }
        val desktopDir = container.desktopDir
        check(desktopDir.isDirectory || desktopDir.mkdirs()) { "Could not open the game shortcut folder." }
        val desktop = File(desktopDir, "$TITLE.desktop")
        if (!desktop.isFile) {
            val controls = InputControlsManager(activity)
            val profile = controls.profiles.firstOrNull { it.name == TOUCH_PROFILE }
                ?: activity.assets.open("ch3xp/CH3XP_Touch_Controls.icp").bufferedReader().use {
                    controls.importProfile(JSONObject(it.readText()))
                }
                ?: error("The bundled touch controls could not be installed.")
            val windowsPath = WinePath.resolveWindowsPath(container, executable.absolutePath)
            val temporary = File(desktopDir, ".ch3xp-shortcut")
            temporary.writeText(
                "[Desktop Entry]\n" +
                    "Name=$TITLE\n" +
                    "Exec=wine ${WinePath.escapeForExec(windowsPath)}\n" +
                    "Type=Application\n" +
                    "StartupWMClass=explorer\n\n" +
                    "[Extra Data]\n" +
                    "storeSource=custom\n" +
                    "screenSize=800x600\n" +
                    "controlsProfile=${profile.id}\n" +
                    "displayBackend=x11\n" +
                    "envVars=WINEDLLOVERRIDES=ddraw=n,b\n"
            )
            Files.move(temporary.toPath(), desktop.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        return Shortcut(container, desktop)
    }

}
