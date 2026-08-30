package net.kaltner.foreman

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

internal data class ForemanSemanticColors(
    val success: Color,
    val successContainer: Color,
    val working: Color,
    val workingContainer: Color,
    val attention: Color,
    val attentionContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val failure: Color,
    val failureContainer: Color,
    val fullAccess: Color,
    val fullAccessContainer: Color,
)

internal data class ForemanThemeVariant(
    val background: Color,
    val surface: Color,
    val alternateSurface: Color,
    val raisedSurface: Color,
    val border: Color,
    val divider: Color,
    val text: Color,
    val mutedText: Color,
    val accent: Color,
    val onAccent: Color,
    val accentEmphasis: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val link: Color,
    val focus: Color,
    val selection: Color,
    val selectionText: Color,
    val disabledSurface: Color,
    val disabledText: Color,
    val disabledBorder: Color,
    val card: Color,
    val groupedHeader: Color,
    val usageTrack: Color,
    val usageFill: Color,
    val contextTrack: Color,
    val contextFill: Color,
    val navigation: Color,
    val dialog: Color,
    val popover: Color,
    val semantic: ForemanSemanticColors,
)

internal data class ForemanThemePalette(
    val light: ForemanThemeVariant,
    val dark: ForemanThemeVariant,
)

private val lightSemantic =
    ForemanSemanticColors(
        success = Color(0xFF087443),
        successContainer = Color(0xFFD9F4E5),
        working = Color(0xFF315FC4),
        workingContainer = Color(0xFFE2E9FF),
        attention = Color(0xFF9B5800),
        attentionContainer = Color(0xFFFFF0CF),
        warning = Color(0xFF8A5000),
        warningContainer = Color(0xFFFFF5D8),
        failure = Color(0xFFB42318),
        failureContainer = Color(0xFFFEE4E2),
        fullAccess = Color(0xFFA4293D),
        fullAccessContainer = Color(0xFFFFE4E9),
    )

private val darkSemantic =
    ForemanSemanticColors(
        success = Color(0xFF6CE9A6),
        successContainer = Color(0xFF153C2E),
        working = Color(0xFFA9C7FF),
        workingContainer = Color(0xFF263E70),
        attention = Color(0xFFFFC56F),
        attentionContainer = Color(0xFF4B2D0C),
        warning = Color(0xFFFFD58A),
        warningContainer = Color(0xFF49330F),
        failure = Color(0xFFFFB4AB),
        failureContainer = Color(0xFF571D1B),
        fullAccess = Color(0xFFFFB0BC),
        fullAccessContainer = Color(0xFF5C1F2B),
    )

private val highContrastLightSemantic =
    ForemanSemanticColors(
        success = Color(0xFF005A2B),
        successContainer = Color(0xFFD5F5DF),
        working = Color(0xFF0033A0),
        workingContainer = Color(0xFFDBE7FF),
        attention = Color(0xFF7A3E00),
        attentionContainer = Color(0xFFFFEDC2),
        warning = Color(0xFF6B3C00),
        warningContainer = Color(0xFFFFF0C7),
        failure = Color(0xFF970B0B),
        failureContainer = Color(0xFFFFE0E0),
        fullAccess = Color(0xFF8F1232),
        fullAccessContainer = Color(0xFFFFDCE5),
    )

private val highContrastDarkSemantic =
    ForemanSemanticColors(
        success = Color(0xFF7FF0A8),
        successContainer = Color(0xFF002E16),
        working = Color(0xFF9BC7FF),
        workingContainer = Color(0xFF00265C),
        attention = Color(0xFFFFD27A),
        attentionContainer = Color(0xFF442400),
        warning = Color(0xFFFFE08A),
        warningContainer = Color(0xFF3A2600),
        failure = Color(0xFFFF9E9E),
        failureContainer = Color(0xFF4A0000),
        fullAccess = Color(0xFFFF9FBD),
        fullAccessContainer = Color(0xFF4A0018),
    )

private fun lightVariant(
    background: Long,
    surface: Long,
    alternate: Long,
    border: Long,
    text: Long,
    muted: Long,
    accent: Long,
    accentEmphasis: Long,
    accentContainer: Long,
    onAccentContainer: Long,
    link: Long,
    focus: Long,
    disabledSurface: Long = 0xFFE7E3EB,
    disabledText: Long = 0xFF8A8492,
    disabledBorder: Long = 0xFFD3CDD9,
    semantic: ForemanSemanticColors = lightSemantic,
) = ForemanThemeVariant(
    background = Color(background),
    surface = Color(surface),
    alternateSurface = Color(alternate),
    raisedSurface = Color(surface),
    border = Color(border),
    divider = Color(border),
    text = Color(text),
    mutedText = Color(muted),
    accent = Color(accent),
    onAccent = Color.White,
    accentEmphasis = Color(accentEmphasis),
    accentContainer = Color(accentContainer),
    onAccentContainer = Color(onAccentContainer),
    link = Color(link),
    focus = Color(focus),
    selection = Color(accentContainer),
    selectionText = Color(onAccentContainer),
    disabledSurface = Color(disabledSurface),
    disabledText = Color(disabledText),
    disabledBorder = Color(disabledBorder),
    card = Color(surface),
    groupedHeader = Color(alternate),
    usageTrack = Color(alternate),
    usageFill = Color(accent),
    contextTrack = Color(border),
    contextFill = Color(accent),
    navigation = Color(surface),
    dialog = Color(surface),
    popover = Color(surface),
    semantic = semantic,
)

