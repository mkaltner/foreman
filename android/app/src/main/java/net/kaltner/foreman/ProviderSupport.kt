package net.kaltner.foreman

import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PROVIDER_CODEX = "codex"
const val PROVIDER_CLAUDE_CODE = "claude-code"

internal fun supportedProvider(provider: String): Boolean =
    provider == PROVIDER_CODEX || provider == PROVIDER_CLAUDE_CODE

@Serializable
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val enabled: Boolean = true,
    val available: Boolean,
    val version: String? = null,
    val cliVersion: String? = null,
    val sdkVersion: String? = null,
    val nodeVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val unavailableReason: String? = null,
)

internal fun providerEnabled(provider: ProviderInfo): Boolean = provider.enabled

internal fun soleEnabledProvider(
    providers: List<ProviderInfo>,
    catalogLoaded: Boolean,
): ProviderInfo? {
    if (!catalogLoaded) return null
    return providers.filter(::providerEnabled).singleOrNull()
}

internal fun shouldShowProviderIdentity(
    providers: List<ProviderInfo>,
    catalogLoaded: Boolean,
): Boolean = soleEnabledProvider(providers, catalogLoaded) == null

internal fun newSessionProviderSelection(
    providers: List<ProviderInfo>,
    catalogLoaded: Boolean,
    preferredProvider: String,
): String? {
    if (!catalogLoaded) return null
    val enabled = providers.filter(::providerEnabled)
    return enabled.singleOrNull()?.id
        ?: enabled.firstOrNull { it.id == preferredProvider }?.id
        ?: enabled.firstOrNull()?.id
}

internal fun providerCatalogResponseIsCurrent(
    requestHostId: String?,
    activeHostId: String?,
    requestRevision: Long,
    currentRevision: Long,
): Boolean = requestHostId == activeHostId && requestRevision == currentRevision

@Serializable
data class PermissionModeInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val highRisk: Boolean = false,
)

@Serializable
data class RateLimitWindow(
    val id: String? = null,
    val label: String? = null,
    val usedPercent: Double,
    val windowDurationMins: Long? = null,
    val resetsAt: Long? = null,
)

@Serializable
data class RateLimitSnapshot(
    val limitId: String? = null,
    val limitName: String? = null,
    val primary: RateLimitWindow? = null,
    val secondary: RateLimitWindow? = null,
    val windows: List<RateLimitWindow> = emptyList(),
    val planType: String? = null,
    val rateLimitReachedType: String? = null,
)

@Serializable
data class ProviderAccountUsage(
    val available: Boolean,
    val rateLimits: RateLimitSnapshot? = null,
    val experimental: Boolean = false,
    val observedAt: Long? = null,
    val availabilityReason: String? = null,
)

@Serializable
data class AccountUsage(
    val providers: Map<String, ProviderAccountUsage> = emptyMap(),
)

internal data class ContextUsageView(
    val usedTokens: Long,
    val remainingTokens: Long,
    val contextWindow: Long,
    val percentUsed: Int,
    val percentRemaining: Int,
)

internal fun contextUsageView(tokenUsage: ThreadTokenUsage?): ContextUsageView? {
    val used = tokenUsage?.last?.totalTokens ?: return null
    val window = tokenUsage.modelContextWindow ?: return null
    if (used < 0 || window <= 0) return null
    val safeUsed = used.coerceAtLeast(0)
    val percentUsed = ((safeUsed.toDouble() / window) * 100).roundToInt().coerceIn(0, 100)
    return ContextUsageView(
        usedTokens = safeUsed,
        remainingTokens = (window - safeUsed).coerceAtLeast(0),
        contextWindow = window,
        percentUsed = percentUsed,
        percentRemaining = 100 - percentUsed,
    )
}

internal fun formatTokenCount(value: Long): String =
    when {
        value >= 1_000_000 -> {
            val millions = value / 1_000_000.0
            if (millions >= 10) "${millions.toInt()}m"
            else "${String.format(Locale.US, "%.1f", millions).removeSuffix(".0")}m"
        }
        value >= 1_000 -> {
            val thousands = value / 1_000.0
            if (thousands >= 100) "${thousands.toInt()}k"
            else "${String.format(Locale.US, "%.1f", thousands).removeSuffix(".0")}k"
        }
        else -> value.toString()
    }

private const val MAX_RATE_LIMIT_WINDOWS = 16
private const val MAX_RATE_LIMIT_DURATION_MINS = 525_600L
private const val MAX_PUBLIC_TIMESTAMP = 253_402_300_799L
private const val MAX_RATE_LIMIT_TEXT = 100

