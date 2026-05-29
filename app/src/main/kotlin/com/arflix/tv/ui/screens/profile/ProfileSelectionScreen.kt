package com.arflix.tv.ui.screens.profile

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.arflix.tv.data.model.Profile
import com.arflix.tv.ui.components.ProfileAvatarVisual
import com.arflix.tv.ui.components.Toast
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.LocalDeviceType
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.filled.Dns
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import com.arflix.tv.R

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ProfileSelectionScreen(
    viewModel: ProfileViewModel = hiltViewModel(),
    onProfileSelected: () -> Unit,
    onShowAddProfile: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Create focus requesters for each profile slot (max 5 profiles + 1 add button)
    val focusRequesters = remember { List(6) { FocusRequester() } }

    // Track if profile was selected in this session to trigger navigation
    var navigateTriggered by remember { mutableStateOf(false) }

    // Guard against Enter key events from previous screen (TV only — touch devices don't need this)
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    var isReadyForInput by remember { mutableStateOf(isTouchDevice) }

    // Set ready for input after a short delay to ignore stray key events (TV only)
    LaunchedEffect(Unit) {
        if (!isTouchDevice) {
            delay(300)
            isReadyForInput = true
        }
    }

    // Reset input guard when dialogs close (TV only)
    LaunchedEffect(uiState.showAddDialog, uiState.editingProfile) {
        if (!isTouchDevice && !uiState.showAddDialog && uiState.editingProfile == null && isReadyForInput) {
            isReadyForInput = false
            delay(300)
            isReadyForInput = true
        }
    }

    // Navigate when activeProfile changes after user selection
    LaunchedEffect(uiState.activeProfile?.id, uiState.isSwitchingProfile) {
        if (
            navigateTriggered &&
            uiState.activeProfile != null &&
            !uiState.isManageMode &&
            !uiState.isSwitchingProfile
        ) {
            onProfileSelected()
        }
    }

    // Request focus on the first available item (profile or add button)
    LaunchedEffect(uiState.isLoading) {
        if (!uiState.isLoading) {
            // Brief delay for layout, with retry logic
            delay(50)
            val targetIndex = if (uiState.profiles.isNotEmpty()) {
                uiState.activeProfile?.let { active ->
                    uiState.profiles.indexOfFirst { it.id == active.id }.takeIf { it >= 0 }
                } ?: 0
            } else {
                0 // Focus on Add Profile button
            }
            try {
                focusRequesters.getOrNull(targetIndex)?.requestFocus()
            } catch (e: IllegalStateException) {
                // Retry after a bit more time
                delay(100)
                try {
                    focusRequesters.getOrNull(targetIndex)?.requestFocus()
                } catch (e2: IllegalStateException) {
                    // Give up silently
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "ARVIO",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 6.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (uiState.isManageMode) stringResource(R.string.manage_profiles) else stringResource(R.string.whos_watching),
                fontSize = 20.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(48.dp))

            // Profile avatars row
            val avatarSize = if (isTouchDevice) 90.dp else 120.dp
            val avatarSpacing = if (isTouchDevice) 16.dp else 24.dp

            if (isTouchDevice) {
                // Mobile: use LazyRow so profiles scroll horizontally on small screens
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(avatarSpacing, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    itemsIndexed(uiState.profiles) { index, profile ->
                        ProfileAvatar(
                            profile = profile,
                            isManageMode = uiState.isManageMode,
                            isActiveProfile = uiState.activeProfile?.id == profile.id,
                            avatarSize = avatarSize,
                            modifier = Modifier.focusRequester(focusRequesters[index]),
                            onClick = {
                                if (uiState.isSwitchingProfile) return@ProfileAvatar
                                if (uiState.isManageMode) {
                                    viewModel.showEditDialog(profile)
                                } else {
                                    navigateTriggered = true
                                    viewModel.selectProfileWithLockCheck(profile)
                                }
                            },
                            onFocus = { viewModel.preloadForProfile(profile) },
                            onDelete = { viewModel.deleteProfile(profile) }
                        )
                    }

                    // Add profile button (max 5 profiles)
                    if (uiState.profiles.size < 5) {
                        item {
                            AddProfileButton(
                                avatarSize = avatarSize,
                                modifier = Modifier.focusRequester(focusRequesters[uiState.profiles.size]),
                                onClick = { viewModel.showAddDialog() }
                            )
                        }
                    }
                }
            } else {
                // TV: original Row layout with fixed spacing
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    uiState.profiles.forEachIndexed { index, profile ->
                        ProfileAvatar(
                            profile = profile,
                            isManageMode = uiState.isManageMode,
                            isActiveProfile = uiState.activeProfile?.id == profile.id,
                            avatarSize = avatarSize,
                            modifier = Modifier.focusRequester(focusRequesters[index]),
                            onClick = {
                                // Guard against stray Enter key events from previous screen
                                if (!isReadyForInput || uiState.isSwitchingProfile) return@ProfileAvatar

                                if (uiState.isManageMode) {
                                    viewModel.showEditDialog(profile)
                                } else {
                                    navigateTriggered = true
                                    viewModel.selectProfileWithLockCheck(profile)
                                }
                            },
                            onFocus = { viewModel.preloadForProfile(profile) },
                            onDelete = { viewModel.deleteProfile(profile) }
                        )

                        if (index < uiState.profiles.size - 1 || uiState.profiles.size < 5) {
                            Spacer(modifier = Modifier.width(avatarSpacing))
                        }
                    }

                    // Add profile button (max 5 profiles)
                    if (uiState.profiles.size < 5) {
                        AddProfileButton(
                            avatarSize = avatarSize,
                            modifier = Modifier.focusRequester(focusRequesters[uiState.profiles.size]),
                            onClick = { if (isReadyForInput && !uiState.isSwitchingProfile) viewModel.showAddDialog() }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Manage Profiles button
            ManageProfilesButton(
                isManageMode = uiState.isManageMode,
                onClick = {
                    if ((isTouchDevice || isReadyForInput) && !uiState.isSwitchingProfile) {
                        viewModel.toggleManageMode()
                    }
                }
            )

            if (!uiState.isSyncServerConnected) {
                Spacer(modifier = Modifier.height(24.dp))

                ConnectServerButton(
                    onClick = {
                        if ((isTouchDevice || isReadyForInput) && !uiState.isSwitchingProfile) {
                            viewModel.showConnectServerDialog()
                        }
                    }
                )
            }

            if (uiState.isSwitchingProfile) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(R.string.loading_profile),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.72f)
                )
            }
        }

        // Add Profile Dialog
        if (uiState.showAddDialog) {
            AddProfileDialog(
                name = uiState.newProfileName,
                onNameChange = { viewModel.setNewProfileName(it) },
                selectedColorIndex = uiState.selectedColorIndex,
                onColorSelected = { viewModel.setSelectedColorIndex(it) },
                selectedAvatarId = uiState.selectedAvatarId,
                onAvatarSelected = { viewModel.setSelectedAvatarId(it) },
                selectedAvatarImageUri = uiState.selectedAvatarImageUri,
                useCustomAvatarImage = uiState.useCustomAvatarImage,
                onAvatarImageSelected = { viewModel.setSelectedAvatarImage(it) },
                onRemoveAvatarImage = { viewModel.removeSelectedAvatarImage() },
                onConfirm = { viewModel.createProfile() },
                onDismiss = { viewModel.hideAddDialog() }
            )
        }

        // Edit Profile Dialog
        uiState.editingProfile?.let { profile ->
            EditProfileDialog(
                profile = profile,
                name = uiState.newProfileName,
                onNameChange = { viewModel.setNewProfileName(it) },
                selectedColorIndex = uiState.selectedColorIndex,
                onColorSelected = { viewModel.setSelectedColorIndex(it) },
                selectedAvatarId = uiState.selectedAvatarId,
                onAvatarSelected = { viewModel.setSelectedAvatarId(it) },
                selectedAvatarImageUri = uiState.selectedAvatarImageUri,
                useCustomAvatarImage = uiState.useCustomAvatarImage,
                onAvatarImageSelected = { viewModel.setSelectedAvatarImage(it) },
                onRemoveAvatarImage = { viewModel.removeSelectedAvatarImage() },
                onConfirm = { viewModel.updateProfile() },
                onDelete = { viewModel.deleteProfile(profile); viewModel.hideEditDialog() },
                onDismiss = { viewModel.hideEditDialog() },
                onShowPinSetup = { viewModel.showPinSetupDialog() },
                onRemovePin = { viewModel.removeProfilePin() }
            )
        }

        // Connect to Server dialog
        if (uiState.showConnectServerDialog) {
            ConnectServerDialog(
                url = uiState.connectServerUrl,
                onUrlChange = { viewModel.setConnectServerUrl(it) },
                isConnecting = uiState.isConnectingToServer,
                error = uiState.connectServerError,
                onConnect = { viewModel.connectToServer() },
                onSkip = { viewModel.dismissConnectServerDialog() }
            )
        }

        // Toast Notification
        Toast(
            message = uiState.toastMessage ?: "",
            type = uiState.toastType,
            isVisible = uiState.showToast,
            onDismiss = { viewModel.dismissToast() }
        )

        // PIN Entry Dialog
        if (uiState.showPinDialog) {
            if (uiState.pinDialogMode == "verify") {
                PinEntryDialog(
                    title = stringResource(R.string.enter_pin_to_unlock),
                    onPinConfirmed = { pin -> viewModel.verifyPinAndSelectProfile(pin) },
                    onDismiss = { viewModel.hidePinDialog() },
                    isSetup = false,
                    pinError = uiState.pinError
                )
            } else if (uiState.pinDialogMode == "setup") {
                PinEntryDialog(
                    title = stringResource(R.string.set_profile_pin),
                    onPinConfirmed = { pin -> viewModel.setupProfilePin(pin) },
                    onDismiss = { viewModel.hidePinDialog() },
                    isSetup = true
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ProfileAvatar(
    profile: Profile,
    isManageMode: Boolean,
    isActiveProfile: Boolean = false,
    avatarSize: Dp = 120.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onFocus: () -> Unit = {},
    onDelete: () -> Unit
) {
    var isFocused by remember { mutableIntStateOf(0) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused > 0) 1.1f else 1f,
        animationSpec = tween(150),
        label = "scale"
    )

    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        Box(contentAlignment = Alignment.Center) {
            val avatarContent: @Composable () -> Unit = {
                ProfileAvatarVisual(
                    profile = profile,
                    letterFontSize = 48.sp,
                    iconPadding = 12.dp
                )
            }

            if (isTouchDevice) {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .scale(scale)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onClick() }
                ) { avatarContent() }
            } else {
                Surface(
                    onClick = onClick,
                    modifier = Modifier
                        .size(avatarSize)
                        .scale(scale)
                        .onFocusChanged { focusState ->
                            val wasFocused = isFocused > 0
                            isFocused = if (focusState.isFocused) 1 else 0
                            if (!wasFocused && focusState.isFocused) {
                                onFocus()
                            }
                        },
                    shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = androidx.tv.material3.Border(
                            border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                            shape = RoundedCornerShape(8.dp)
                        )
                    )
                ) { avatarContent() }
            }

            // Edit icon overlay in manage mode
            if (isManageMode) {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.6f))
                        .then(if (isTouchDevice) Modifier.clickable { onClick() } else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused > 0) Color.White else Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddProfileButton(
    avatarSize: Dp = 120.dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableIntStateOf(0) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused > 0) 1.1f else 1f,
        animationSpec = tween(150),
        label = "scale"
    )

    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        val addContent: @Composable () -> Unit = {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
        }
        if (isTouchDevice) {
            Box(
                modifier = Modifier
                    .size(avatarSize)
                    .scale(scale)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.1f))
                    .border(2.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .clickable { onClick() }
            ) { addContent() }
        } else {
            Surface(
                onClick = onClick,
                modifier = Modifier
                    .size(avatarSize)
                    .scale(scale)
                    .onFocusChanged { isFocused = if (it.isFocused) 1 else 0 },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.1f),
                    focusedContainerColor = Color.White.copy(alpha = 0.2f)
                ),
                border = ClickableSurfaceDefaults.border(
                    border = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.3f)),
                        shape = RoundedCornerShape(8.dp)
                    ),
                    focusedBorder = androidx.tv.material3.Border(
                        border = androidx.compose.foundation.BorderStroke(3.dp, Color.White),
                        shape = RoundedCornerShape(8.dp)
                    )
                )
            ) { addContent() }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.add_profile),
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused > 0) Color.White else Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ManageProfilesButton(
    isManageMode: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableIntStateOf(0) }

    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    Surface(
        onClick = if (isTouchDevice) ({}) else onClick,
        modifier = Modifier
            .then(
                if (isTouchDevice) Modifier.clickable { onClick() } else Modifier
            )
            .onFocusChanged { isFocused = if (it.isFocused) 1 else 0 },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(4.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.1f)
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                shape = RoundedCornerShape(4.dp)
            ),
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                shape = RoundedCornerShape(4.dp)
            )
        )
    ) {
        Text(
            text = if (isManageMode) stringResource(R.string.done) else stringResource(R.string.manage_profiles),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectServerButton(
    onClick: () -> Unit
) {
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    var isFocused by remember { mutableIntStateOf(0) }

    if (isTouchDevice) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF6C63FF).copy(alpha = 0.25f),
                            Color(0xFF00D4FF).copy(alpha = 0.18f)
                        )
                    )
                )
                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(24.dp))
                .clickable { onClick() }
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Cloud,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.connect_to_server),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    } else {
        Surface(
            onClick = onClick,
            modifier = Modifier.onFocusChanged { isFocused = if (it.isFocused) 1 else 0 },
            shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(24.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.06f),
                focusedContainerColor = Color.White.copy(alpha = 0.18f)
            ),
            border = ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(24.dp)
                ),
                focusedBorder = androidx.tv.material3.Border(
                    border = androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.6f)),
                    shape = RoundedCornerShape(24.dp)
                )
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = if (isFocused > 0) Color.White else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.connect_to_server),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isFocused > 0) Color.White else Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectServerDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    isConnecting: Boolean,
    error: String?,
    onConnect: () -> Unit,
    onSkip: () -> Unit
) {
    val isTouchDevice = LocalDeviceType.current.isTouchDevice()
    var connectFocused by remember { mutableIntStateOf(0) }
    var skipFocused by remember { mutableIntStateOf(0) }
    val connectFocusRequester = remember { FocusRequester() }
    var editTextRef by remember { mutableStateOf<EditText?>(null) }

    // Auto-focus the URL field when the dialog opens
    LaunchedEffect(Unit) {
        delay(200)
        editTextRef?.requestFocus()
    }

    Dialog(
        onDismissRequest = onSkip,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .then(if (isTouchDevice) Modifier.fillMaxWidth(0.9f) else Modifier.wrapContentWidth())
                    .widthIn(min = 320.dp, max = 520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF141414))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Dns,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Connect to Server",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Enter your sync server URL to restore\nsettings on this device.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(22.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF222222))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                ) {
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                setText(url)
                                setTextColor(android.graphics.Color.WHITE)
                                setHintTextColor(android.graphics.Color.parseColor("#777777"))
                                hint = "http://192.168.1.x:5000"
                                textSize = 15f
                                background = null
                                setPadding(36, 28, 36, 28)
                                isSingleLine = true
                                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                                imeOptions = EditorInfo.IME_ACTION_GO
                                isFocusable = true
                                isFocusableInTouchMode = true
                                doAfterTextChanged { onUrlChange(it?.toString() ?: "") }
                                setOnEditorActionListener { _, actionId, _ ->
                                    if (actionId == EditorInfo.IME_ACTION_GO) { onConnect(); true } else false
                                }
                                // D-pad down escapes the text field and focuses the Connect button
                                setOnKeyListener { _, keyCode, event ->
                                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                                        connectFocusRequester.requestFocus()
                                        true
                                    } else false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        update = { et ->
                            editTextRef = et
                            if (et.text.toString() != url) { et.setText(url); et.setSelection(url.length) }
                        }
                    )
                }

                if (!error.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        fontSize = 12.sp,
                        color = Color(0xFFFF6B6B),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Connect button
                if (isTouchDevice) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isConnecting) Color.White.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.15f))
                            .clickable(enabled = !isConnecting) { onConnect() }
                            .padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isConnecting) "Connecting…" else "Connect",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isConnecting) Color.White.copy(alpha = 0.4f) else Color.White
                        )
                    }
                } else {
                    Surface(
                        onClick = { if (!isConnecting) onConnect() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(connectFocusRequester)
                            .onFocusChanged { connectFocused = if (it.isFocused) 1 else 0 },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.12f),
                            focusedContainerColor = Color.White.copy(alpha = 0.25f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(2.dp, Color.White),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isConnecting) "Connecting…" else "Connect",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isConnecting) Color.White.copy(alpha = 0.4f) else Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Skip link
                if (isTouchDevice) {
                    Text(
                        text = "Skip — set up manually",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier
                            .clickable(enabled = !isConnecting) { onSkip() }
                            .padding(8.dp)
                    )
                } else {
                    Surface(
                        onClick = { if (!isConnecting) onSkip() },
                        modifier = Modifier.onFocusChanged { skipFocused = if (it.isFocused) 1 else 0 },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(6.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            focusedContainerColor = Color.White.copy(alpha = 0.06f)
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = androidx.tv.material3.Border(
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                                shape = RoundedCornerShape(6.dp)
                            )
                        )
                    ) {
                        Text(
                            text = "Skip — set up manually",
                            fontSize = 13.sp,
                            color = if (skipFocused > 0) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.35f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
