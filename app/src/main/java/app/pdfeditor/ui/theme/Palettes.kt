package app.pdfeditor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Neutral surfaces shared by every accent; only the accent roles are swapped in
// per preset so the chrome stays consistent and cohesive.
private val BaseLight = lightColorScheme(
    background = Color(0xFFF8F9FB),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF8F9FB),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE1E2E8),
    onSurfaceVariant = Color(0xFF44474E),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3F5),
    surfaceContainer = Color(0xFFECEDEF),
    surfaceContainerHigh = Color(0xFFE6E8EA),
    surfaceContainerHighest = Color(0xFFE1E2E4),
    outline = Color(0xFF74777F),
    outlineVariant = Color(0xFFC4C6CF),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val BaseDark = darkColorScheme(
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF191C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF282A2E),
    surfaceContainerHighest = Color(0xFF333539),
    outline = Color(0xFF8E9099),
    outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private class AccentRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val tertiary: Color,
    val inversePrimary: Color,
)

private fun rolesFor(accent: Accent, dark: Boolean): AccentRoles = when (accent) {
    Accent.TEAL -> if (!dark) AccentRoles(
        Color(0xFF006B5B), Color(0xFFFFFFFF), Color(0xFF7DF8DD), Color(0xFF00201A),
        Color(0xFF4A635C), Color(0xFF426277), Color(0xFF5DDBC1),
    ) else AccentRoles(
        Color(0xFF5DDBC1), Color(0xFF00382F), Color(0xFF005144), Color(0xFF7DF8DD),
        Color(0xFFB1CCC2), Color(0xFFAACBE3), Color(0xFF006B5B),
    )
    Accent.BLUE -> if (!dark) AccentRoles(
        Color(0xFF215FA6), Color(0xFFFFFFFF), Color(0xFFD6E3FF), Color(0xFF001B3D),
        Color(0xFF565F71), Color(0xFF705574), Color(0xFFA9C7FF),
    ) else AccentRoles(
        Color(0xFFA9C7FF), Color(0xFF00315F), Color(0xFF004787), Color(0xFFD6E3FF),
        Color(0xFFBEC6DC), Color(0xFFDCBCE0), Color(0xFF215FA6),
    )
    Accent.VIOLET -> if (!dark) AccentRoles(
        Color(0xFF6750A4), Color(0xFFFFFFFF), Color(0xFFEADDFF), Color(0xFF21005D),
        Color(0xFF625B71), Color(0xFF7D5260), Color(0xFFD0BCFF),
    ) else AccentRoles(
        Color(0xFFD0BCFF), Color(0xFF381E72), Color(0xFF4F378B), Color(0xFFEADDFF),
        Color(0xFFCCC2DC), Color(0xFFEFB8C8), Color(0xFF6750A4),
    )
    Accent.GREEN -> if (!dark) AccentRoles(
        Color(0xFF3B6939), Color(0xFFFFFFFF), Color(0xFFBCF0B4), Color(0xFF002105),
        Color(0xFF52634F), Color(0xFF38656A), Color(0xFFA1D399),
    ) else AccentRoles(
        Color(0xFFA1D399), Color(0xFF0A390F), Color(0xFF235024), Color(0xFFBCF0B4),
        Color(0xFFB9CCB4), Color(0xFFA0CFD4), Color(0xFF3B6939),
    )
    Accent.AMBER -> if (!dark) AccentRoles(
        Color(0xFF855300), Color(0xFFFFFFFF), Color(0xFFFFDDB3), Color(0xFF2A1800),
        Color(0xFF6F5B40), Color(0xFF51643F), Color(0xFFFFB95C),
    ) else AccentRoles(
        Color(0xFFFFB95C), Color(0xFF472A00), Color(0xFF653E00), Color(0xFFFFDDB3),
        Color(0xFFDDC2A1), Color(0xFFB6CCA0), Color(0xFF855300),
    )
}

object Palettes {
    fun scheme(accent: Accent, dark: Boolean): ColorScheme {
        val base = if (dark) BaseDark else BaseLight
        val r = rolesFor(accent, dark)
        return base.copy(
            primary = r.primary,
            onPrimary = r.onPrimary,
            primaryContainer = r.primaryContainer,
            onPrimaryContainer = r.onPrimaryContainer,
            secondary = r.secondary,
            tertiary = r.tertiary,
            inversePrimary = r.inversePrimary,
        )
    }
}
