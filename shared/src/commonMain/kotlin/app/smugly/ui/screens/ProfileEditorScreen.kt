package app.smugly.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.smugly.Config
import app.smugly.S
import app.smugly.XrayForm
import app.smugly.XrayFormProtocol
import app.smugly.effectiveS3fuToml
import app.smugly.t
import app.smugly.ui.EditorDraft
import app.smugly.ui.PlatformBackHandler
import app.smugly.ui.components.CompactButton
import app.smugly.ui.components.JsonSyntaxHighlightTransformation
import app.smugly.ui.components.MenuLayer
import app.smugly.ui.components.MenuRow
import app.smugly.ui.components.PillSelector
import app.smugly.ui.components.ProfileNameField
import app.smugly.ui.components.SettingsCard
import app.smugly.ui.components.SettingsDivider
import app.smugly.ui.components.SettingsDropdownRow
import app.smugly.ui.components.SettingsFieldRow
import app.smugly.ui.components.SettingsGroup
import app.smugly.ui.components.SettingsRow
import app.smugly.ui.components.SettingsRowPadH
import app.smugly.ui.components.SettingsSwitchRow
import app.smugly.ui.components.SmuglyTextField
import app.smugly.ui.components.TomlSyntaxHighlightTransformation
import app.smugly.ui.components.TopBar
import app.smugly.ui.theme.SmuglyAccent
import app.smugly.ui.theme.SmuglyBg
import app.smugly.ui.theme.SmuglyInput
import app.smugly.ui.theme.SmuglyStroke
import app.smugly.ui.theme.SmuglyTextMuted
import app.smugly.ui.theme.SmuglyTextPrimary

private val DnsQueryTypeOptions = listOf(
    "TXT (16)" to 16,
    "NULL (10)" to 10,
    "HTTPS (65)" to 65
)

private val EditorMaxWidth = 640.dp
private val EditorInlineFieldWidth = 104.dp

private val NetworkLabels = mapOf(
    "tcp" to "TCP (RAW)",
    "ws" to "WebSocket",
    "grpc" to "gRPC",
    "xhttp" to "XHTTP",
    "httpupgrade" to "HTTPUpgrade",
    "kcp" to "mKCP"
)

private class EditorMenu(
    val options: List<Pair<String, String>>,
    val selected: String,
    val anchorY: Int,
    val onPick: (String) -> Unit
)

private class HeaderParams(
    val name: String,
    val onName: (String) -> Unit,
    val protocolLabel: String,
    val onProtocol: (Int) -> Unit,
    val inputMode: Boolean?,
    val onInputMode: (Boolean) -> Unit
)

private typealias OpenMenu = (options: List<Pair<String, String>>, selected: String, anchorY: Int, onPick: (String) -> Unit) -> Unit

