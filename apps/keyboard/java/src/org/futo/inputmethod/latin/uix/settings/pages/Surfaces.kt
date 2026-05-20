package org.futo.inputmethod.latin.uix.settings.pages

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment.Companion.Center
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.uix.theme.Typography

@Composable
fun ParagraphText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current.copy(alpha = 0.9f)
) {
    Text(text, modifier = modifier, style = Typography.SmallMl, color = color)
}

@Composable
fun SettingsSurfaceHeading(title: String) {
    Text(
        title,
        style = Typography.Body.MediumMl,
        color = LocalContentColor.current
    )
}

@Composable
fun SettingsSurface(
    isPrimary: Boolean,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val containerColor = if (isPrimary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    val contentColor = if (isPrimary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        contentAlignment = Center
    ) {
        Surface(
            color = containerColor,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .let {
                    if (onClick != null) {
                        it.clickable { onClick() }
                    } else {
                        it
                    }
                }
                .widthIn(Dp.Unspecified, 400.dp)

        ) {
            CompositionLocalProvider(LocalContentColor provides contentColor) {
                Column(
                    Modifier.padding(top = 19.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}
