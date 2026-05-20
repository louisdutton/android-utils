package org.futo.inputmethod.latin.uix.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T> VerticalGrid(
    items: List<T>,
    columns: Int,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit
) {
    Column(modifier = modifier) {
        items.chunked(columns).forEach { rowItems ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    Box(modifier = Modifier.weight(1.0f)) {
                        itemContent(item)
                    }
                }

                repeat(columns - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1.0f))
                }
            }
        }
    }
}