private fun darkVariant(
    background: Long,
    surface: Long,
    alternate: Long,
    border: Long,
    text: Long,
    muted: Long,
    accent: Long,
    onAccent: Long,
    accentEmphasis: Long,
    accentContainer: Long,
    onAccentContainer: Long,
    link: Long,
    focus: Long,
    disabledSurface: Long = 0xFF2D2834,
    disabledText: Long = 0xFF746C7D,
    disabledBorder: Long = 0xFF3A3442,
    semantic: ForemanSemanticColors = darkSemantic,
) = ForemanThemeVariant(
    background = Color(background),
    surface = Color(surface),
    alternateSurface = Color(alternate),
    raisedSurface = Color(surface),
    border = Color(border),
    divider = Color(border),
    text = Color(text),
    mutedText = Color(muted),
    accent = Color(accent),
    onAccent = Color(onAccent),
    accentEmphasis = Color(accentEmphasis),
    accentContainer = Color(accentContainer),
    onAccentContainer = Color(onAccentContainer),
    link = Color(link),
    focus = Color(focus),
    selection = Color(accentContainer),
    selectionText = Color(onAccentContainer),
    disabledSurface = Color(disabledSurface),
    disabledText = Color(disabledText),
    disabledBorder = Color(disabledBorder),
    card = Color(surface),
    groupedHeader = Color(alternate),
    usageTrack = Color(alternate),
    usageFill = Color(accent),
    contextTrack = Color(border),
    contextFill = Color(accent),
    navigation = Color(surface),
    dialog = Color(surface),
    popover = Color(surface),
    semantic = semantic,
)

