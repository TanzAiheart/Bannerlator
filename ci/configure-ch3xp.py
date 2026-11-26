from pathlib import Path


def replace(path: str, source: str, replacement: str) -> None:
    file = Path(path)
    contents = file.read_text()
    if contents.count(source) != 1:
        raise SystemExit(f"{path}: expected one match for {source!r}")
    file.write_text(contents.replace(source, replacement, 1))


replace("app/build.gradle", 'applicationId "com.winlator.banner"', 'applicationId "com.ch3xp.banner.v2"')
replace("app/build.gradle", 'resValue "string", "app_name", "Bannerlator Bionic"',
        'resValue "string", "app_name", "Chivalrous Heroes XP"')
replace("app/build.gradle", 'versionName "3.2.2"', 'versionName "2.0.0-preview4"')
replace("app/build.gradle", "        versionCode 109", "        versionCode 112")
replace(
    "app/src/main/java/com/winlator/star/MainActivity.kt",
    "        val selectedMenuItemId = intent.getIntExtra",
    """        if (BuildConfig.APPLICATION_ID == "com.ch3xp.banner.v2") {
            Ch3xpStandalone(this).show()
            contentReady = true
            return
        }

        val selectedMenuItemId = intent.getIntExtra""",
)


# Keep a source location on the failure screen so a phone test can identify later launch errors.
replace(
    "app/src/main/java/com/winlator/star/XServerDisplayActivity.java",
    """                    final String stageName = stage[0];
                    final String detail = e.getMessage();""",
    """                    final String stageName = stage[0];
                    final StackTraceElement[] frames = e.getStackTrace();
                    final String location = frames.length == 0 ? "" : "\\n" + frames[0];
                    final String detail = e + location;""",
)


display = "app/src/main/java/com/winlator/star/XServerDisplayActivity.java"
replace(
    display,
    '        envVars.put("WINEDEBUG", wineDebugValue);',
    '''        if ("com.ch3xp.banner.v2".equals(BuildConfig.APPLICATION_ID)) wineDebugValue = "err+all";
        envVars.put("WINEDEBUG", wineDebugValue);''',
)
replace(
    display,
    '''            File logDir = isLaunchLoggingEnabled()
                    ? com.winlator.star.core.LogLocation.resolveGameLogDir(this, currentLogGameName())
                    : null;''',
    '''            boolean ch3xp = "com.ch3xp.banner.v2".equals(BuildConfig.APPLICATION_ID);
            File logDir = ch3xp ? getFilesDir() : isLaunchLoggingEnabled()
                    ? com.winlator.star.core.LogLocation.resolveGameLogDir(this, currentLogGameName())
                    : null;''',
)
replace(display, 'if (!logDir.equals(flatLogRoot)) {', 'if (!ch3xp && !logDir.equals(flatLogRoot)) {')
replace(
    display,
    '                File logFile = new File(logDir, "wine_debug.log");',
    '                File logFile = new File(logDir, ch3xp ? "ch3xp-last-launch.txt" : "wine_debug.log");',
)
replace(
    display,
    '                    if (wineDebugWriter != null) {\n                        wineDebugWriter.println(com.winlator.star.core.LogcatCapture.redact(line));',
    '                    if (wineDebugWriter != null && (!ch3xp || logFile.length() < 256 * 1024)) {\n                        wineDebugWriter.println(com.winlator.star.core.LogcatCapture.redact(line));',
)
replace(
    display,
    '''                final String logDir = com.winlator.star.core.LogLocation.resolveLogDir(this).getAbsolutePath();
                runOnUiThread(() -> {
                    cancelLaunchTimers();
                    preloaderDialog.fail(
                            "Launching Windows",
                            "The game exited before rendering",
                            "exit code " + status,''',
    '''                final String logDir = com.winlator.star.core.LogLocation.resolveLogDir(this).getAbsolutePath();
                if ("com.ch3xp.banner.v2".equals(BuildConfig.APPLICATION_ID))
                    ProcessHelper.reportDebugLine("Guest process exit code: " + status);
                runOnUiThread(() -> {
                    cancelLaunchTimers();
                    preloaderDialog.fail(
                            "Launching Windows",
                            "The game exited before rendering",
                            "exit code " + status + ("com.ch3xp.banner.v2".equals(BuildConfig.APPLICATION_ID)
                                    ? "\\nUse Copy launch report on the launcher." : ""),''',
)
