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
    // The box shows the meaningful spec: the stored UDP range while the SSH
    // port is at its default (22), otherwise the custom SSH port. Without
    // this, a saved 1-65535 appeared to "revert to 22" on reopen even
    // though the range had saved.
    var port by remember {
        mutableStateOf(
            if (existing != null && existing.port == 22) existing.udpPortRange
            else (existing?.port ?: 22).toString()
        )
    }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var authMethod by remember { mutableStateOf(existing?.authMethod ?: VpnConfig.AuthMethod.PASSWORD) }
    var showPassword by remember { mutableStateOf(false) }

    var enableDns by remember { mutableStateOf(existing?.enableDns ?: true) }
    var preferredDns by remember { mutableStateOf(existing?.preferredDns ?: "8.8.8.8") }
    var alternateDns by remember { mutableStateOf(existing?.alternateDns ?: "8.8.4.4") }
    var udpPort by remember { mutableStateOf((existing?.udpGatewayPort ?: 7300).toString()) }
    var enableUdpState by remember { mutableStateOf(existing?.enableUdp ?: false) }
    var mtu by remember { mutableStateOf((existing?.mtu ?: 1400).toString()) }

    var enableMapDns by remember { mutableStateOf(existing?.enableMapDns ?: true) }
    var mapDnsNetwork by remember { mutableStateOf(existing?.mapDnsNetwork ?: "198.18.0.0") }
    var mapDnsNetmask by remember { mutableStateOf(existing?.mapDnsNetmask ?: "255.254.0.0") }

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
        // The one Port box, HTTP Custom style: a single number is the SSH
        // port; a lo-hi range is the UDP redirect list. Garbage shows an
        // error - it never quietly becomes 22.
        if (VpnConfig.parsePort(port) == null &&
            VpnConfig.parsePortRange(port) == null
        ) {
            e["port"] = "Port must be a number (SSH port, 22) or a range like 1-65535"
        }
        if (mtu.trim().toIntOrNull()?.takeIf { it in 68..65535 } == null) {
            e["mtu"] = "MTU must be a number from 68 to 65535"
        }
        if (VpnConfig.parsePort(udpPort) == null) {
            e["udpPort"] = "udpgw port must be a number from 1 to 65535"
        }
        // MapDNS needs exactly one DNS address to intercept, and a valid
        // contiguous fake-IP pool - validated, never silently defaulted.
        if (enableMapDns) {
            if (!enableDns) {
                e["enableMapDns"] = "MapDNS needs Custom DNS enabled above (one DNS address)"
            }
            if (VpnConfig.parseIPv4(mapDnsNetwork) == null) {
                e["mapDnsNetwork"] = "Fake-IP pool base must be a valid IPv4 address"
            }
            if (VpnConfig.parseNetmask(mapDnsNetmask) == null) {
                e["mapDnsNetmask"] = "Netmask must be contiguous, e.g. 255.254.0.0"
            }
        }
        errors = e
        return e.isEmpty()
    }

    fun save() {
        if (!validate()) return
        // validate() already showed errors for anything unparseable - these
        // guards exist so a default can NEVER be silently substituted, even
        // if the fields somehow changed between validate() and here.
        val single = VpnConfig.parsePort(port)
        // Only a dashed input sets the UDP list: a bare "22" must never
        // silently narrow the stored range to 22-22.
        val range = if (port.contains('-')) VpnConfig.parsePortRange(port) else null
        if (single == null && range == null) {
            errors = errors + ("port" to
                "Port must be a number (SSH port, 22) or a range like 1-65535")
            return
        }
        // A single number IS the SSH port; a range sets the UDP redirect
        // list and leaves the SSH port exactly as stored (22 for new
        // profiles) - one box, two meanings, nothing silently overwritten.
        val portNum = single ?: (existing?.port ?: 22)
        val rangeText = range?.let { "${it.first}-${it.last}" }
            ?: (existing?.udpPortRange ?: "1-65535")
        val mtuNum = mtu.trim().toIntOrNull()?.takeIf { it in 68..65535 }
            ?: run { errors = errors + ("mtu" to "MTU must be a number from 68 to 65535"); return }
        val udpNum = VpnConfig.parsePort(udpPort)
            ?: run { errors = errors + ("udpPort" to "udpgw port must be a number from 1 to 65535"); return }
        // Re-guards, same no-silent-default rule as the fields above.
        if (enableMapDns && !enableDns) {
            errors = errors + ("enableMapDns" to "MapDNS needs Custom DNS enabled above (one DNS address)")
            return
        }
        if (enableMapDns && VpnConfig.parseIPv4(mapDnsNetwork) == null) {
            errors = errors + ("mapDnsNetwork" to "Fake-IP pool base must be a valid IPv4 address")
            return
        }
        if (enableMapDns && VpnConfig.parseNetmask(mapDnsNetmask) == null) {
            errors = errors + ("mapDnsNetmask" to "Netmask must be contiguous, e.g. 255.254.0.0")
            return
        }
        val config = VpnConfig(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            host = host.trim(),
            port = portNum,
            username = username.trim(),
            password = password,
            authMethod = authMethod,
            privateKeyAlias = existing?.privateKeyAlias,
            enableDns = enableDns,
            preferredDns = preferredDns.trim(),
            alternateDns = alternateDns.trim(),
            enableUdp = enableUdpState,
            udpGatewayPort = udpNum,
            udpPortRange = rangeText,
            mtu = mtuNum,
            enableMapDns = enableMapDns,
            mapDnsNetwork = mapDnsNetwork.trim(),
            mapDnsNetmask = mapDnsNetmask.trim()
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
                    LabeledField("Port", errors["port"]) {
                        GoldTextField(
                            port, { port = it },
                            placeholder = "22 or 1-65535",
                            keyboardType = KeyboardType.Number
                        )
                    }
                }
            }
            Text(
                "One box, HTTP Custom style: a single number = the SSH " +
                    "port (22); a range like 1-65535 = the UDP redirect list",
                color = MidGray, fontSize = 11.sp
            )
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
                    Column(Modifier.weight(1f)) {
                        Text("MapDNS (DNS without UDP)", color = Color.White, fontSize = 13.sp)
                        Text(
                            "Answers DNS on-device with fake IPs; real lookups happen " +
                                "server-side over the SSH channel",
                            color = MidGray, fontSize = 11.sp
                        )
                    }
                    GoldSwitch(enableMapDns) { enableMapDns = it }
                }
                errors["enableMapDns"]?.let {
                    Text(it, color = ErrorRed, fontSize = 11.sp)
                }
                if (enableMapDns) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.weight(1f)) {
                            LabeledField("Fake-IP pool base", errors["mapDnsNetwork"]) {
                                GoldTextField(mapDnsNetwork, { mapDnsNetwork = it })
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            LabeledField("Pool netmask", errors["mapDnsNetmask"]) {
                                GoldTextField(mapDnsNetmask, { mapDnsNetmask = it })
                            }
                        }
                    }
                    Text(
                        "Default 198.18.0.0/15 is a reserved test range - fake-IP " +
                            "leaks are detected against this pool",
                        color = MidGray, fontSize = 11.sp
                    )
                }

                Divider(color = CardBorder)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("UDP Custom (udpgw client)", color = Color.White, fontSize = 13.sp)
                        Text(
                            "Tunnels app UDP through the VPS's udpgw - via the SSH " +
                                "session when the provider allows it, otherwise direct " +
                                "to the VPS port range (unencrypted, HTTP Custom style). " +
                                "Needs a udpgw server on your VPS.",
                            color = MidGray, fontSize = 11.sp
                        )
                    }
                    GoldSwitch(enableUdpState) { enableUdpState = it }
                }
                LabeledField("udpgw Port (the VPS's UDP door - probe + client)", errors["udpPort"]) {
                    GoldTextField(udpPort, { udpPort = it }, keyboardType = KeyboardType.Number)
                }

                Divider(color = CardBorder)

                LabeledField("MTU", errors["mtu"]) {
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
