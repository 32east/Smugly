package app.smugly.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VpnLock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.AppLanguage
import app.smugly.Config
import app.smugly.GlobalSettings
import app.smugly.S
import app.smugly.TunPlan
import app.smugly.TunSettings
import app.smugly.isDesktop
import app.smugly.t
import app.smugly.ui.components.CompactButton
import app.smugly.ui.components.ConfirmDialog
import app.smugly.ui.components.SettingsCard
import app.smugly.ui.components.SettingsDivider
import app.smugly.ui.components.SettingsFieldRow
import app.smugly.ui.components.SettingsGroup
import app.smugly.ui.components.SettingsGroupGap
import app.smugly.ui.components.SettingsRow
import app.smugly.ui.components.SettingsRowPadH
import app.smugly.ui.components.SettingsRowText
import app.smugly.ui.components.SettingsSwitchRow
import app.smugly.ui.components.PillSelector
import app.smugly.ui.components.SmuglyTextField
import app.smugly.ui.components.TopBar
import app.smugly.ui.components.handClickable
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary
import app.smugly.ui.theme.SmuglyTextSecondary

private val SettingsMaxWidth = 640.dp
private val InlineFieldWidth = 104.dp
private val CredentialsSideBySideMin = 280.dp

@Composable
fun SettingsScreen(
    settings: GlobalSettings,
    supportsVpn: Boolean,
    /** Android status-bar traffic notification; hidden on desktop/iOS. */
    showTrafficNotification: Boolean = false,
    /** Local proxy username/password; hidden where the platform cannot use it (desktop). */
    showLocalSocksAuth: Boolean = true,
    /** In-app updates from GitHub releases; hidden where the app installs some other way. */
    showUpdates: Boolean = false,
    /** What the package manager says is installed — not the constant compiled into the UI. */
    installedVersion: String = "",
    checkingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onMenu: () -> Unit,
    onChange: (GlobalSettings) -> Unit,
    accountName: String? = null,
    signedIn: Boolean = false,
    onSignIn: () -> Unit = {},
    onSignOut: () -> Unit = {},
    showTunSettings: Boolean = false,
    showAutoConnect: Boolean = false,
    onReset: () -> Unit = {},
    onBlockDrawerGestures: (Boolean) -> Unit = {},
    startWithAdvanced: Boolean = false,
    scrollState: ScrollState = rememberScrollState()
) {
    val languageOptions = listOf(
        t(S.LANGUAGE_SYSTEM) to AppLanguage.SYSTEM,
        "English" to AppLanguage.EN,
        "Русский" to AppLanguage.RU
    )
    // Same setting, different machinery: a VpnService on the phone, a TUN adapter on the
    // desktop. Calling it "VPN" on Windows would suggest something the app does not do.
    val modeOptions = listOf(
        t(S.CONNECTION_MODE_PROXY),
        t(if (app.smugly.currentHostPlatform().isDesktop()) S.CONNECTION_MODE_TUN else S.CONNECTION_MODE_VPN)
    )
    val modeIndex = if (settings.mode == Config.Mode.VPN) 1 else 0
    val langIndex = languageOptions.indexOfFirst { it.second == settings.language }.coerceAtLeast(0)
    var advancedOpen by remember { mutableStateOf(startWithAdvanced) }
    var advancedWarning by remember { mutableStateOf(false) }
    var resetConfirm by remember { mutableStateOf(false) }
    LaunchedEffect(advancedWarning || resetConfirm) {
        onBlockDrawerGestures(advancedWarning || resetConfirm)
    }
    DisposableEffect(Unit) {
        onDispose { onBlockDrawerGestures(false) }
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(SmuglyBg)) {
        TopBar(title = t(S.SETTINGS), onMenu = onMenu)
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(Modifier.widthIn(max = SettingsMaxWidth).fillMaxWidth()) {
                if (accountName != null) {
                    SettingsGroup(t(S.ACCOUNT_SECTION), Icons.Rounded.AccountCircle, first = true) {
                        AccountRow(accountName, signedIn, onSignIn, onSignOut)
                    }
                }
                SettingsGroup(t(S.SETTINGS_SECTION_GENERAL), Icons.Rounded.Settings, first = accountName == null) {
                    SettingsFieldRow(t(S.LANGUAGE)) {
                        PillSelector(
                            options = languageOptions.map { it.first },
                            selectedIndex = langIndex,
                            trackColor = SmuglyBg,
                            onSelected = { idx ->
                                onChange(settings.copy(language = languageOptions[idx].second))
                            }
                        )
                    }
                    if (showTrafficNotification) {
                        SettingsDivider()
                        SettingsSwitchRow(t(S.SHOW_TRAFFIC_NOTIFICATION), settings.trafficNotification) {
                            onChange(settings.copy(trafficNotification = it))
                        }
                    }
                    if (showAutoConnect) {
                        SettingsDivider()
                        SettingsSwitchRow(t(S.AUTO_CONNECT_ON_BOOT), settings.autoConnectOnBoot) {
                            onChange(settings.copy(autoConnectOnBoot = it))
                        }
                    }
                }
                if (showUpdates) {
                    SettingsGroup(t(S.UPDATES_SECTION), Icons.Rounded.SystemUpdate) {
                        SettingsSwitchRow(t(S.UPDATE_AUTO), settings.autoUpdate) {
                            onChange(settings.copy(autoUpdate = it))
                        }
                        SettingsDivider()
                        SettingsRow(t(S.UPDATE_CURRENT_VERSION), installedVersion) {
                            CompactButton(
                                if (checkingUpdate) t(S.UPDATE_CHECKING) else t(S.UPDATE_CHECK_BTN),
                                enabled = !checkingUpdate,
                                onClick = onCheckUpdate
                            )
                        }
                    }
                }
                Spacer(Modifier.height(SettingsGroupGap))
                AdvancedToggle(advancedOpen) {
                    if (advancedOpen) advancedOpen = false else advancedWarning = true
                }
                AnimatedVisibility(
                    visible = advancedOpen,
                    enter = fadeIn(tween(180)),
                    exit = ExitTransition.None
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        if (supportsVpn) {
                            SettingsGroup(t(S.SETTINGS_SECTION_CONNECTION), Icons.Rounded.VpnLock) {
                                SettingsFieldRow(t(S.CONNECTION_MODE)) {
                                    PillSelector(
                                        options = modeOptions,
                                        selectedIndex = modeIndex,
                                        trackColor = SmuglyBg,
                                        onSelected = { idx ->
                                            onChange(
                                                settings.copy(
                                                    mode = if (idx == 1) Config.Mode.VPN else Config.Mode.PROXY
                                                )
                                            )
                                        }
                                    )
                                }
                            }
                        }
                        if (showTunSettings) {
                            SettingsGroup(t(S.SETTINGS_SECTION_TUNNEL), Icons.Rounded.SettingsEthernet) {
                                BoundedNumberRow(
                                    title = t(S.TUN_MTU),
                                    value = settings.tun.mtu,
                                    range = TunSettings.MIN_MTU..TunSettings.MAX_MTU,
                                    rangeText = t(S.TUN_MTU_RANGE),
                                    onCommit = { onChange(settings.copy(tun = settings.tun.copy(mtu = it))) }
                                )
                                SettingsDivider()
                                SettingsSwitchRow(t(S.TUN_IPV6), settings.tun.ipv6) {
                                    onChange(settings.copy(tun = settings.tun.copy(ipv6 = it)))
                                }
                                SettingsDivider()
                                SettingsSwitchRow(t(S.TUN_BYPASS_LAN), settings.tun.bypassLan) {
                                    onChange(settings.copy(tun = settings.tun.copy(bypassLan = it)))
                                }
                                SettingsDivider()
                                SettingsFieldRow(t(S.TUN_DNS), t(S.TUN_DNS_HINT)) {
                                    SmuglyTextField(
                                        value = settings.tun.dnsServers,
                                        onValueChange = { onChange(settings.copy(tun = settings.tun.copy(dnsServers = it))) },
                                        hint = TunPlan.DEFAULT_DNS.joinToString(", ")
                                    )
                                    val invalid = TunPlan.invalidDnsEntries(settings.tun.dnsServers)
                                    if (invalid.isNotEmpty()) {
                                        FieldError("${t(S.TUN_DNS_INVALID)}: ${invalid.joinToString(", ")}")
                                    }
                                }
                            }
                        }
                        SettingsGroup(t(S.SETTINGS_SECTION_XRAY), Icons.Rounded.Route) {
                            SettingsSwitchRow(t(S.ROUTE_DIRECT_RU), settings.routing.directRu) {
                                onChange(settings.copy(routing = settings.routing.copy(directRu = it)))
                            }
                            SettingsDivider()
                            SettingsSwitchRow(t(S.ROUTE_BLOCK_ADS), settings.routing.blockAds) {
                                onChange(settings.copy(routing = settings.routing.copy(blockAds = it)))
                            }
                            if (supportsVpn) {
                                SettingsDivider()
                                SettingsSwitchRow(
                                    t(S.APP_HTTP_PROXY),
                                    settings.appHttpProxy,
                                    subtitle = t(S.APP_HTTP_PROXY_HINT)
                                ) {
                                    onChange(settings.copy(appHttpProxy = it))
                                }
                            }
                        }
                        SettingsGroup(t(S.SETTINGS_SECTION_LOCAL_PROXY), Icons.Rounded.Lan) {
                            SettingsRow(t(S.LOCAL_PORT)) {
                                SmuglyTextField(
                                    value = settings.listenPort.toString(),
                                    onValueChange = {
                                        onChange(settings.copy(listenPort = it.filter(Char::isDigit).toIntOrNull() ?: settings.listenPort))
                                    },
                                    modifier = Modifier.width(InlineFieldWidth),
                                    number = true
                                )
                            }
                            SettingsDivider()
                            SettingsSwitchRow(t(S.ALLOW_LAN), settings.allowLan, subtitle = t(S.ALLOW_LAN_HINT)) {
                                onChange(settings.copy(allowLan = it))
                            }
                            // Local SOCKS auth only where the platform can actually use it — see
                            // HostPlatform.supportsLocalProxyAuth().
                            if (showLocalSocksAuth) {
                                SettingsDivider()
                                SettingsSwitchRow(t(S.PROTECT_LOCAL_SOCKS), settings.localSocksAuthEnabled) {
                                    onChange(settings.copy(localSocksAuthEnabled = it))
                                }
                                if (settings.localSocksAuthEnabled) {
                                    SocksCredentialsRow(
                                        username = settings.localSocksUsername,
                                        password = settings.localSocksPassword,
                                        onUsername = { onChange(settings.copy(localSocksUsername = it)) },
                                        onPassword = { onChange(settings.copy(localSocksPassword = it)) }
                                    )
                                }
                            }
                        }
                        SettingsGroup(t(S.SETTINGS_SECTION_SLIPSTREAM), Icons.Rounded.Dns) {
                            SettingsFieldRow(t(S.DNS_RESOLVER_POOL), t(S.DNS_RESOLVER_POOL_HINT)) {
                                SmuglyTextField(
                                    value = settings.dnsResolverPool,
                                    onValueChange = { onChange(settings.copy(dnsResolverPool = it)) },
                                    singleLine = false,
                                    minLines = 5
                                )
                            }
                        }
                        SettingsGroup(t(S.SETTINGS_SECTION_DEBUG), Icons.Rounded.BugReport) {
                            SettingsSwitchRow(t(S.ENABLE_DEBUG_MODE), settings.fileLogging, subtitle = t(S.DEBUG_MODE_HINT)) {
                                onChange(settings.copy(fileLogging = it))
                            }
                        }
                        Spacer(Modifier.height(SettingsGroupGap))
                        SettingsCard {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .handClickable { resetConfirm = true }
                                    .padding(horizontal = SettingsRowPadH, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.RestartAlt,
                                    contentDescription = null,
                                    tint = SmuglyAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(t(S.RESET_SETTINGS_BTN), color = SmuglyAccent, fontSize = 15.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    ConfirmDialog(
        visible = advancedWarning,
        title = t(S.SETTINGS_ADVANCED_WARNING_TITLE),
        message = t(S.SETTINGS_ADVANCED_WARNING),
        confirmLabel = t(S.CONTINUE_BTN),
        cancelLabel = t(S.CANCEL_BTN),
        onConfirm = {
            advancedWarning = false
            advancedOpen = true
        },
        onDismiss = { advancedWarning = false }
    )
    ConfirmDialog(
        visible = resetConfirm,
        title = t(S.RESET_SETTINGS_TITLE),
        message = t(S.RESET_SETTINGS_TEXT),
        confirmLabel = t(S.RESET_BTN),
        cancelLabel = t(S.CANCEL_BTN),
        onConfirm = {
            resetConfirm = false
            onReset()
        },
        onDismiss = { resetConfirm = false }
    )
    }
}

@Composable
private fun AccountRow(
    accountName: String,
    signedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = SettingsRowPadH, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            when {
                !signedIn -> Text(t(S.ACCOUNT_NOT_SIGNED_IN), color = SmuglyTextPrimary, fontSize = 15.sp)
                accountName.isBlank() -> Text(t(S.TOAST_ACCOUNT_SIGNED_IN), color = SmuglyTextPrimary, fontSize = 15.sp)
                else -> {
                    Text(t(S.ACCOUNT_SIGNED_IN_AS), color = SmuglyTextMuted, fontSize = 12.5.sp, lineHeight = 16.sp)
                    Text(
                        accountName,
                        color = SmuglyTextPrimary,
                        fontSize = 15.sp,
                        lineHeight = 19.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        if (signedIn) {
            CompactButton(t(S.SIGN_OUT_SHORT), onClick = onSignOut)
        } else {
            CompactButton(t(S.SIGN_IN_BTN), filled = true, onClick = onSignIn)
        }
    }
}

@Composable
private fun AdvancedToggle(open: Boolean, onClick: () -> Unit) {
    val rotation by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(240, easing = FastOutSlowInEasing),
        label = "advancedChevron"
    )
    SettingsCard {
        Row(
            Modifier
                .fillMaxWidth()
                .handClickable(onClick = onClick)
                .padding(horizontal = SettingsRowPadH, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Tune, contentDescription = null, tint = SmuglyAccent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            SettingsRowText(t(S.SETTINGS_ADVANCED_WARNING_TITLE), null, Modifier.weight(1f))
            Icon(
                Icons.Rounded.KeyboardArrowDown,
                contentDescription = null,
                tint = SmuglyTextSecondary,
                modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

@Composable
private fun BoundedNumberRow(
    title: String,
    value: Int,
    range: IntRange,
    rangeText: String,
    onCommit: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in range } == true
    SettingsRow(title, rangeText, subtitleColor = if (valid) SmuglyTextMuted else SmuglyAccent) {
        SmuglyTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter(Char::isDigit).take(5)
                text.toIntOrNull()?.takeIf { it in range }?.let { if (it != value) onCommit(it) }
            },
            modifier = Modifier.width(InlineFieldWidth),
            number = true
        )
    }
}

@Composable
private fun SocksCredentialsRow(
    username: String,
    password: String,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(start = SettingsRowPadH, end = SettingsRowPadH, bottom = 14.dp)
    ) {
        if (maxWidth >= CredentialsSideBySideMin * fontScale) {
            Row(Modifier.fillMaxWidth()) {
                CredentialField(t(S.SOCKS_USERNAME), username, onUsername, password = false, Modifier.weight(1f))
                Spacer(Modifier.width(10.dp))
                CredentialField(t(S.SOCKS_PASSWORD), password, onPassword, password = true, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                CredentialField(t(S.SOCKS_USERNAME), username, onUsername, password = false, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                CredentialField(t(S.SOCKS_PASSWORD), password, onPassword, password = true, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    password: Boolean,
    modifier: Modifier
) {
    Column(modifier) {
        Text(label, color = SmuglyTextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
        SmuglyTextField(value, onValueChange, password = password)
    }
}

@Composable
private fun FieldError(text: String) {
    Text(
        text = text,
        color = SmuglyAccent,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 4.dp)
    )
}
