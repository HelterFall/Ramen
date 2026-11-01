package io.github.helterfall.ramen.runtime.html5

import android.net.Uri
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebResourceErrorCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.helterfall.ramen.library.GameRecord
import io.github.helterfall.ramen.library.LibraryRepository
import java.io.ByteArrayInputStream
import java.io.File

class Html5PlayerActivity : ComponentActivity() {
    private var playerWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val gameId = intent.getStringExtra(EXTRA_GAME_ID)
        val game = gameId?.let { id ->
            LibraryRepository(applicationContext).load().firstOrNull { it.id == id }
        }

        if (game == null || !isLaunchableGame(game)) {
            finish()
            return
        }

        val gameDirectory = File(filesDir, game.relativeDirectory)
        val gamesRoot = File(filesDir, "games")
        val safeDirectory = runCatching { gameDirectory.canonicalFile }.getOrNull()
        val safeGamesRoot = runCatching { gamesRoot.canonicalFile }.getOrNull()
        val indexFile = safeDirectory?.let { File(it, "index.html") }

        val directoryIsContained = safeDirectory != null &&
            safeGamesRoot != null &&
            safeDirectory.toPath().startsWith(safeGamesRoot.toPath()) &&
            safeDirectory != safeGamesRoot

        val entryPointIsContained = safeDirectory != null &&
            indexFile != null &&
            runCatching { indexFile.canonicalFile.toPath().startsWith(safeDirectory.toPath()) }
                .getOrDefault(false) &&
            indexFile.isFile

        if (!directoryIsContained || !entryPointIsContained) {
            finish()
            return
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFF2B766),
                background = Color(0xFF101216),
                surface = Color(0xFF1A1E25)
            )) {
                Html5PlayerScreen(
                    game = game,
                    gameDirectory = safeDirectory!!,
                    onExit = ::finish,
                    onWebViewCreated = { playerWebView = it }
                )
            }
        }
    }

    override fun onPause() {
        playerWebView?.onPause()
        playerWebView?.pauseTimers()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        playerWebView?.onResume()
        playerWebView?.resumeTimers()
    }

    override fun onDestroy() {
        playerWebView?.let { view ->
            view.stopLoading()
            view.webChromeClient = WebChromeClient()
            view.webViewClient = android.webkit.WebViewClient()
            view.removeAllViews()
            view.destroy()
        }
        playerWebView = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME_ID = "io.github.helterfall.ramen.extra.GAME_ID"

        private fun isLaunchableGame(game: GameRecord): Boolean =
            game.id.matches(Regex("[A-Za-z0-9-]{1,64}")) &&
                game.relativeDirectory == "games/${game.id}" &&
                game.engineId == "html5" &&
                game.compatibilityStatus == "SUPPORTED_LIMITED"
    }
}

@Composable
private fun Html5PlayerScreen(
    game: GameRecord,
    gameDirectory: File,
    onExit: () -> Unit,
    onWebViewCreated: (WebView?) -> Unit
) {
    val context = LocalContext.current
    var runtimeError by remember(game.id) { mutableStateOf<String?>(null) }
    var runtimeNotice by remember(game.id) {
        mutableStateOf("Офлайн-режим: внешние адреса и сетевые запросы заблокированы.")
    }

    BackHandler(onBack = onExit)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF101216)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1E25))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.title,
                        color = Color(0xFFF4F1EA),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Text(
                        text = "HTML5 • локальный запуск",
                        color = Color(0xFFA8ADB7),
                        fontSize = 11.sp
                    )
                }
                TextButton(onClick = onExit) {
                    Text("Закрыть")
                }
            }

            if (runtimeError != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Не удалось запустить проект",
                        color = Color(0xFFF4F1EA),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = runtimeError.orEmpty(),
                        color = Color(0xFFA8ADB7),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                    Button(
                        onClick = onExit,
                        modifier = Modifier.padding(top = 18.dp)
                    ) {
                        Text("Вернуться в библиотеку")
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    factory = { viewContext ->
                        runCatching {
                            createRestrictedWebView(
                                context = viewContext,
                                game = game,
                                gameDirectory = gameDirectory,
                                onMainFrameError = { message -> runtimeError = message },
                                onNavigationBlocked = { host ->
                                    runtimeNotice = if (host.isNullOrBlank()) {
                                        "Внешний переход заблокирован."
                                    } else {
                                        "Переход на $host заблокирован: доступна только локальная игра."
                                    }
                                }
                            )
                        }.getOrElse { error ->
                            WebView(viewContext).also { fallback ->
                                fallback.post {
                                    runtimeError = error.message
                                        ?: "Эта версия Android System WebView не поддерживает изоляцию данных игр."
                                }
                            }
                        }.also(onWebViewCreated)
                    }
                )
            }

            Text(
                text = runtimeNotice,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1A1E25))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                color = Color(0xFFA8ADB7),
                fontSize = 10.sp
            )
        }
    }
}

