package net.kaltner.foreman

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

@Serializable
data class InputOption(
    val value: String,
    val label: String,
    val description: String? = null,
)

@Serializable
data class InputField(
    val id: String,
    val type: String,
    val label: String,
    val description: String? = null,
    val required: Boolean,
    val secret: Boolean = false,
    val options: List<InputOption> = emptyList(),
    val allowOther: Boolean = false,
    val minSelections: Int = 0,
    val maxSelections: Int = 0,
    val minLength: Int = 0,
    val maxLength: Int = 4096,
    val default: JsonElement? = null,
)

@Serializable
data class InputRequest(
    val id: String,
    val sessionId: String,
    val turnId: String? = null,
    val itemId: String? = null,
    val source: String,
    val title: String,
    val message: String? = null,
    val serverName: String? = null,
    val fields: List<InputField> = emptyList(),
    val supported: Boolean,
    val unsupportedMessage: String? = null,
    val canDecline: Boolean = false,
    val canCancel: Boolean = false,
    val autoResolutionMs: Long? = null,
    val createdAt: Long,
    val status: String,
    val resolution: String? = null,
)

internal fun inputAttentionLabel(input: InputRequest): String =
    if (input.supported) "Waiting for user input" else "Waiting for unsupported user input"

@Composable
internal fun InputRequestCard(
    input: InputRequest,
    connected: Boolean,
    submitting: Boolean,
    error: String?,
    onRespond: (JsonObject) -> Unit,
) {
    var values by remember(input.id) {
        mutableStateOf(input.fields.mapNotNull { field -> field.default?.let { field.id to it } }.toMap())
    }
    var otherValues by remember(input.id) { mutableStateOf(emptyMap<String, String>()) }
    var localError by remember(input.id) { mutableStateOf<String?>(null) }
    val disabled = !connected || submitting || input.status != "pending"

    fun submit() {
        val normalized = values.toMutableMap()
        input.fields.forEach { field ->
            if ((normalized[field.id] as? JsonPrimitive)?.contentOrNull == "__other__") {
                normalized[field.id] = JsonPrimitive(otherValues[field.id].orEmpty())
            }
            val value = normalized[field.id]
            if (field.required && value == null) {
                localError = "${field.label} is required."
                return
            }
            if (field.type in setOf("shortText", "longText") && value != null) {
                val length = value.jsonPrimitive.content.length
                if (length !in field.minLength..field.maxLength) {
                    localError = "${field.label} must be between ${field.minLength} and ${field.maxLength} characters."
                    return
                }
            }
            if (field.type == "multipleChoice" && value != null) {
                val count = value.jsonArray.size
                if (count !in field.minSelections..field.maxSelections) {
                    localError = "${field.label} requires ${field.minSelections} to ${field.maxSelections} choices."
                    return
                }
            }
        }
        localError = null
        onRespond(buildJsonObject {
            put("action", "accept")
            put("values", JsonObject(normalized))
        })
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (input.source == "mcp") "Requested by ${input.serverName ?: "MCP server"}" else "Codex needs input",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(input.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            input.message?.takeIf(String::isNotBlank)?.let { Text(it) }
            if (!input.supported) {
                Text(
                    input.unsupportedMessage ?: "This input schema is not supported in Foreman.",
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                input.fields.forEach { field ->
                    InputFieldControl(
                        field = field,
                        value = values[field.id],
                        other = otherValues[field.id].orEmpty(),
                        disabled = disabled,
                        onValue = { value -> values = values + (field.id to value) },
                        onOther = { value -> otherValues = otherValues + (field.id to value) },
                    )
                }
                Button(enabled = !disabled, onClick = ::submit) { Text("Submit") }
            }
            if (submitting || input.status == "submitting") Text("Submitting response…", color = MaterialTheme.colorScheme.primary)
            if (input.status == "resolved") Text(if (input.resolution == "resolvedElsewhere") "Already resolved in another client." else "Input resolved.")
            if (input.status == "expired") Text("This input request is no longer available.")
            (localError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (input.canDecline || input.canCancel) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (input.canDecline) OutlinedButton(enabled = !disabled, onClick = { onRespond(buildJsonObject { put("action", "decline") }) }) { Text("Decline") }
                    if (input.canCancel) OutlinedButton(enabled = !disabled, onClick = { onRespond(buildJsonObject { put("action", "cancel") }) }) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun InputFieldControl(
    field: InputField,
    value: JsonElement?,
    other: String,
    disabled: Boolean,
    onValue: (JsonElement) -> Unit,
    onOther: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(field.label, fontWeight = FontWeight.SemiBold)
        field.description?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (!field.required) Text("Optional", style = MaterialTheme.typography.labelSmall)
        when (field.type) {
            "shortText", "longText" -> OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = (value as? JsonPrimitive)?.contentOrNull.orEmpty(),
                enabled = !disabled,
                minLines = if (field.type == "longText") 4 else 1,
                maxLines = if (field.type == "longText") 8 else 1,
                visualTransformation = if (field.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                onValueChange = { if (it.length <= field.maxLength) onValue(JsonPrimitive(it)) },
            )
            "singleChoice" -> {
                field.options.forEach { option -> ChoiceRow(value?.jsonPrimitive?.contentOrNull == option.value, !disabled, option.label, option.description) { onValue(JsonPrimitive(option.value)) } }
                if (field.allowOther) {
                    ChoiceRow(value?.jsonPrimitive?.contentOrNull == "__other__", !disabled, "Other", null) { onValue(JsonPrimitive("__other__")) }
                    if (value?.jsonPrimitive?.contentOrNull == "__other__") OutlinedTextField(value = other, enabled = !disabled, onValueChange = { if (it.length <= field.maxLength) onOther(it) })
                }
            }
            "multipleChoice" -> {
                val selected = (value as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet().orEmpty()
                Text("Select ${field.minSelections} to ${field.maxSelections}.", style = MaterialTheme.typography.labelSmall)
                field.options.forEach { option ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = option.value in selected, enabled = !disabled, onCheckedChange = { checked -> onValue(buildJsonArray { (if (checked) selected + option.value else selected - option.value).forEach { add(JsonPrimitive(it)) } }) })
                        Column { Text(option.label); option.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
            "boolean", "confirmation" -> listOf(true, false).forEach { choice ->
                ChoiceRow(value?.jsonPrimitive?.booleanOrNull == choice, !disabled, if (field.type == "confirmation") if (choice) "Confirm" else "Do not confirm" else if (choice) "Yes" else "No", null) { onValue(JsonPrimitive(choice)) }
            }
        }
    }
}

@Composable
private fun ChoiceRow(selected: Boolean, enabled: Boolean, label: String, description: String?, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, enabled = enabled, onClick = onClick)
        Column { Text(label); description?.let { Text(it, style = MaterialTheme.typography.bodySmall) } }
    }
}