@Composable
fun ProfileEditorScreen(
    draft: EditorDraft,
    onBack: () -> Unit,
    onChange: (EditorDraft) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onLocalDns: () -> Unit,
    formatXray: (String) -> String? = { null },
    xrayFormToJson: (XrayForm) -> String? = { null },
    xrayFormFromJson: (String) -> XrayForm? = { null },
    scrollState: ScrollState = rememberScrollState()
) {
    val c = draft.config
    val protocols = listOf(
        Config.TunnelProtocol.SLIPSTREAM to t(S.PROTOCOL_SLIPSTREAM),
        Config.TunnelProtocol.S3FU to t(S.PROTOCOL_S3FU),
        Config.TunnelProtocol.TURNFU to t(S.PROTOCOL_TURNFU),
        Config.TunnelProtocol.XRAY to t(S.PROTOCOL_XRAY)
    )
    var menu by remember { mutableStateOf<EditorMenu?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    val screenOrigin = remember { intArrayOf(0, 0) }

    PlatformBackHandler(enabled = menuOpen) { menuOpen = false }

    val openMenu: OpenMenu = { options, selected, anchorY, onPick ->
        menu = EditorMenu(options, selected, anchorY - screenOrigin[1], onPick)
        menuOpen = true
    }
    val isXray = c.protocol == Config.TunnelProtocol.XRAY
    val manualXray = isXray && c.xrayManual
    val isCodeProfile = !manualXray && c.protocol != Config.TunnelProtocol.SLIPSTREAM &&
        c.protocol != Config.TunnelProtocol.TURNFU

    val setInputMode: (Boolean) -> Unit = { manual ->
        if (manual != c.xrayManual) {
            var next = draft.copy(config = c.copy(xrayManual = manual))
            if (manual && draft.xrayForm == XrayForm() && c.xrayConfigJson.isNotBlank()) {
                xrayFormFromJson(c.xrayConfigJson)?.let { next = next.copy(xrayForm = it) }
            }
            if (!manual && c.xrayConfigJson.isBlank() && draft.xrayForm.missingField() == null) {
                xrayFormToJson(draft.xrayForm)?.let { next = next.copy(config = next.config.copy(xrayConfigJson = it)) }
            }
            onChange(next)
        }
    }

    val header = remember { movableContentOf { p: HeaderParams -> ProfileHeaderCard(p) } }
    val headerParams = HeaderParams(
        name = draft.name,
        onName = { onChange(draft.copy(name = it)) },
        protocolLabel = protocols.first { it.first == c.protocol }.second,
        onProtocol = { y ->
            openMenu(protocols.map { it.first.name to it.second }, c.protocol.name, y) { picked ->
                val protocol = Config.TunnelProtocol.valueOf(picked)
                val becomesXray = protocol == Config.TunnelProtocol.XRAY && c.protocol != Config.TunnelProtocol.XRAY
                val manual = if (becomesXray && draft.profileId == null) true else c.xrayManual
                onChange(draft.copy(config = c.copy(protocol = protocol, xrayManual = manual)))
            }
        },
        inputMode = if (isXray && draft.profileId == null) c.xrayManual else null,
        onInputMode = setInputMode
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(SmuglyBg)
            .onGloballyPositioned {
                val root = it.positionInRoot()
                screenOrigin[0] = root.x.toInt()
                screenOrigin[1] = root.y.toInt()
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar(
                title = if (draft.profileId == null) t(S.NEW_PROFILE_TITLE) else t(S.EDIT_PROFILE_TITLE),
                onBack = onBack,
                onConfirm = onSave,
                onDeleteAction = onDelete
            )
            if (isCodeProfile) {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(Modifier.widthIn(max = EditorMaxWidth).fillMaxWidth()) { header(headerParams) }
                }
                val editorModifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
                when (c.protocol) {
                    Config.TunnelProtocol.S3FU -> CodeEditor(
                        text = c.effectiveS3fuToml(),
                        onTextChange = { onChange(draft.copy(config = c.copy(s3fuToml = it))) },
                        placeholder = "endpoint = \"https://…\"",
                        highlight = TomlSyntaxHighlightTransformation,
                        format = { null },
                        modifier = editorModifier
                    )
                    else -> CodeEditor(
                        text = c.xrayConfigJson,
                        onTextChange = { onChange(draft.copy(config = c.copy(xrayConfigJson = it))) },
                        placeholder = "{}",
                        highlight = JsonSyntaxHighlightTransformation,
                        format = formatXray,
                        modifier = editorModifier
                    )
                }
            } else {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Column(Modifier.widthIn(max = EditorMaxWidth).fillMaxWidth()) {
                        header(headerParams)
                        if (manualXray) {
                            XrayFormEditor(
                                form = draft.xrayForm,
                                onForm = { onChange(draft.copy(xrayForm = it)) },
                                openMenu = openMenu
                            )
                        } else if (c.protocol == Config.TunnelProtocol.TURNFU) {
                            TurnfuEditor(c = c, onChange = { onChange(draft.copy(config = it)) })
                        } else {
                            SlipstreamEditor(
                                c = c,
                                onChange = { onChange(draft.copy(config = it)) },
                                onLocalDns = onLocalDns
                            )
                        }
                    }
                }
            }
        }

        MenuLayer(
            visible = menuOpen,
            anchorY = menu?.anchorY ?: 0,
            onDismiss = { menuOpen = false }
        ) {
            menu?.let { m ->
                m.options.forEach { (value, label) ->
                    MenuRow(label, selected = value == m.selected) {
                        menuOpen = false
                        m.onPick(value)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(p: HeaderParams) {
    val inputMode = p.inputMode
    Spacer(Modifier.height(10.dp))
    SettingsCard {
        SettingsFieldRow(t(S.PROFILE_NAME)) {
            ProfileNameField(name = p.name, onNameChange = p.onName)
        }
        SettingsDivider()
        SettingsDropdownRow(t(S.PROTOCOL), p.protocolLabel, onClick = p.onProtocol)
        if (inputMode != null) {
            SettingsDivider()
            SettingsFieldRow(t(S.XRAY_INPUT)) {
                PillSelector(
                    options = listOf("JSON", t(S.XRAY_INPUT_MANUAL)),
                    selectedIndex = if (inputMode) 1 else 0,
                    trackColor = SmuglyBg,
                    onSelected = { p.onInputMode(it == 1) }
                )
            }
        }
    }
}

@Composable
private fun Pills(title: String, options: List<String>, selected: Int, onSelected: (Int) -> Unit) {
    SettingsFieldRow(title) {
        PillSelector(options = options, selectedIndex = selected, trackColor = SmuglyBg, onSelected = onSelected)
    }
}

@Composable
private fun TextRow(
    title: String,
    value: String,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onValue: (String) -> Unit
) {
    SettingsFieldRow(title) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SmuglyTextField(value, onValue, Modifier.weight(1f), password = password)
            trailing?.invoke()
        }
    }
}

@Composable
private fun NumberRow(title: String, value: Int, onValue: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    SettingsRow(title) {
        SmuglyTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter(Char::isDigit).take(6)
                text.toIntOrNull()?.let { if (it != value) onValue(it) }
            },
            modifier = Modifier.width(EditorInlineFieldWidth),
            number = true
        )
    }
}

@Composable
private fun TurnfuEditor(c: Config, onChange: (Config) -> Unit) {
    SettingsGroup(t(S.TURNFU_SERVER), Icons.Rounded.Dns) {
        TextRow(t(S.FIELD_ADDRESS), c.turnfuHost) { onChange(c.copy(turnfuHost = it.trim())) }
        SettingsDivider()
        NumberRow(t(S.FIELD_PORT), c.turnfuPort) { onChange(c.copy(turnfuPort = it)) }
        SettingsDivider()
        TextRow(t(S.PASSWORD), c.turnfuPassword, password = true) { onChange(c.copy(turnfuPassword = it.trim())) }
    }
    SettingsGroup(t(S.EDITOR_SECTION_ADVANCED), Icons.Rounded.Tune) {
        NumberRow(t(S.TURNFU_WORKERS), c.turnfuWorkers) { onChange(c.copy(turnfuWorkers = it)) }
        SettingsDivider()
        NumberRow(t(S.TURNFU_MTU), c.turnfuMtu) { onChange(c.copy(turnfuMtu = it)) }
    }
}

@Composable
private fun SlipstreamEditor(
    c: Config,
    onChange: (Config) -> Unit,
    onLocalDns: () -> Unit
) {
    val manualDns = c.resolverMode == Config.ResolverMode.MANUAL
    SettingsGroup(t(S.EDITOR_SECTION_SERVER), Icons.Rounded.Dns) {
        TextRow(t(S.DOMAIN), c.domain) { onChange(c.copy(domain = it)) }
    }
    SettingsGroup(t(S.DNS_RESOLVER), Icons.Rounded.Router) {
        Pills(t(S.FIELD_MODE), listOf(t(S.DNS_MODE_MANUAL), t(S.DNS_MODE_AUTO)), if (manualDns) 0 else 1) { idx ->
            onChange(c.copy(resolverMode = if (idx == 1) Config.ResolverMode.AUTO else Config.ResolverMode.MANUAL))
        }
        if (manualDns) {
            SettingsDivider()
            TextRow(
                t(S.FIELD_ADDRESS),
                c.resolverHost,
                trailing = { CompactButton(t(S.LOCAL_RESOLVER_BTN), onClick = onLocalDns) }
            ) { onChange(c.copy(resolverHost = it)) }
        }
        SettingsDivider()
        NumberRow(t(S.FIELD_PORT), c.resolverPort) { onChange(c.copy(resolverPort = it)) }
        if (manualDns) {
            SettingsDivider()
            Pills(
                t(S.TRANSPORT),
                listOf("UDP", "TCP"),
                if (c.resolverTransport == Config.ResolverTransport.TCP) 1 else 0
            ) { idx ->
                onChange(c.copy(resolverTransport = if (idx == 1) Config.ResolverTransport.TCP else Config.ResolverTransport.UDP))
            }
        }
        SettingsDivider()
        Pills(
            t(S.DNS_QUERY_TYPE),
            DnsQueryTypeOptions.map { it.first },
            DnsQueryTypeOptions.indexOfFirst { it.second == c.dnsQueryType }.coerceAtLeast(0)
        ) { idx -> onChange(c.copy(dnsQueryType = DnsQueryTypeOptions[idx].second)) }
        SettingsDivider()
        Pills(
            t(S.DNS_PATH_MODE),
            listOf(t(S.PATH_MODE_RECURSIVE), t(S.PATH_MODE_AUTHORITATIVE)),
            if (c.resolverPathMode == Config.ResolverPathMode.AUTHORITATIVE) 1 else 0
        ) { idx ->
            onChange(
                c.copy(
                    resolverPathMode = if (idx == 1) Config.ResolverPathMode.AUTHORITATIVE else Config.ResolverPathMode.RECURSIVE
                )
            )
        }
    }
    SettingsGroup(t(S.AUTHENTICATION), Icons.Rounded.Key) {
        val login = c.authMode == Config.AuthMode.LOGIN_PASSWORD
        Pills(t(S.FIELD_MODE), listOf(t(S.AUTH_NO_AUTH), t(S.AUTH_LOGIN_PASSWORD)), if (login) 1 else 0) { idx ->
            onChange(c.copy(authMode = if (idx == 1) Config.AuthMode.LOGIN_PASSWORD else Config.AuthMode.NO_AUTH))
        }
        if (login) {
            SettingsDivider()
            TextRow(t(S.USERNAME), c.username) { onChange(c.copy(username = it)) }
            SettingsDivider()
            TextRow(t(S.PASSWORD), c.password, password = true) { onChange(c.copy(password = it)) }
        }
    }
    SettingsGroup(t(S.EDITOR_SECTION_ADVANCED), Icons.Rounded.Tune) {
        NumberRow(t(S.DNS_LABEL_LENGTH), c.dnsLabelLength) { onChange(c.copy(dnsLabelLength = it)) }
        SettingsDivider()
        NumberRow(t(S.DNS_LABEL_LENGTH_JITTER), c.dnsLabelLengthJitter) { onChange(c.copy(dnsLabelLengthJitter = it)) }
        SettingsDivider()
        NumberRow(t(S.MAX_POLL_RATE), c.maxPollQps) { onChange(c.copy(maxPollQps = it)) }
        SettingsDivider()
        NumberRow(t(S.MAX_DATA_RATE), c.maxDataQps) { onChange(c.copy(maxDataQps = it)) }
        SettingsDivider()
        NumberRow(t(S.MAX_ACTIVE_CONNECTIONS), c.maxActiveClients) { onChange(c.copy(maxActiveClients = it)) }
        SettingsDivider()
        SettingsSwitchRow(t(S.USE_BASE64U_ENCODING), c.base64uEncoding) { onChange(c.copy(base64uEncoding = it)) }
    }
}

@Composable
private fun XrayFormEditor(
    form: XrayForm,
    onForm: (XrayForm) -> Unit,
    openMenu: OpenMenu
) {
    val notSet = t(S.XRAY_NOT_SET)
    fun label(value: String) = value.ifEmpty { notSet }

    @Composable
    fun Dropdown(title: String, value: String, options: List<String>, display: (String) -> String = ::label, onPick: (String) -> Unit) {
        SettingsDropdownRow(title, display(value)) { y ->
            openMenu(options.map { it to display(it) }, value, y, onPick)
        }
    }

    val protocol = form.protocol
    SettingsGroup(t(S.EDITOR_SECTION_SERVER), Icons.Rounded.Dns) {
        SettingsDropdownRow(t(S.XRAY_PROTOCOL), protocol.label) { y ->
            openMenu(XrayFormProtocol.entries.map { it.id to it.label }, protocol.id, y) { id ->
                val next = XrayFormProtocol.of(id) ?: protocol
                val security = when {
                    next == XrayFormProtocol.SHADOWSOCKS -> XrayForm.SECURITY_NONE
                    next == XrayFormProtocol.TROJAN && form.security == XrayForm.SECURITY_NONE -> XrayForm.SECURITY_TLS
                    else -> form.security
                }
                onForm(form.copy(protocol = next, security = security))
            }
        }
        SettingsDivider()
        TextRow(t(S.XRAY_ADDRESS), form.address) { onForm(form.copy(address = it)) }
        SettingsDivider()
        SettingsRow(t(S.FIELD_PORT)) {
            SmuglyTextField(
                value = form.port,
                onValueChange = { onForm(form.copy(port = it.filter(Char::isDigit).take(5))) },
                modifier = Modifier.width(EditorInlineFieldWidth),
                number = true
            )
        }
        SettingsDivider()
        val usesUuid = protocol == XrayFormProtocol.VLESS || protocol == XrayFormProtocol.VMESS
        TextRow(if (usesUuid) t(S.XRAY_UUID) else t(S.PASSWORD), form.secret) { onForm(form.copy(secret = it)) }
        when (protocol) {
            XrayFormProtocol.VLESS -> {
                SettingsDivider()
                Dropdown(t(S.XRAY_FLOW), form.flow, XrayForm.FLOWS) { onForm(form.copy(flow = it)) }
                SettingsDivider()
                TextRow(t(S.XRAY_ENCRYPTION), form.vlessEncryption) { onForm(form.copy(vlessEncryption = it)) }
            }
            XrayFormProtocol.VMESS -> {
                SettingsDivider()
                Dropdown(t(S.XRAY_ENCRYPTION), form.vmessSecurity, XrayForm.VMESS_SECURITIES) {
                    onForm(form.copy(vmessSecurity = it))
                }
            }
            XrayFormProtocol.SHADOWSOCKS -> {
                SettingsDivider()
                Dropdown(t(S.XRAY_SS_METHOD), form.ssMethod, XrayForm.SS_METHODS) { onForm(form.copy(ssMethod = it)) }
            }
            XrayFormProtocol.TROJAN -> Unit
        }
    }
    SettingsGroup(t(S.TRANSPORT), Icons.Rounded.SwapHoriz) {
        Dropdown(
            t(S.XRAY_NETWORK),
            form.network,
            XrayForm.NETWORKS,
            display = { NetworkLabels[it] ?: it }
        ) { onForm(form.copy(network = it)) }
        if (form.network == "tcp") {
            SettingsDivider()
            Dropdown(t(S.XRAY_HEADER_TYPE), form.headerType, XrayForm.TCP_HEADERS) { onForm(form.copy(headerType = it)) }
        }
        if (XrayForm.hasHostAndPath(form.network, form.headerType)) {
            SettingsDivider()
            TextRow(t(S.XRAY_HOST), form.host) { onForm(form.copy(host = it)) }
            SettingsDivider()
            TextRow(t(S.XRAY_PATH), form.path) { onForm(form.copy(path = it)) }
        }
        if (form.network == "xhttp") {
            SettingsDivider()
            Dropdown(t(S.XRAY_XHTTP_MODE), form.xhttpMode, XrayForm.XHTTP_MODES) { onForm(form.copy(xhttpMode = it)) }
        }
        if (form.network == "grpc") {
            SettingsDivider()
            TextRow(t(S.XRAY_SERVICE_NAME), form.serviceName) { onForm(form.copy(serviceName = it)) }
            SettingsDivider()
            TextRow(t(S.XRAY_AUTHORITY), form.authority) { onForm(form.copy(authority = it)) }
            SettingsDivider()
            Pills(t(S.XRAY_GRPC_MODE), listOf("gun", "multi"), if (form.grpcMulti) 1 else 0) {
                onForm(form.copy(grpcMulti = it == 1))
            }
        }
    }
    SettingsGroup(t(S.XRAY_SECURITY), Icons.Rounded.Lock) {
        val securityIndex = XrayForm.SECURITIES.indexOf(form.security).coerceAtLeast(0)
        Box(Modifier.fillMaxWidth().padding(horizontal = SettingsRowPadH, vertical = 12.dp)) {
            PillSelector(
                options = listOf(t(S.XRAY_SECURITY_NONE), "TLS", "REALITY"),
                selectedIndex = securityIndex,
                trackColor = SmuglyBg,
                onSelected = { onForm(form.copy(security = XrayForm.SECURITIES[it])) }
            )
        }
        if (form.security != XrayForm.SECURITY_NONE) {
            SettingsDivider()
            TextRow(t(S.XRAY_SNI), form.sni) { onForm(form.copy(sni = it)) }
            SettingsDivider()
            Dropdown(t(S.XRAY_FINGERPRINT), form.fingerprint, XrayForm.FINGERPRINTS) { onForm(form.copy(fingerprint = it)) }
        }
        if (form.security == XrayForm.SECURITY_TLS) {
            SettingsDivider()
            Dropdown(t(S.XRAY_ALPN), form.alpn, XrayForm.ALPNS) { onForm(form.copy(alpn = it)) }
        }
        if (form.security == XrayForm.SECURITY_REALITY) {
            SettingsDivider()
            TextRow(t(S.XRAY_PUBLIC_KEY), form.publicKey) { onForm(form.copy(publicKey = it)) }
            SettingsDivider()
            TextRow(t(S.XRAY_SHORT_ID), form.shortId) { onForm(form.copy(shortId = it)) }
            SettingsDivider()
            TextRow(t(S.XRAY_SPIDER_X), form.spiderX) { onForm(form.copy(spiderX = it)) }
        }
    }
}

/**
 * Focus inside the editor must not scroll the page. Nested verticalScroll + the default
 * bring-into-view used to yank the form to the top the moment the text field was tapped.
 */
private val NoOpBringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float
    ): Float = 0f
}

