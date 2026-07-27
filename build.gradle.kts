import java.time.LocalDateTime

plugins {
  // This is necessary to avoid the plugins to be loaded multiple times in each subproject's classloader.
  kotlin("multiplatform").apply(false)
  kotlin("native.cocoapods").apply(false)
  id("com.android.library").apply(false)
  id("org.jetbrains.compose").apply(false)
  id("org.jetbrains.kotlin.plugin.compose").apply(false)
  id("com.android.application").apply(false)
  id("com.mikepenz.aboutlibraries.plugin").version("11.2.1").apply(false)
}

// ---------------------------------------------------------------------------
// Centralised app versioning
// ---------------------------------------------------------------------------
// Single source of truth for the version stamped into Android, iOS, Desktop,
// Web SW cache key, and the runtime-displayed Kotlin `BuildInfo` object.
//
// Inputs (all optional; last-wins, override -> git-derived -> defaults):
//   -PappVersion.tag         = the git tag being cut (e.g. "v1.3.2"); when
//                              blank, `git describe --tags --exact-match HEAD`
//                              is consulted.
//   -PappVersion.runNumber   = the GHA `github.run_number`; when blank, this
//                              is treated as a local build and a wall-clock
//                              stamp is used instead so the SW cache still
//                              busts across rebuilds.
//   -PappVersion.releaseName = human codename ("Ammonite"). When blank, we
//                              walk history via `git log --grep=^ReleaseName:`
//                              so the name stays sticky across commits that
//                              don't re-declare it.
//
// Exposed as individual primitive extra properties on the root project so
// subproject build scripts can read them without cross-classloader casting
// pain (each build script has its own classloader; a shared data class would
// not be assignable across them).
//
// Consumed by:
//   - androidApp/build.gradle.kts (versionCode, versionName)
//   - desktopApp/build.gradle.kts (version, packageVersion)
//   - webApp/build.gradle.kts     (SW APP_VERSION -> CACHE_NAME)
//   - shared/build.gradle.kts     (stampIosInfoPlist, generateBuildInfoKt)
// ---------------------------------------------------------------------------

run {
  val tagOverride = providers.gradleProperty("appVersion.tag").orNull.orEmpty().trim()
  val runNumberStr = providers.gradleProperty("appVersion.runNumber").orNull.orEmpty().trim()
  val releaseNameOverride = providers.gradleProperty("appVersion.releaseName").orNull.orEmpty().trim()

  val gitTagFallback = if (tagOverride.isEmpty()) {
    providers.exec {
      commandLine("git", "describe", "--tags", "--exact-match", "HEAD")
      isIgnoreExitValue = true
    }.standardOutput.asText.orNull.orEmpty().trim()
  } else ""

  val releaseNameFallback = if (releaseNameOverride.isEmpty()) {
    providers.exec {
      commandLine("git", "log", "--grep=^ReleaseName:", "-1", "--pretty=%B")
      isIgnoreExitValue = true
    }.standardOutput.asText.orNull.orEmpty().lineSequence()
      .firstOrNull { it.startsWith("ReleaseName:") }
      ?.substringAfter(":")
      ?.trim()
      .orEmpty()
  } else ""

  val tag = (tagOverride.ifEmpty { gitTagFallback }).removePrefix("v").trim()
  val releaseName = (releaseNameOverride.ifEmpty { releaseNameFallback })
    .takeIf { it.isNotEmpty() }

  val isLocal = runNumberStr.isEmpty()

  val now = LocalDateTime.now()
  val datePart = "%04d.%02d.%02d".format(now.year, now.monthValue, now.dayOfMonth)
  val buildId = if (isLocal) {
    "$datePart-%02d%02d%02d".format(now.hour, now.minute, now.second)
  } else {
    "$datePart-$runNumberStr"
  }

  val versionName = if (tag.isNotEmpty()) tag else buildId
  val versionCode = runNumberStr.toIntOrNull()
    ?: ((System.currentTimeMillis() / 1000L / 60L).toInt())

  // Compose Desktop's packageVersion is strict MAJOR.MINOR.PATCH — the
  // MSI target additionally caps `build <= 65535`. Prefer the tag when it
  // parses as semver; otherwise synthesise `1.0.<runNumber % 65536>` for
  // CI, or fall back to `1.0.0` locally (installers aren't built locally
  // in the normal dev loop).
  val desktopPackageVersion = when {
    tag.matches(Regex("""^\d+\.\d+\.\d+(\..*)?$""")) -> tag.substringBefore('-').take(50)
    !isLocal -> "1.0.${(runNumberStr.toIntOrNull() ?: 0) % 65536}"
    else -> "1.0.0"
  }

  val displayString = buildString {
    append("v").append(versionName)
    if (versionName != buildId) {
      append(" (build ").append(buildId).append(")")
    }
    if (releaseName != null) {
      append(" \"").append(releaseName).append("\"")
    }
  }

  extra["app.versionName"] = versionName
  extra["app.versionCode"] = versionCode
  extra["app.releaseName"] = releaseName            // String? — null when absent
  extra["app.buildId"] = buildId
  extra["app.displayString"] = displayString
  extra["app.desktopPackageVersion"] = desktopPackageVersion
  extra["app.isLocal"] = isLocal
}
