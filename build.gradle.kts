import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import javax.inject.Inject
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations

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

  // Both fallbacks go through `SafeGitCommand` (defined below) because
  // `providers.exec` propagates startup failures both at initial read AND
  // during configuration-cache store — where an outer `runCatching` cannot
  // reach the re-invocation of the internal `ProcessOutputValueSource`.
  // Per Gradle 9 docs, exception handling belongs *inside* a ValueSource's
  // `obtain()`. Inside the Docker builder the `git` binary is absent and
  // `.git` is excluded from the context; SafeGitCommand catches the exec
  // failure and returns "" so the surrounding code takes the wall-clock /
  // buildId path.
  val gitTagFallback = if (tagOverride.isEmpty()) {
    providers.of(SafeGitCommand::class.java) {
      parameters.args.set(listOf("git", "describe", "--tags", "--exact-match", "HEAD"))
    }.get().trim()
  } else ""

  val releaseNameFallback = if (releaseNameOverride.isEmpty()) {
    providers.of(SafeGitCommand::class.java) {
      parameters.args.set(listOf("git", "log", "--grep=^ReleaseName:", "-1", "--pretty=%B"))
    }.get().lineSequence()
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

// ---------------------------------------------------------------------------
// SafeGitCommand — a ValueSource that runs a git subprocess and returns its
// stdout, swallowing any startup / exit failure as an empty string.
// ---------------------------------------------------------------------------
// Why a ValueSource and not `providers.exec`:
//
// `providers.exec { ... }` in Gradle 9 is backed by an internal
// `ProcessOutputValueSource`. When configuration cache is enabled (see
// `org.gradle.configuration-cache=true` in gradle.properties) Gradle invokes
// the ValueSource at least twice per build — once at configuration-read time,
// then again at configuration-cache-store time. An outer `runCatching`
// around the `.standardOutput.asText.orNull` call only intercepts the first
// invocation; the store-time re-invocation raises
// `ProcessExecutionException: A problem occurred starting process 'command 'git''`
// unguarded, and fails the whole build. Handling the exception INSIDE
// `obtain()` (as Gradle's docs prescribe) ensures every invocation returns a
// safe, serialisable String, so both the read and the store phases succeed
// in environments without a `git` binary (the Wasm Docker builder in CI).
// ---------------------------------------------------------------------------
abstract class SafeGitCommand : ValueSource<String, SafeGitCommand.Params> {
  interface Params : ValueSourceParameters {
    val args: ListProperty<String>
  }

  @get:Inject
  abstract val execOperations: ExecOperations

  override fun obtain(): String {
    return try {
      val stdout = ByteArrayOutputStream()
      execOperations.exec {
        commandLine(parameters.args.get())
        standardOutput = stdout
        errorOutput = ByteArrayOutputStream()
        isIgnoreExitValue = true
      }
      stdout.toString(Charsets.UTF_8.name())
    } catch (_: Exception) {
      ""
    }
  }
}