private fun RateLimitWindow.normalized(fallbackId: String): RateLimitWindow? {
    if (!usedPercent.isFinite()) return null
    return copy(
        id = id?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null } ?: fallbackId,
        label = label?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
        usedPercent = usedPercent.coerceIn(0.0, 100.0),
        windowDurationMins = windowDurationMins?.takeIf { it > 0 }
            ?.coerceAtMost(MAX_RATE_LIMIT_DURATION_MINS),
        resetsAt = resetsAt?.takeIf { it >= 0 }?.coerceAtMost(MAX_PUBLIC_TIMESTAMP),
    )
}

internal fun accountUsageWindows(usage: ProviderAccountUsage?): List<RateLimitWindow> {
    val limits = usage?.rateLimits ?: return emptyList()
    val raw = limits.windows.takeIf { it.isNotEmpty() }
        ?: listOfNotNull(limits.primary, limits.secondary)
    val seen = mutableSetOf<String>()
    return raw.take(MAX_RATE_LIMIT_WINDOWS).mapIndexedNotNull { index, window ->
        val fallback = if (index == 0) "primary" else if (index == 1) "secondary" else "window-${index + 1}"
        window.normalized(fallback)?.let { normalized ->
            val baseId = normalized.id!!
            var id = baseId
            var duplicate = 2
            while (id in seen) {
                val suffix = "-${duplicate++}"
                id = baseId.take(MAX_RATE_LIMIT_TEXT - suffix.length) + suffix
            }
            seen.add(id)
            normalized.copy(id = id)
        }
    }
}

internal fun AccountUsage.normalized(): AccountUsage {
    val projected = providers.mapNotNull { (provider, usage) ->
        if (!supportedProvider(provider)) return@mapNotNull null
        val windows = accountUsageWindows(usage)
        val primary = windows.firstOrNull { it.id == "primary" } ?: windows.firstOrNull()
        val secondary = windows.firstOrNull { it.id == "secondary" } ?: windows.getOrNull(1)
        val limits = usage.rateLimits?.takeIf { windows.isNotEmpty() }?.copy(
            limitId = usage.rateLimits.limitId?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
            limitName = usage.rateLimits.limitName?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
            primary = primary,
            secondary = secondary,
            windows = windows,
            planType = usage.rateLimits.planType?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
            rateLimitReachedType = usage.rateLimits.rateLimitReachedType?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
        )
        provider to usage.copy(
            available = usage.available && windows.isNotEmpty(),
            rateLimits = limits,
            observedAt = usage.observedAt?.takeIf { it >= 0 }?.coerceAtMost(MAX_PUBLIC_TIMESTAMP),
            availabilityReason = usage.availabilityReason?.trim()?.take(MAX_RATE_LIMIT_TEXT)?.ifBlank { null },
        )
    }.toMap()
    return AccountUsage(projected)
}

private val accountUsageStorageJson = Json { ignoreUnknownKeys = true }

internal fun encodeStoredAccountUsage(usage: AccountUsage): String =
    accountUsageStorageJson.encodeToString(usage.normalized())

internal fun decodeStoredAccountUsage(encoded: String?): AccountUsage =
    encoded?.let {
        runCatching { accountUsageStorageJson.decodeFromString<AccountUsage>(it).normalized() }
            .getOrNull()
    } ?: AccountUsage()

internal fun accountUsageRemaining(usage: ProviderAccountUsage?): String {
    val windows = accountUsageWindows(usage)
    if (windows.isEmpty()) return "unavailable"
    return "${(100 - windows.maxOf { it.usedPercent }).roundToInt().coerceIn(0, 100)}% left"
}

internal fun mostConstrainedWindow(windows: List<RateLimitWindow>): RateLimitWindow? =
    windows.maxByOrNull { it.usedPercent }

internal fun rateLimitLabel(window: RateLimitWindow, index: Int, count: Int): String {
    window.label?.let { return it }
    return when (val durationMinutes = window.windowDurationMins) {
        10_080L -> "Weekly limit"
        null -> if (count == 1) "Usage limit" else "Usage limit ${index + 1}"
        else -> when {
            durationMinutes % 60 == 0L -> "${durationMinutes / 60}-hour limit"
            else -> "$durationMinutes-minute limit"
        }
    }
}

internal fun compactRateLimitLabel(window: RateLimitWindow): String =
    rateLimitLabel(window, 0, 1).removeSuffix(" limit")

internal data class CompactAccountUsage(
    val usedPercent: Int,
    val primaryText: String,
    val secondaryText: String,
)

internal fun compactAccountUsage(windows: List<RateLimitWindow>): CompactAccountUsage {
    val constrained = mostConstrainedWindow(windows)
        ?: return CompactAccountUsage(0, "Account usage unavailable", "Account usage")
    val usedPercent = constrained.usedPercent.roundToInt().coerceIn(0, 100)
    val remaining = (100 - constrained.usedPercent).roundToInt().coerceIn(0, 100)
    val additional = (windows.size - 1).coerceAtLeast(0)
    return CompactAccountUsage(
        usedPercent = usedPercent,
        primaryText = "$remaining% left · ${compactRateLimitLabel(constrained)}",
        secondaryText = if (additional > 0) {
            "+$additional more ${if (additional == 1) "limit" else "limits"}"
        } else {
            "Account usage"
        },
    )
}

