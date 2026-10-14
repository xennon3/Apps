package com.xennon3.dashplay

import android.content.Context
import androidx.webkit.WebViewAssetLoader
import java.io.File

/**
 * Dashplay's own pages live at one https address that never touches the network:
 * bundled pages under /assets/ and tools downloaded from the store under /store/.
 * Sharing an origin lets downloaded tools use the same stylesheet and scripts, and
 * gives every page working localStorage.
 */
object Local {
    const val ORIGIN = "https://${WebViewAssetLoader.DEFAULT_DOMAIN}"
    const val ASSETS = "$ORIGIN/assets"
    const val STORE = "$ORIGIN/store"

    fun storeDir(context: Context) = File(context.filesDir, "store").apply { mkdirs() }

    fun isLocal(url: String) = url.startsWith("$ORIGIN/")

    /** A real website, as opposed to one of Dashplay's own pages. */
    fun isWeb(url: String) = (url.startsWith("http://") || url.startsWith("https://")) && !isLocal(url)

    fun loader(context: Context): WebViewAssetLoader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/store/", WebViewAssetLoader.InternalStoragePathHandler(context, storeDir(context)))
        .build()
}
