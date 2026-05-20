package org.futo.inputmethod.latin.uix.theme.selector

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.futo.inputmethod.latin.uix.KeyboardBackground
import org.futo.inputmethod.latin.uix.KeyboardColorScheme
import org.futo.inputmethod.latin.uix.theme.ThemeOption
import org.futo.inputmethod.latin.uix.theme.Typography

@Composable
fun ThemePreview(
    theme: ThemeOption,
    isSelected: Boolean = false,
    overrideName: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = { }
) {
    val context = LocalContext.current
    val colors = remember(theme) { theme.obtainColors(context) }

    ThemePreview(
        colors = colors,
        name = overrideName ?: stringResource(theme.name),
        loading = false,
        isSelected = isSelected,
        modifier = modifier,
        onClick = onClick
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThemePreview(
    colors: KeyboardColorScheme,
    name: String,
    loading: Boolean,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit = { }
) {
    val currColors = MaterialTheme.colorScheme

    val borderWidth = if (isSelected) {
        4.dp
    } else {
        Dp.Hairline
    }

    val borderColor = if (isSelected) {
        currColors.inversePrimary
    } else {
        currColors.outline
    }

    val textColor = colors.onBackground
    val spacebarColor = colors.keyboardContainer
    val actionColor = colors.primary
    val keyboardShape = RoundedCornerShape(8.dp)

    val previewModifier = if(LocalInspectionMode.current) {
        modifier.width(172.dp)
    } else {
        modifier
    }

    Box(
        modifier = previewModifier
            .padding(12.dp)
            .height(128.dp)
            .border(borderWidth, borderColor, keyboardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .clip(keyboardShape),
    ) {
        KeyboardBackground(colors, useThumbnail = true)
        Box(modifier = Modifier.fillMaxSize()) {
            Text(
                text = name,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(colors.keyboardSurfaceDim.copy(alpha = 1.0f))
                    .fillMaxWidth()
                    .padding(4.dp),
                color = textColor,
                style = Typography.SmallMl
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(18.dp)
                        .align(Alignment.BottomCenter),
                    color = spacebarColor,
                    shape = RoundedCornerShape(4.dp)
                ) { }

                Surface(
                    modifier = Modifier
                        .width(24.dp)
                        .height(18.dp)
                        .align(Alignment.BottomEnd)
                        .padding(0.dp, 1.dp),
                    color = actionColor,
                    shape = RoundedCornerShape(4.dp)
                ) { }
            }
        }
    }
}
