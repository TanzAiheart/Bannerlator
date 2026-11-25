package com.winlator.star

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.winlator.star.androidgames.AndroidGames
import com.winlator.star.container.Container
import com.winlator.star.container.ContainerManager
import com.winlator.star.container.Shortcut
import com.winlator.star.container.ShortcutOrder
import com.winlator.star.core.WinePath
import com.winlator.star.linux.LinuxSettings
import com.winlator.star.ui.screens.launchShortcutNow
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.xenvironment.ImageFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * One-tap "Chivalrous Heroes XP" launcher (its own icon in the app drawer).
 *
 *  - First run: pick the game pack zip (CH3XP-vanilla-*.zip). The zip is unpacked into the chosen Wine
 *    container's C: drive (C:\CH3XP), a library shortcut "Chivalrous Heroes XP" is written (640x480), and the
 *    game starts.
 *  - Every later run: the shortcut already exists, so this opens the game straight away.
 *
 * Re-importing unpacks over the existing folder and never deletes anything, so saves are kept.
 */
class Ch3xpImportActivity : ComponentActivity() {

    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var playButton: Button
    private var busy = false

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) startImport(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppThemeState.init(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        if (!busy) refresh(autoPlay = true)
    }

    private fun buildUi() {
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        title = TextView(this).apply { text = "Chivalrous Heroes XP"; textSize = 24f; gravity = Gravity.CENTER }
        status = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, pad / 2, 0, pad / 2) }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        playButton = Button(this).apply { text = "Play"; setOnClickListener { play() } }
        pickButton = Button(this).apply {
            text = "Choose game pack zip…"
            setOnClickListener { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) }
        }
        root.addView(title)
        root.addView(status)
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(playButton)
        root.addView(pickButton)
        setContentView(root)
    }

    /** Shows the right buttons; with [autoPlay] an already-imported game is started at once. */
    private fun refresh(autoPlay: Boolean) {
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) { setupProblem() }
            val shortcut = if (problem == null) withContext(Dispatchers.IO) { findShortcut() } else null
            progress.visibility = View.GONE
            if (problem != null) {
                status.text = problem
                playButton.visibility = View.GONE
                pickButton.visibility = View.GONE
                return@launch
            }
            pickButton.setOnClickListener {
                picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            if (shortcut != null) {
                status.text = "Game is installed."
                playButton.visibility = View.VISIBLE
                pickButton.visibility = View.VISIBLE
                pickButton.text = "Re-import game pack…"
                if (autoPlay && !intent.getBooleanExtra(EXTRA_STAY, false)) {
                    intent.putExtra(EXTRA_STAY, true)   // back from the game lands on the menu, not a relaunch loop
                    play()
                }
            } else {
                status.text = "Pick the CH3XP game pack zip. It is unpacked into the game container once; after that this icon starts the game."
                playButton.visibility = View.GONE
                pickButton.visibility = View.VISIBLE
                pickButton.text = "Choose game pack zip…"
            }
        }
    }

    /** Null when Bannerlator is ready to take the game, else what the user has to do first. BLOCKING. */
    private fun setupProblem(): String? {
        if (!ImageFs.find(this).isValid()) {
            return "Bannerlator isn't set up yet. Open Bannerlator once and let it finish setting up, then come back."
        }
        if (containers().isEmpty()) {
            return "No container yet. In Bannerlator, create a container (the defaults are fine), then come back."
        }
        return null
    }

    private fun containers(): List<Container> {
        val manager = runCatching { ContainerManager(this) }.getOrNull() ?: return emptyList()
        return manager.containers.orEmpty().filter { !LinuxSettings.isLinuxContainer(it) && !AndroidGames.isHome(it) }
    }

    /** The container the game lives in (the one with our shortcut, else the remembered one, else the first). */
    private fun pickContainer(): Container? {
        val all = containers()
        all.firstOrNull { File(it.desktopDir, "$GAME_NAME.desktop").isFile }?.let { return it }
        val remembered = PreferenceManager.getDefaultSharedPreferences(this).getInt(PREF_CONTAINER, -1)
        return all.firstOrNull { it.id == remembered } ?: all.firstOrNull()
    }

    private fun gameDir(container: Container) = File(container.rootDir, ".wine/drive_c/$GAME_DIR")

    /** Our library entry, but only if the unpack finished and the exe is there. BLOCKING. */
    private fun findShortcut(): Shortcut? {
        for (c in containers()) {
            val desktop = File(c.desktopDir, "$GAME_NAME.desktop")
            if (!desktop.isFile) continue
            val dir = gameDir(c)
            if (!File(dir, MARKER).isFile || !File(dir, "Shkxp.exe").isFile) continue
            return runCatching { Shortcut(c, desktop) }.getOrNull()
        }
        return null
    }

    private fun play() {
        lifecycleScope.launch {
            val shortcut = withContext(Dispatchers.IO) { findShortcut() }
            if (shortcut == null) {
                refresh(autoPlay = false)
                return@launch
            }
            launchShortcutNow(this@Ch3xpImportActivity, shortcut)
        }
    }

    private fun startImport(uri: Uri) {
        if (busy) return
        busy = true
        pickButton.isEnabled = false
        playButton.visibility = View.GONE
        progress.visibility = View.VISIBLE
        progress.progress = 0
        status.text = "Unpacking… keep the screen on until it finishes."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { importPack(uri) { fraction, text ->
                    runOnUiThread {
                        progress.progress = (fraction * 1000).toInt().coerceIn(0, 1000)
                        status.text = text
                    }
                } }
            }
            busy = false
            pickButton.isEnabled = true
            result.onSuccess {
                Toast.makeText(this@Ch3xpImportActivity, "Game installed", Toast.LENGTH_SHORT).show()
                refresh(autoPlay = true)
            }.onFailure { e ->
                progress.visibility = View.GONE
                status.text = "Import failed: ${e.message ?: e.javaClass.simpleName}"
                pickButton.visibility = View.VISIBLE
                pickButton.text = "Try again…"
            }
        }
    }

    /** Unpacks the pack into C:\CH3XP of the game container and writes the shortcut. BLOCKING. */
    private fun importPack(uri: Uri, onProgress: (Float, String) -> Unit) {
        val container = pickContainer() ?: error("No container found")
        val dest = gameDir(container)
        dest.mkdirs()
        val destRoot = dest.canonicalFile
        val total = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
        if (total > 0 && !File(dest, MARKER).isFile && dest.usableSpace < total + (200L shl 20)) {
            error("Not enough free space (need about ${(total shr 20) + 200} MB)")
        }
        File(dest, MARKER).delete()

        var done = 0L
        var lastReport = 0L
        val buffer = ByteArray(1 shl 20)
        val stream = contentResolver.openInputStream(uri) ?: error("Can't read the zip")
        var sawExe = false
        ZipInputStream(BufferedInputStream(stream, 1 shl 20)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val out = File(destRoot, entry.name).canonicalFile
                // Zip-slip guard: nothing may land outside C:\CH3XP.
                if (out.path != destRoot.path && !out.path.startsWith(destRoot.path + File.separator)) {
                    error("Bad path in zip: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos ->
                        while (true) {
                            val n = zin.read(buffer)
                            if (n < 0) break
                            fos.write(buffer, 0, n)
                            done += n
                            if (done - lastReport > (8L shl 20)) {
                                lastReport = done
                                val frac = if (total > 0) (done.toFloat() / total).coerceAtMost(1f) else 0f
                                onProgress(frac, "Unpacking… ${done shr 20} MB" + if (total > 0) " of ${total shr 20} MB" else "")
                            }
                        }
                    }
                    if (out.name.equals("Shkxp.exe", ignoreCase = true) && out.parentFile == destRoot) sawExe = true
                }
                entry = zin.nextEntry
            }
        }
        if (!sawExe) error("That zip has no Shkxp.exe at its top level. Use the CH3XP-vanilla-….zip pack.")

        // The game's compatibility manifest (the pack does not carry it). Kept next to the exe.
        val manifest = File(dest, "Shkxp.exe.manifest")
        if (!manifest.isFile) assets.open("ch3xp/Shkxp.exe.manifest").use { i -> manifest.outputStream().use { o -> i.copyTo(o) } }
        File(dest, MARKER).writeText("ok")

        onProgress(1f, "Creating shortcut…")
        writeShortcut(container)
        PreferenceManager.getDefaultSharedPreferences(this).edit().putInt(PREF_CONTAINER, container.id).apply()
    }

    private fun writeShortcut(container: Container) {
        val desktopDir = container.desktopDir
        if (!desktopDir.exists()) desktopDir.mkdirs()
        val file = File(desktopDir, "$GAME_NAME.desktop")
        if (file.exists()) return   // keep the user's per-game settings on a re-import
        val exe = File(gameDir(container), "Shkxp.exe")
        val winPath = WinePath.resolveWindowsPath(container, exe.absolutePath)
        val content = buildString {
            append("[Desktop Entry]\n")
            append("Name=").append(GAME_NAME).append("\n")
            append("Exec=wine ").append(WinePath.escapeForExec(winPath)).append("\n")
            append("Icon=").append(GAME_NAME).append("\n")
            append("Type=Application\n")
            append("StartupWMClass=explorer\n")
            append("\n")
            append("[Extra Data]\n")
            append("storeSource=custom\n")
            append(ShortcutOrder.addedAtLine(System.currentTimeMillis()))
            append("screenSize=640x480\n")
        }
        file.writeText(content)
    }

    companion object {
        private const val GAME_NAME = "Chivalrous Heroes XP"
        private const val GAME_DIR = "CH3XP"
        private const val MARKER = ".bannerlator_import_ok"
        private const val PREF_CONTAINER = "ch3xp_container_id"
        private const val EXTRA_STAY = "ch3xp_started"
    }
}
