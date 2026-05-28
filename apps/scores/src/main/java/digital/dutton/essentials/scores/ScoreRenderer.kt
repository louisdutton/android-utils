package digital.dutton.essentials.scores

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.caverock.androidsvg.SVG
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

interface ScoreRenderer {
    suspend fun render(
        musicXml: ByteArray,
        targetWidthPx: Int,
    ): List<RenderedScorePage>
}

class VerovioScoreRenderer(
    private val context: Context,
) : ScoreRenderer {
    override suspend fun render(
        musicXml: ByteArray,
        targetWidthPx: Int,
    ): List<RenderedScorePage> = withContext(Dispatchers.Default) {
        val xmlText = musicXml.decodeToString()
        val resourcePath = VerovioResources.ensure(context).absolutePath
        val svgPages = VerovioBridge.renderSvgPages(
            musicXml = xmlText,
            resourcePath = resourcePath,
            targetWidthPx = targetWidthPx.coerceIn(320, 2400),
        )
        if (svgPages.isEmpty()) {
            throw IllegalStateException(VerovioBridge.lastDiagnostic().ifBlank { "Verovio did not render any notation." })
        }
        svgPages.mapIndexed { index, svg ->
            renderSvgPage(index, svg, targetWidthPx.coerceIn(320, 2400))
        }
    }

    private fun renderSvgPage(
        index: Int,
        svgText: String,
        targetWidthPx: Int,
    ): RenderedScorePage {
        val svg = SVG.getFromString(svgText)
        val documentWidth = svg.documentWidth.takeIf { it > 0f } ?: targetWidthPx.toFloat()
        val documentHeight = svg.documentHeight.takeIf { it > 0f } ?: (targetWidthPx * 4 / 3f)
        val scale = targetWidthPx / documentWidth
        val targetHeightPx = (documentHeight * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(targetWidthPx, targetHeightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        svg.renderToCanvas(canvas)
        bitmap.prepareToDraw()

        return RenderedScorePage(
            index = index,
            widthPx = bitmap.width,
            heightPx = bitmap.height,
            bitmap = bitmap,
        )
    }
}

object VerovioBridge {
    private val loaded = runCatching {
        System.loadLibrary("verovio")
        System.loadLibrary("scores_verovio_bridge")
    }.isSuccess

    private external fun renderMusicXmlSvgPages(
        musicXml: String,
        resourcePath: String,
        targetWidthPx: Int,
    ): Array<String>

    external fun lastRenderDiagnostic(): String

    fun renderSvgPages(
        musicXml: String,
        resourcePath: String,
        targetWidthPx: Int,
    ): List<String> {
        return if (!loaded) {
            emptyList()
        } else {
            runCatching {
                renderMusicXmlSvgPages(musicXml, resourcePath, targetWidthPx).toList()
            }.getOrDefault(emptyList())
        }
    }

    fun lastDiagnostic(): String {
        return if (!loaded) {
            "Verovio native libraries failed to load."
        } else {
            runCatching { lastRenderDiagnostic() }.getOrDefault("Verovio did not render any notation.")
        }
    }
}

private object VerovioResources {
    private const val AssetRoot = "verovio"
    private const val Version = "8100cb39604d40102a9c2ce75719136f3fb52a77"

    fun ensure(context: Context): File {
        val directory = File(context.filesDir, "verovio-resources")
        val marker = File(directory, ".version-$Version")
        if (marker.exists()) return directory

        directory.deleteRecursively()
        directory.mkdirs()
        val rootChildren = context.assets.list(AssetRoot).orEmpty()
        require(rootChildren.isNotEmpty()) { "Verovio resources are not bundled in this build." }
        rootChildren.forEach { child ->
            copyAsset(context, "$AssetRoot/$child", File(directory, child))
        }
        marker.writeText(Version)
        return directory
    }

    private fun copyAsset(
        context: Context,
        assetPath: String,
        target: File,
    ) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAsset(context, "$assetPath/$child", File(target, child))
        }
    }
}
