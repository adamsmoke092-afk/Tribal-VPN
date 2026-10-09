package com.tribal.vpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tribal.vpn.data.VpnConfig

private val Gold = Color(0xFFF5B700)
private val Black = Color(0xFF000000)
private val DarkGray = Color(0xFF111114)
private val CardBorder = Color(0xFF1F1F23)
private val MidGray = Color(0xFF6B6B70)

/**
 * Lists all persisted profiles from ConfigRepository (via the ViewModel) -
 * no demo/placeholder profiles are shown here. An empty list renders an
 * empty state, not fake sample data.
 */
@Composable
fun ProfilesScreen(
    viewModel: VpnViewModel,
    onAddProfile: () -> Unit,
    onEditProfile: (VpnConfig) -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Profiles", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            IconButton(
                onClick = onAddProfile,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Gold)
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Add profile", tint = Black)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (profiles.isEmpty()) {
            EmptyProfilesState(onAddProfile)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == activeProfileId,
                        onSelect = { viewModel.setActiveProfile(profile.id) },
                        onEdit = { onEditProfile(profile) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyProfilesState(onAddProfile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(16.dp))
            .clickable { onAddProfile() }
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = MidGray, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Text("Add your first profile", color = MidGray, fontSize = 14.sp)
    }
}

@Composable
private fun ProfileCard(
    profile: VpnConfig,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(DarkGray, RoundedCornerShape(16.dp))
            .border(1.dp, if (isActive) Gold.copy(alpha = 0.5f) else CardBorder, RoundedCornerShape(16.dp))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(profile.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(DarkGray, RoundedCornerShape(50))
                            .border(1.dp, Gold.copy(alpha = 0.4f), RoundedCornerShape(50))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("ACTIVE", color = Gold, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("${profile.username}@${profile.host}", color = MidGray, fontSize = 12.sp)
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit ${profile.name}", tint = MidGray)
        }
    }
}
