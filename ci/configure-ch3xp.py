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
replace("app/build.gradle", 'versionName "3.2.2"', 'versionName "2.0.0-preview3"')
replace("app/build.gradle", "        versionCode 109", "        versionCode 111")
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