data class SessionIdentity(
    val hostId: String,
    val provider: String,
    val sessionId: String,
)

internal fun providerSessionKey(provider: String, sessionId: String): String =
    "${provider.length}:$provider$sessionId"

internal fun parseProviderSessionKey(key: String): Pair<String, String>? {
    val separator = key.indexOf(':')
    val providerLength = key.substring(0, separator.coerceAtLeast(0)).toIntOrNull() ?: return null
    val providerStart = separator + 1
    val providerEnd = providerStart + providerLength
    if (separator <= 0 || providerEnd > key.length) return null
    val provider = key.substring(providerStart, providerEnd)
    if (provider !in setOf(PROVIDER_CODEX, PROVIDER_CLAUDE_CODE)) return null
    val sessionId = key.substring(providerEnd)
    return if (sessionId.isBlank()) null else provider to sessionId
}

internal fun sessionIdentityKey(identity: SessionIdentity): String =
    "${identity.hostId.length}:${identity.hostId}" +
        providerSessionKey(identity.provider, identity.sessionId)

internal fun providerNotificationId(
    hostId: String,
    provider: String,
    sessionId: String,
    base: Int = 2_000,
): Int = base + (sessionIdentityKey(SessionIdentity(hostId, provider, sessionId)).hashCode() and 0x00ffffff)

internal fun legacySessionKey(value: String): String =
    if (value.startsWith("${PROVIDER_CODEX.length}:$PROVIDER_CODEX") ||
        value.startsWith("${PROVIDER_CLAUDE_CODE.length}:$PROVIDER_CLAUDE_CODE")
    ) value else providerSessionKey(PROVIDER_CODEX, value)

internal fun sessionProvider(session: SessionSummary): String =
    session.provider?.takeIf { it.isNotBlank() } ?: PROVIDER_CODEX

internal fun SessionSummary.providerKey(): String = providerSessionKey(sessionProvider(this), id)

internal fun SessionSummary.matches(provider: String, sessionId: String): Boolean =
    id == sessionId && sessionProvider(this) == provider

internal fun providerDisplayName(provider: String): String =
    when (provider) {
        PROVIDER_CODEX -> "Codex"
        PROVIDER_CLAUDE_CODE -> "Claude Code"
        else -> "Provider"
    }

internal fun sessionDisplayStatus(session: SessionSummary): String =
    when {
        sessionProvider(session) == PROVIDER_CLAUDE_CODE &&
            session.source == "external" && session.status == "working" -> "external active"
        else -> session.status
    }

internal fun providerUnavailableDescription(reason: String?): String =
    when (reason) {
        "cli-missing" -> "The Claude Code CLI is not installed."
        "node-missing" -> "Node.js 20 or newer is not installed."
        "sdk-missing" -> "The pinned Claude Agent SDK is not installed."
        "authentication-unavailable" -> "Claude authentication is unavailable on this host."
        else -> "The Claude Code adapter is unavailable."
    }

internal fun claudeInterruptEligible(session: SessionSummary): Boolean =
    sessionProvider(session) == PROVIDER_CLAUDE_CODE &&
        session.source == "managed" &&
        session.status in setOf("working", "waiting") &&
        !session.activeTurnId.isNullOrBlank()

internal fun providerInterruptEligible(session: SessionSummary): Boolean =
    if (sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        claudeInterruptEligible(session)
    } else {
        session.status in setOf("working", "waiting") && !session.activeTurnId.isNullOrBlank()
    }

internal fun providerPromptOperation(session: SessionSummary): String =
    if (sessionProvider(session) == PROVIDER_CLAUDE_CODE && session.source == "external") {
        "provider.session.resume"
    } else if (sessionProvider(session) == PROVIDER_CLAUDE_CODE) {
        "provider.turn.prompt"
    } else {
        "turn.prompt"
    }

internal fun claudePromptPayload(
    session: SessionSummary,
    text: String,
    model: String,
    permissionMode: String,
) = buildJsonObject {
    require(sessionProvider(session) == PROVIDER_CLAUDE_CODE)
    put("provider", PROVIDER_CLAUDE_CODE)
    put("sessionId", session.id)
    put("repositoryId", session.repositoryId ?: ".")
    put("text", text.trim())
    put("model", model)
    put("permissionMode", permissionMode)
}

internal fun defaultProviders(): List<ProviderInfo> =
    listOf(
        ProviderInfo(
            id = PROVIDER_CODEX,
            displayName = "Codex",
            available = true,
            capabilities = emptyList(),
            limitations = emptyList(),
        ),
    )
