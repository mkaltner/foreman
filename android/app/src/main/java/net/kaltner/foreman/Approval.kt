package net.kaltner.foreman

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class ApprovalDecision(
    val type: String,
    val label: String,
    val optionId: String? = null,
    val scopes: List<String> = emptyList(),
    val amendment: List<String> = emptyList(),
    val networkAmendment: JsonObject? = null,
)

@Serializable
data class ApprovalFileChange(
    val path: String,
    val kind: String,
    val summary: JsonObject? = null,
)

@Serializable
data class ApprovalRequest(
    val id: String,
    val sessionId: String,
    val turnId: String? = null,
    val itemId: String? = null,
    val type: String,
    val title: String,
    val createdAt: Long,
    val startedAt: Long? = null,
    val reason: String? = null,
    val status: String,
    val resolution: String? = null,
    val availableDecisions: List<ApprovalDecision> = emptyList(),
    val command: String? = null,
    val commandActions: List<JsonObject> = emptyList(),
    val cwd: String? = null,
    val networkContext: JsonObject? = null,
    val requestedPermissions: JsonObject = JsonObject(emptyMap()),
    val fileChanges: List<ApprovalFileChange> = emptyList(),
    val fileCount: Int = 0,
    val grantRoot: String? = null,
    val availableScopes: List<String> = emptyList(),
)

internal fun approvalAttentionLabel(type: String): String =
    when (type) {
        "command" -> "Waiting for command approval"
        "fileChange" -> "Waiting for file-change approval"
        "permission" -> "Waiting for permission grant"
        else -> "Approval required"
    }

internal data class PermissionChoice(
    val id: String,
    val label: String,
    val group: String,
    val value: JsonElement,
)

internal fun permissionChoices(approval: ApprovalRequest): List<PermissionChoice> {
    val choices = mutableListOf<PermissionChoice>()
    val fileSystem = approval.requestedPermissions["fileSystem"] as? JsonObject
    listOf("read", "write").forEach { group ->
        (fileSystem?.get(group) as? JsonArray)?.forEachIndexed { index, value ->
            val path = (value as? JsonPrimitive)?.contentOrNull ?: return@forEachIndexed
            choices += PermissionChoice("$group-$index", "${group.replaceFirstChar(Char::uppercase)}: $path", group, value)
        }
    }
    (fileSystem?.get("entries") as? JsonArray)?.forEachIndexed { index, value ->
        val entry = value as? JsonObject ?: return@forEachIndexed
        val access = entry["access"]?.jsonPrimitive?.contentOrNull ?: "Access"
        val path = permissionPath(entry["path"] as? JsonObject)
        choices += PermissionChoice("entry-$index", "$access: $path", "entries", entry)
    }
    if ((approval.requestedPermissions["network"] as? JsonObject)?.get("enabled")?.jsonPrimitive?.contentOrNull == "true") {
        choices += PermissionChoice("network", "Network access", "network", JsonPrimitive(true))
    }
    return choices
}

private fun permissionPath(path: JsonObject?): String {
    if (path == null) return "Requested path"
    path["path"]?.jsonPrimitive?.contentOrNull?.let { return it }
    path["pattern"]?.jsonPrimitive?.contentOrNull?.let { return it }
    val value = path["value"] as? JsonObject
    return listOf("kind", "path", "subpath")
        .mapNotNull { value?.get(it)?.jsonPrimitive?.contentOrNull }
        .joinToString(": ")
        .ifBlank { "Requested path" }
}

internal fun selectedPermissions(
    approval: ApprovalRequest,
    choices: List<PermissionChoice>,
    selected: Set<String>,
): JsonObject {
    val groups = choices.filter { it.id in selected }.groupBy { it.group }
    return buildJsonObject {
        if (groups.keys.any { it != "network" }) {
            put("fileSystem", buildJsonObject {
                listOf("read", "write", "entries").forEach { group ->
                    groups[group]?.let { values -> put(group, buildJsonArray { values.forEach { add(it.value) } }) }
                }
                val depth = (approval.requestedPermissions["fileSystem"] as? JsonObject)?.get("globScanMaxDepth")
                if (groups["entries"] != null && depth != null) put("globScanMaxDepth", depth)
            })
        }
        if (groups["network"] != null) put("network", buildJsonObject { put("enabled", true) })
    }
}

