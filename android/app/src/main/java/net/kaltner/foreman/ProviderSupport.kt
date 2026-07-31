package net.kaltner.foreman

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

const val PROVIDER_CODEX = "codex"
const val PROVIDER_CLAUDE_CODE = "claude-code"

@Serializable
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val available: Boolean,
    val version: String? = null,
    val cliVersion: String? = null,
    val sdkVersion: String? = null,
    val nodeVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val unavailableReason: String? = null,
)

@Serializable
data class PermissionModeInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val highRisk: Boolean = false,
)

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
    session.provider?.takeIf { it == PROVIDER_CODEX || it == PROVIDER_CLAUDE_CODE }
        ?: PROVIDER_CODEX

internal fun SessionSummary.providerKey(): String = providerSessionKey(sessionProvider(this), id)

internal fun SessionSummary.matches(provider: String, sessionId: String): Boolean =
    id == sessionId && sessionProvider(this) == provider

internal fun providerDisplayName(provider: String): String =
    if (provider == PROVIDER_CLAUDE_CODE) "Claude Code" else "Codex"

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
