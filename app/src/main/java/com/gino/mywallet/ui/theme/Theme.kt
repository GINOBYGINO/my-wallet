package com.gino.mywallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Moss = Color(0xFFC4D6A0)
private val Bark = Color(0xFF1A1F16)
private val Leaf = Color(0xFF3D5A3A)
private val Soil = Color(0xFF2A3226)
private val Mist = Color(0xFFE8EDE0)

private val Scheme = darkColorScheme(
    primary = Moss,
    onPrimary = Bark,
    secondary = Leaf,
    onSecondary = Mist,
    background = Bark,
    onBackground = Mist,
    surface = Soil,
    onSurface = Mist,
    surfaceVariant = Color(0xFF343C2F),
    onSurfaceVariant = Color(0xFFB8C4A8),
    error = Color(0xFFE8A090),
    onError = Bark
)

@Composable
fun MyWalletTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        content = content
    )
}