internal fun foremanThemePalette(themeId: ThemeId): ForemanThemePalette =
    when (themeId) {
        ThemeId.Foreman -> ForemanThemePalette(
            light = lightVariant(0xFFF5F3FA, 0xFFFFFFFF, 0xFFEEEBF4, 0xFFDDD7E7, 0xFF1C1A24, 0xFF6C6676, 0xFF6B3FB5, 0xFF4F298F, 0xFFE9DDF8, 0xFF281044, 0xFF523091, 0xFF7044BC),
            dark = darkVariant(0xFF14111B, 0xFF1D1926, 0xFF292333, 0xFF443B50, 0xFFF4EFFA, 0xFFB0A6BC, 0xFFD0B7FA, 0xFF291443, 0xFFB58AE9, 0xFF4C2D72, 0xFFF3EAFF, 0xFFD6C2FF, 0xFFD0B7FA),
        )
        ThemeId.Harbor -> ForemanThemePalette(
            light = lightVariant(0xFFF1F7F8, 0xFFFFFFFF, 0xFFE5F0F2, 0xFFCBDFE2, 0xFF122326, 0xFF5D7377, 0xFF006B75, 0xFF00515A, 0xFFC5EDF0, 0xFF00363C, 0xFF005E8A, 0xFF007984),
            dark = darkVariant(0xFF0D1719, 0xFF142226, 0xFF203137, 0xFF385159, 0xFFEDF8FA, 0xFFA2B9BD, 0xFF74D8E1, 0xFF00363C, 0xFF4BBBC6, 0xFF134D54, 0xFFD3F8FB, 0xFF8BDCFF, 0xFF74D8E1),
        )
        ThemeId.Grove -> ForemanThemePalette(
            light = lightVariant(0xFFF4F7F1, 0xFFFFFFFF, 0xFFE9F0E5, 0xFFD2DECF, 0xFF19231A, 0xFF637062, 0xFF356A3F, 0xFF25522F, 0xFFD6ECD2, 0xFF15391D, 0xFF2D6337, 0xFF40784A),
            dark = darkVariant(0xFF111812, 0xFF19231A, 0xFF263127, 0xFF3E5140, 0xFFF0F8ED, 0xFFA8B9A5, 0xFF91D99A, 0xFF113919, 0xFF6FBD79, 0xFF285C31, 0xFFDDF8DC, 0xFFA5E7AD, 0xFF91D99A),
        )
        ThemeId.Ember -> ForemanThemePalette(
            light = lightVariant(0xFFFAF3F4, 0xFFFFFFFF, 0xFFF3E7EB, 0xFFE4D2D9, 0xFF291B21, 0xFF77636B, 0xFF8A3D61, 0xFF6D2A4B, 0xFFF0D3DF, 0xFF48142D, 0xFF7D3558, 0xFF97496D),
            dark = darkVariant(0xFF1A1115, 0xFF25191E, 0xFF34242B, 0xFF523B45, 0xFFFFF1F5, 0xFFC0A7B1, 0xFFEFACC8, 0xFF521A35, 0xFFD888AA, 0xFF6B2948, 0xFFFFE8F1, 0xFFFFC0DA, 0xFFEFACC8),
        )
        ThemeId.Dune -> ForemanThemePalette(
            light = lightVariant(0xFFFAF6ED, 0xFFFFFDF8, 0xFFF2E7D2, 0xFFD9C6A3, 0xFF2B2115, 0xFF6F604D, 0xFF7A4F00, 0xFF5D3C00, 0xFFF3DCA9, 0xFF3F2900, 0xFF704600, 0xFF8A5A00),
            dark = darkVariant(0xFF18140D, 0xFF221C12, 0xFF302719, 0xFF55462F, 0xFFFFF6E4, 0xFFC0AD8B, 0xFFF2C66D, 0xFF3E2D00, 0xFFD7A942, 0xFF5A4213, 0xFFFFEBBD, 0xFFFFD587, 0xFFF2C66D),
        )
        ThemeId.Slate -> ForemanThemePalette(
            light = lightVariant(0xFFF3F6FA, 0xFFFFFFFF, 0xFFE7EDF4, 0xFFCBD5E1, 0xFF172033, 0xFF59677B, 0xFF365A8C, 0xFF27456F, 0xFFD8E6F8, 0xFF152F52, 0xFF2D5489, 0xFF40699E),
            dark = darkVariant(0xFF0F141C, 0xFF171F2B, 0xFF222D3B, 0xFF3B4A5F, 0xFFF2F6FB, 0xFFAAB7C7, 0xFF9FC5F5, 0xFF183656, 0xFF78A7DD, 0xFF294F78, 0xFFE4F0FF, 0xFFAFD2FF, 0xFF9FC5F5),
        )
        ThemeId.HighContrast -> ForemanThemePalette(
            light = lightVariant(
                0xFFFFFFFF, 0xFFFFFFFF, 0xFFE6E6E6, 0xFF1A1A1A, 0xFF000000, 0xFF333333,
                0xFF0033A0, 0xFF001F66, 0xFFC9DCFF, 0xFF001B54, 0xFF0033A0, 0xFF7A1F00,
                disabledSurface = 0xFFD9D9D9,
                disabledText = 0xFF595959,
                disabledBorder = 0xFF595959,
                semantic = highContrastLightSemantic,
            ),
            dark = darkVariant(
                0xFF000000, 0xFF050505, 0xFF1A1A1A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFE0E0E0,
                0xFF79B8FF, 0xFF000000, 0xFFA9D1FF, 0xFF002D73, 0xFFFFFFFF, 0xFF8FC6FF, 0xFFFFDD57,
                disabledSurface = 0xFF1F1F1F,
                disabledText = 0xFFB3B3B3,
                disabledBorder = 0xFF999999,
                semantic = highContrastDarkSemantic,
            ),
        )
    }

internal fun foremanThemeVariant(themeId: ThemeId, darkTheme: Boolean): ForemanThemeVariant =
    foremanThemePalette(themeId).let { if (darkTheme) it.dark else it.light }

internal fun foremanColorScheme(themeId: ThemeId, darkTheme: Boolean) =
    foremanThemeVariant(themeId, darkTheme).let { colors ->
        val semantic = colors.semantic
        val scheme = if (darkTheme) darkColorScheme() else lightColorScheme()
        scheme.copy(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.accentContainer,
            onPrimaryContainer = colors.onAccentContainer,
            secondary = colors.accentEmphasis,
            onSecondary = colors.onAccent,
            secondaryContainer = colors.selection,
            onSecondaryContainer = colors.onAccentContainer,
            tertiary = colors.link,
            onTertiary = colors.onAccent,
            background = colors.background,
            onBackground = colors.text,
            surface = colors.surface,
            onSurface = colors.text,
            surfaceVariant = colors.alternateSurface,
            onSurfaceVariant = colors.mutedText,
            surfaceContainer = colors.raisedSurface,
            surfaceContainerLow = colors.card,
            surfaceContainerHigh = colors.groupedHeader,
            outline = colors.border,
            outlineVariant = colors.divider,
            error = semantic.failure,
            onError = if (darkTheme) Color(0xFF690005) else Color.White,
            errorContainer = semantic.failureContainer,
            onErrorContainer = semantic.failure,
            surfaceTint = colors.accent,
            scrim = Color.Black,
        )
    }

internal val LocalForemanColors = staticCompositionLocalOf {
    foremanThemePalette(ThemeId.Foreman).light.semantic
}

@Composable
internal fun ForemanTheme(
    themeId: ThemeId,
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    val variant = foremanThemeVariant(themeId, darkTheme)
    CompositionLocalProvider(LocalForemanColors provides variant.semantic) {
        MaterialTheme(colorScheme = foremanColorScheme(themeId, darkTheme), content = content)
    }
}