private fun createRestrictedWebView(
    context: android.content.Context,
    game: GameRecord,
    gameDirectory: File,
    onMainFrameError: (String) -> Unit,
    onNavigationBlocked: (String?) -> Unit
): WebView {
    check(WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
        "Нужна версия Android System WebView с поддержкой отдельных профилей для каждой игры."
    }

    val webView = WebView(context)

    // Each game receives a separate browser profile, including isolated localStorage.
    WebViewCompat.setProfile(webView, "ramen-${game.id}")

    val assetLoader = WebViewAssetLoader.Builder()
        .addPathHandler(
            GAME_PATH_PREFIX,
            WebViewAssetLoader.InternalStoragePathHandler(context, gameDirectory)
        )
        .build()

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = false
        allowFileAccess = false
        allowContentAccess = false
        allowFileAccessFromFileURLs = false
        allowUniversalAccessFromFileURLs = false
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        mediaPlaybackRequiresUserGesture = true
        blockNetworkLoads = true
    }

    webView.webViewClient = object : WebViewClientCompat() {
        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest
        ): WebResourceResponse? {
            val url = request.url

            // Embedded images and audio use data: URIs inside some standalone HTML games.
            // Let WebView decode these document-contained resources itself; this does not
            // enable network access. All other non-appassets schemes remain blocked below.
            if (url.scheme.equals("data", ignoreCase = true)) {
                return null
            }

            if (!isAllowedGameUrl(url)) {
                return blockedResponse()
            }
            return assetLoader.shouldInterceptRequest(url) ?: blockedResponse()
        }

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            if (isAllowedGameUrl(request.url)) return false
            onNavigationBlocked(request.url.host)
            return true
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceErrorCompat
        ) {
            if (request.isForMainFrame) {
                onMainFrameError("Ошибка загрузки локального проекта: ${error.description}")
            }
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            response: WebResourceResponse
        ) {
            if (request.isForMainFrame && response.statusCode >= 400) {
                onMainFrameError("Локальный проект вернул ошибку HTTP ${response.statusCode}.")
            }
        }

        override fun onRenderProcessGone(
            view: WebView,
            detail: RenderProcessGoneDetail
        ): Boolean {
            onMainFrameError("Процесс WebView завершился. Вернись в библиотеку и попробуй снова.")
            return true
        }
    }

    webView.webChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: android.webkit.GeolocationPermissions.Callback
        ) {
            callback.invoke(origin, false, false)
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean = false
    }

    webView.loadUrl(ENTRY_POINT_URL)
    return webView
}

private fun isAllowedGameUrl(uri: Uri): Boolean {
    if (
        uri.scheme != "https" ||
        !uri.host.equals(ASSET_DOMAIN, ignoreCase = true) ||
        uri.port != -1 ||
        uri.userInfo != null
    ) {
        return false
    }

    val path = uri.path ?: return false
    if (!path.startsWith(GAME_PATH_PREFIX)) return false

    val relativePath = path.removePrefix(GAME_PATH_PREFIX)
    if (relativePath.isBlank()) return false

    return relativePath.split('/').all { segment ->
        segment.isNotBlank() &&
            segment != "." &&
            segment != ".." &&
            '\\' !in segment &&
            segment.none { it.isISOControl() }
    }
}

private fun blockedResponse() = WebResourceResponse(
    "text/plain",
    "UTF-8",
    403,
    "Forbidden",
    mapOf("Cache-Control" to "no-store"),
    ByteArrayInputStream("Blocked by Ramen local-only policy.".toByteArray())
)

private const val ASSET_DOMAIN = "appassets.androidplatform.net"
private const val GAME_PATH_PREFIX = "/game/"
private const val ENTRY_POINT_URL = "https://appassets.androidplatform.net/game/index.html"