@Composable
internal fun ApprovalCard(
    approval: ApprovalRequest,
    connected: Boolean,
    submitting: Boolean,
    error: String?,
    onRespond: (JsonObject) -> Unit,
) {
    var selected by remember(approval.id) { mutableStateOf(emptySet<String>()) }
    var scope by remember(approval.id) { mutableStateOf<String?>(null) }
    val choices = remember(approval) { permissionChoices(approval) }
    val disabled = !connected || submitting || approval.status != "pending"
    val warning = LocalForemanColors.current.attention
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Approval required", color = warning, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(approval.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            approval.reason?.takeIf(String::isNotBlank)?.let { Text(it) }
            when (approval.type) {
                "command" -> CommandApprovalDetails(approval)
                "fileChange" -> FileApprovalDetails(approval)
                "permission" -> {
                    Text("Select only the access you want to grant", fontWeight = FontWeight.SemiBold)
                    choices.forEach { choice ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = choice.id in selected, enabled = !disabled, onCheckedChange = { checked -> selected = if (checked) selected + choice.id else selected - choice.id })
                            Text(choice.label, Modifier.padding(top = 11.dp), fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text("Grant scope", fontWeight = FontWeight.SemiBold)
                    approval.availableScopes.forEach { value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = scope == value, enabled = !disabled, onClick = { scope = value })
                            Text(if (value == "turn") "This turn" else "This session")
                        }
                    }
                }
                else -> Text("Unsupported approval type.")
            }
            if (submitting || approval.status == "submitting") Text("Submitting decision…", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            if (approval.status == "resolved") Text(if (approval.resolution == "resolvedElsewhere") "Already resolved in another client." else "Approval resolved.")
            if (approval.status == "expired") Text("This approval is no longer available.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            HorizontalDivider()
            if (approval.type == "permission") {
                Button(
                    enabled = !disabled && selected.isNotEmpty() && scope != null,
                    onClick = { onRespond(buildJsonObject {
                        put("type", "grant")
                        put("scope", requireNotNull(scope))
                        put("permissions", selectedPermissions(approval, choices, selected))
                    }) },
                ) { Text("Grant selected") }
                OutlinedButton(enabled = !disabled, onClick = { onRespond(buildJsonObject { put("type", "deny") }) }) { Text("Deny all") }
            } else {
                approval.availableDecisions.forEach { decision ->
                    val broad = decision.type in setOf("acceptForSession", "acceptWithExecpolicyAmendment", "applyNetworkPolicyAmendment")
                    if (broad || decision.type in setOf("decline", "cancel")) {
                        OutlinedButton(enabled = !disabled, onClick = { onRespond(decisionPayload(decision)) }) { Text(decision.label) }
                    } else {
                        Button(enabled = !disabled, onClick = { onRespond(decisionPayload(decision)) }) { Text(decision.label) }
                    }
                }
            }
        }
    }
}

private fun decisionPayload(decision: ApprovalDecision) = buildJsonObject {
    put("type", decision.type)
    decision.optionId?.let { put("optionId", it) }
}

@Composable
private fun CommandApprovalDetails(approval: ApprovalRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        approval.networkContext?.let { context -> Text("Network: ${context["protocol"]?.jsonPrimitive?.contentOrNull}://${context["host"]?.jsonPrimitive?.contentOrNull}") }
        approval.commandActions.forEach { action -> Text(listOf("type", "name", "path", "query", "command").mapNotNull { action[it]?.jsonPrimitive?.contentOrNull }.joinToString(": ")) }
        approval.command?.let {
            Text(
                it,
                Modifier.fillMaxWidth().heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                fontFamily = FontFamily.Monospace,
            )
        }
        approval.cwd?.let { Text("Working directory: $it", fontFamily = FontFamily.Monospace) }
        PermissionSummary(approval.requestedPermissions)
    }
}

@Composable
private fun FileApprovalDetails(approval: ApprovalRequest) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${approval.fileCount} affected file${if (approval.fileCount == 1) "" else "s"}")
        approval.fileChanges.forEach { change ->
            val added = change.summary?.get("addedLines")?.jsonPrimitive?.contentOrNull
            val removed = change.summary?.get("removedLines")?.jsonPrimitive?.contentOrNull
            Text(change.path + if (added != null && removed != null) "  +$added −$removed" else "", fontFamily = FontFamily.Monospace)
        }
        approval.grantRoot?.let { Text("Proposed write root: $it", fontFamily = FontFamily.Monospace) }
    }
}

@Composable
private fun PermissionSummary(permissions: JsonObject) {
    if (permissions.isEmpty()) return
    val placeholder = ApprovalRequest("", "", type = "permission", title = "", createdAt = 0, status = "", requestedPermissions = permissions)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Additional access requested", fontWeight = FontWeight.SemiBold)
        permissionChoices(placeholder).forEach { Text(it.label, fontFamily = FontFamily.Monospace) }
    }
}
