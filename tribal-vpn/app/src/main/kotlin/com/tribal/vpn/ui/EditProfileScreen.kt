package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tribal.vpn.data.VpnConfig
import java.util.UUID

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val CardBorder = Color(0xFF1F1F23)
private val MidGray = Color(0xFF6B6B70)
private val ErrorRed = Color(0xFFCF4A4A)

/**
 * Form state is local UI state (obviously - it's an unsaved edit), but the
 * ONLY way it becomes durable is via viewModel.saveProfile(), which writes
 * through to EncryptedSharedPreferences. There is no separate "demo save"
 * path.
 */
@Composable
fun EditProfileScreen(
    viewModel: VpnViewModel,
    existing: VpnConfig?,
    onDone: () -> Unit
) {
    val isNew = existing == null
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 22).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var authMethod by remember { mutableStateOf(existing?.authMethod ?: VpnConfig.AuthMethod.PASSWORD) }
    var showPassword by remember { mutableStateOf(false) }

    var enableDns by remember { mutableStateOf(existing?.enableDns ?: true) }
    var preferredDns by remember { mutableStateOf(existing?.preferredDns ?: "8.8.8.8") }
    var alternateDns by remember { mutableStateOf(existing?.alternateDns ?: "8.8.4.4") }
    var enableUdp by remember { mutableStateOf(existing?.enableUdp ?: false) }
    var udpPort by remember { mutableStateOf((existing?.udpGatewayPort ?: 7300).toString()) }
    var mtu by remember { mutableStateOf((existing?.mtu ?: 1400).toString()) }

    var showAdvanced by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }

    fun validate(): Boolean {
        val e = mutableMapOf<String, String>()
        if (name.isBlank()) e["name"] = "Give this profile a name"
        if (host.isBlank()) e["host"] = "Host is required"
        if (username.isBlank()) e["username"] = "Username is required"
        if (authMethod == VpnConfig.AuthMethod.PASSWORD && password.isBlank()) {
            e["password"] = "Password is required"
        }
        errors = e
        return e.isEmpty()
    }

    fun save() {
        if (!validate()) return
        val config = VpnConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            port = port.toIntOrNull() ?: 22,
            username = username.trim(),
            password = password,
            authMethod = authMethod,
            privateKeyAlias = existing?.privateKeyAlias,
            enableDns = enableDns,
            preferredDns = preferredDns.trim(),
            alternateDns = alternateDns.trim(),
            enableUdp = enableUdp,
            udpGatewayPort = udpPort.toIntOrNull() ?: 7300,
            mtu = mtu.toIntOrNull() ?: 1400
        )
        viewModel.saveProfile(config)
        if (isNew) viewModel.setActiveProfile(config.id)
        onDone()
    }

    fun remove() {
        existing?.let { viewModel.deleteProfile(it.id) }
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                if (isNew) "New Profile" else "Edit Profile",
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        SectionCard {
            LabeledField("Profile Name", errors["name"]) {
                GoldTextField(name, { name = it }, placeholder = "e.g. Home VPS")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(2f)) {
                    LabeledField("Host", errors["host"]) {
                        GoldTextField(host, { host = it }, placeholder = "vps.example.com")
                    }
                }
                Box(Modifier.weight(1f)) {
                    LabeledField("Port", null) {
                        GoldTextField(port, { port = it }, keyboardType = KeyboardType.Number)
                    }
                }
            }
            LabeledField("Username", errors["username"]) {
                GoldTextField(username, { username = it }, placeholder = "root")
            }

            Text("Authentication", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuthMethodButton(
                    label = "Password", icon = Icons.Filled.Lock,
                    selected = authMethod == VpnConfig.AuthMethod.PASSWORD,
                    onClick = { authMethod = VpnConfig.AuthMethod.PASSWORD },
                    modifier = Modifier.weight(1f)
                )
                AuthMethodButton(
                    label = "SSH Key", icon = Icons.Filled.Key,
                    selected = authMethod == VpnConfig.AuthMethod.KEY,
                    onClick = { authMethod = VpnConfig.AuthMethod.KEY },
                    modifier = Modifier.weight(1f)
                )
            }

            if (authMethod == VpnConfig.AuthMethod.PASSWORD) {
                LabeledField("Password", errors["password"]) {
                    GoldTextField(
                        value = password,
                        onChange = { password = it },
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle password visibility",
                                    tint = MidGray
                                )
                            }
                        }
                    )
                }
                Text(
                    "Stored encrypted, unlocked with device biometrics",
                    color = MidGray, fontSize = 11.sp
                )
            } else {
                Text(
                    "SSH key import is not yet implemented (see README_DATA_INTEGRITY.md)",
                    color = MidGray, fontSize = 12.sp
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Advanced", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("DNS, UDP Custom, MTU", color = MidGray, fontSize = 11.sp)
                }
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null, tint = MidGray
                )
            }

            if (showAdvanced) {
                Divider(color = CardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enable Custom DNS", color = Color.White, fontSize = 13.sp)
                    GoldSwitch(enableDns) { enableDns = it }
                }
                if (enableDns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            LabeledField("Preferred DNS", null) {
                                GoldTextField(preferredDns, { preferredDns = it })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            LabeledField("Alternate DNS", null) {
                                GoldTextField(alternateDns, { alternateDns = it })
                            }
                        }
                    }
                }

                Divider(color = CardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("UDP Custom", color = Color.White, fontSize = 13.sp)
                        Text("Needs badvpn-udpgw on your VPS", color = MidGray, fontSize = 11.sp)
                    }
                    GoldSwitch(enableUdp) { enableUdp = it }
                }
                if (enableUdp) {
                    LabeledField("UDPGW Port", null) {
                        GoldTextField(udpPort, { udpPort = it }, keyboardType = KeyboardType.Number)
                    }
                }

                Divider(color = CardBorder)

                LabeledField("MTU", null) {
                    GoldTextField(mtu, { mtu = it }, keyboardType = KeyboardType.Number)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!isNew) {
                OutlinedButton(
                    onClick = { remove() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed)
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete profile")
                }
            }
            Button(
                onClick = { save() },
                colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Black),
                modifier = Modifier.weight(1f)
            ) {
                Text("Save Profile", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
private fun LabeledField(label: String, error: String?, field: @Composable () -> Unit) {
    Column {
        Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        field()
        if (error != null) {
            Text(error, color = ErrorRed, fontSize = 11.sp)
        }
    }
}

@Composable
private fun GoldTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = placeholder?.let { { Text(it, color = MidGray) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Gold,
            unfocusedBorderColor = CardBorder,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = Gold
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun AuthMethodButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) Gold else Color.Transparent
    val fg = if (selected) Black else MidGray
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) Gold else CardBorder, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun GoldSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Black,
            checkedTrackColor = Gold,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = CardBorder
        )
    )
}
