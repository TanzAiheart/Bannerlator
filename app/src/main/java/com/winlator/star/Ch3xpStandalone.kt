package com.winlator.star

import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.contents.ContentsManager
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.resume

internal class Ch3xpStandalone(private val activity: MainActivity) {
    private val status = TextView(activity)
    private val progress = ProgressBar(activity)
    private val play = Button(activity)

    fun show() {
        val page = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
            addView(status.apply { gravity = Gravity.CENTER; textSize = 18f })
            addView(progress)
            addView(play.apply { isAllCaps = false; setOnClickListener { prepareAndLaunch() } })
        }
        activity.setContentView(page)
        prepareAndLaunch()
    }

    private fun prepareAndLaunch() {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        play.isEnabled = false
        play.text = "Play Chivalrous Heroes XP"
        progress.visibility = View.VISIBLE
        status.text = TITLE
        activity.lifecycleScope.launch {
            try {
                installRuntime()
                val container = getOrCreateContainer()
                status.text = "Preparing Chivalrous Heroes XP…"
                val shortcut = withContext(Dispatchers.IO) { installGame(container) }
                status.text = TITLE
                launchShortcutNow(activity, shortcut)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.e("CH3XP", "Could not start the game", error)
                status.text = error.message ?: "Could not start Chivalrous Heroes XP."
                play.text = "Retry"
            } finally {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.GONE
                play.isEnabled = true
            }
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
        manager.containers.firstOrNull { it.name == TITLE }?.let { return it }
        status.text = "Creating the Windows game container…"
        val contents = withContext(Dispatchers.IO) { ContentsManager(activity).apply { syncContents() } }
        val settings = JSONObject()
            .put("name", TITLE)
            .put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier())
            .put("screenSize", "800x600")
            .put("dxwrapper", "wined3d")
            .put("drives", "")
        return suspendCancellableCoroutine<Container?> { continuation ->
            manager.createContainerAsync(settings, contents) { container ->
                if (continuation.isActive) continuation.resume(container)
            }
        } ?: error("The bundled Wine prefix could not be created.")
    }

    private fun installGame(container: Container): Shortcut {
        val driveC = File(container.rootDir, ".wine/drive_c")
        val game = File(driveC, "CH3XP")
        val executable = File(game, "Shkxp.exe")
        if (!game.exists()) {
            val staging = File(driveC, ".ch3xp-install")
            if (staging.exists()) check(staging.deleteRecursively()) { "Could not clear the interrupted setup." }
            check(staging.mkdir()) { "Could not open the game installation folder." }
            try {
                activity.assets.open("ch3xp/game.pack").use { Ch3xpPack.unpack(it, staging) }
                for (name in OVERRIDES) {
                    activity.assets.open("ch3xp/patch/$name").use { input ->
                        File(staging, name).outputStream().use { output -> input.copyTo(output) }
                    }
                }
                Files.move(staging.toPath(), game.toPath(), StandardCopyOption.ATOMIC_MOVE)
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

    private companion object {
        const val TITLE = "Chivalrous Heroes XP"
        const val TOUCH_PROFILE = "CH3XP Touch Controls"
        val OVERRIDES = listOf("Shkxp.exe", "Shkxp.exe.manifest", "chxp-sdl3.dll", "SDL3.dll",
            "SDL3-LICENSE.txt", "ddraw.dll", "ddraw.ini")
    }
}
