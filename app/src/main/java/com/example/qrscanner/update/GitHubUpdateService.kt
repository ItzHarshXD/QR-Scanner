package com.example.qrscanner.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class GitHubUpdateService(
    private val context: Context
) {
    companion object {
        // GitHub Releases source for in-app updates.
        private const val GITHUB_OWNER = "ItzHarshXD"
        private const val GITHUB_REPO = "QR-Scanner"
        private const val PREFS_NAME = "updater_prefs"
        private const val KEY_LAST_LATER_APP_VERSION_CODE = "last_later_app_version_code"
        private const val MAX_NOTES_LENGTH = 280
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun checkLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
            val body = executeGetWithRetry(url)
            val json = JSONObject(body)
            val tagName = json.optString("tag_name").trim()
            if (tagName.isBlank()) {
                error("GitHub release tag is missing.")
            }

            val releaseNotes = json.optString("body").orEmpty().trim()
            val shortNotes = releaseNotes.take(MAX_NOTES_LENGTH)
                .ifBlank { "No release notes provided." }

            val assets = json.optJSONArray("assets")
                ?: error("GitHub release has no assets.")

            var apkUrl: String? = null
            var apkName: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").orEmpty()
                if (name.endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url").orEmpty()
                    apkName = name
                    break
                }
            }

            if (apkUrl.isNullOrBlank() || apkName.isNullOrBlank()) {
                error("Release is missing a valid APK asset.")
            }

            ReleaseInfo(
                tag = tagName,
                shortNotes = shortNotes,
                apkUrl = apkUrl,
                apkFileName = apkName
            )
        }
    }

    fun isUpdateAvailable(installedVersionName: String, latestTag: String): Boolean {
        val installed = parseVersion(installedVersionName)
        val latest = parseVersion(latestTag)
        if (installed.isEmpty() || latest.isEmpty()) return false

        val size = maxOf(installed.size, latest.size)
        for (index in 0 until size) {
            val currentPart = installed.getOrElse(index) { 0 }
            val latestPart = latest.getOrElse(index) { 0 }
            if (latestPart > currentPart) return true
            if (latestPart < currentPart) return false
        }
        return false
    }

    fun getInstalledVersionName(): String {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "0.0.0"
        } catch (_: PackageManager.NameNotFoundException) {
            "0.0.0"
        }
    }

    fun shouldAutoPrompt(): Boolean {
        val installedVersionCode = getInstalledVersionCode()
        val suppressedForVersion = prefs.getLong(KEY_LAST_LATER_APP_VERSION_CODE, -1L)
        return suppressedForVersion != installedVersionCode
    }

    fun markLaterForCurrentAppVersion() {
        prefs.edit()
            .putLong(KEY_LAST_LATER_APP_VERSION_CODE, getInstalledVersionCode())
            .apply()
    }

    suspend fun downloadApk(
        releaseInfo: ReleaseInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(releaseInfo.apkUrl)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("Download failed with HTTP ${response.code}.")
                }
                val body = response.body ?: error("APK response body is empty.")
                val totalBytes = body.contentLength()
                val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val outputFile = File(updatesDir, releaseInfo.apkFileName)
                if (outputFile.exists()) outputFile.delete()

                body.byteStream().use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var bytesRead = input.read(buffer)
                        while (bytesRead >= 0) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalBytes > 0) {
                                val progress = ((downloaded * 100) / totalBytes).toInt()
                                onProgress(progress.coerceIn(0, 100))
                            }
                            bytesRead = input.read(buffer)
                        }
                    }
                }
                if (!outputFile.exists() || outputFile.length() <= 0L) {
                    error("Downloaded APK is invalid.")
                }
                outputFile
            }
        }
    }

    fun launchInstaller(apkFile: File): Result<Unit> = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(settingsIntent)
            error("Enable install unknown apps permission and retry install.")
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }.recoverCatching { throwable ->
        if (throwable is ActivityNotFoundException) {
            throw IllegalStateException("No installer found on this device.")
        }
        throw throwable
    }

    private suspend fun executeGetWithRetry(url: String): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("GitHub API error HTTP ${response.code}.")
                    }
                    return response.body?.string().orEmpty()
                }
            } catch (error: Throwable) {
                lastError = error
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }
        throw IOException(lastError?.message ?: "Failed to call GitHub API.")
    }

    private fun getInstalledVersionCode(): Long {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            PackageInfoCompat.getLongVersionCode(info)
        } catch (_: PackageManager.NameNotFoundException) {
            -1L
        }
    }

    private fun parseVersion(value: String): List<Int> {
        return value
            .trim()
            .removePrefix("v")
            .split(".")
            .mapNotNull { token ->
                token.takeWhile { it.isDigit() }.toIntOrNull()
            }
    }
}

data class ReleaseInfo(
    val tag: String,
    val shortNotes: String,
    val apkUrl: String,
    val apkFileName: String
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpdateAvailable(val releaseInfo: ReleaseInfo) : UpdateState
    data object NoUpdate : UpdateState
    data class Downloading(val progress: Int) : UpdateState
    data class DownloadFailed(val message: String) : UpdateState
    data class InstallReady(val apkFile: File, val releaseInfo: ReleaseInfo) : UpdateState
    data class InstallFailed(val message: String) : UpdateState
}
