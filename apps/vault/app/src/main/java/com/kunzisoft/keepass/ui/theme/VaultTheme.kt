package com.kunzisoft.keepass.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kunzisoft.keepass.settings.PreferencesUtil

@Composable
fun VaultTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    pureBlack: Boolean = PreferencesUtil.usePureBlackOled(LocalContext.current),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }.let { scheme ->
        if (darkTheme && pureBlack) scheme.withPureBlackSurfaces() else scheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

private fun ColorScheme.withPureBlackSurfaces(): ColorScheme {
    return copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceBright = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color.Black,
        surfaceContainer = Color.Black,
        surfaceContainerHigh = Color.Black,
        surfaceContainerHighest = Color.Black,
        surfaceVariant = Color.Black
    )
}