private val NoOpBringIntoViewResponder = object : BringIntoViewResponder {
    override fun calculateRectForParent(localRect: Rect): Rect = Rect.Zero
    override suspend fun bringChildIntoView(localRect: () -> Rect?) {
        // Swallow the request entirely — do not scroll on focus.
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CodeEditor(
    text: String,
    onTextChange: (String) -> Unit,
    placeholder: String,
    highlight: VisualTransformation,
    format: (String) -> String?,
    modifier: Modifier = Modifier
) {
    var field by remember {
        mutableStateOf(TextFieldValue(text, TextRange(0)))
    }
    LaunchedEffect(text) {
        if (text != field.text) {
            val sel = field.selection
            val max = text.length
            field = TextFieldValue(
                text,
                TextRange(sel.start.coerceIn(0, max), sel.end.coerceIn(0, max))
            )
        }
    }
    val scroll = rememberScrollState()
    val shape = RoundedCornerShape(10.dp)

    // Disable focus-driven auto-scroll for everything inside this editor.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoOpBringIntoViewSpec) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(SmuglyInput)
                .border(1.dp, SmuglyStroke, shape)
                .bringIntoViewResponder(NoOpBringIntoViewResponder)
        ) {
            // Outer scroll of full text height (not BTF internal scroller). Auto bring-into-view
            // is disabled above so wheel position is preserved on first focus/click.
            Box(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(horizontal = 12.dp, vertical = 12.dp)
            ) {
                BasicTextField(
                    value = field,
                    onValueChange = { next ->
                        val pasted = next.text.length - field.text.length > 1
                        val formatted = if (pasted) format(next.text) ?: next.text else next.text
                        field = if (formatted != next.text) {
                            TextFieldValue(formatted, TextRange(formatted.length))
                        } else {
                            next
                        }
                        onTextChange(field.text)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .bringIntoViewResponder(NoOpBringIntoViewResponder),
                    textStyle = TextStyle(
                        color = SmuglyTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    ),
                    cursorBrush = SolidColor(SmuglyAccent),
                    visualTransformation = highlight,
                    decorationBox = { inner ->
                        Box(Modifier.fillMaxWidth()) {
                            if (field.text.isEmpty()) {
                                Text(placeholder, color = SmuglyTextMuted, fontSize = 12.sp)
                            }
                            inner()
                        }
                    }
                )
            }
        }
    }
}
