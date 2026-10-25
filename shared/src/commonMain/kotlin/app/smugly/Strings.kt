package app.smugly

import androidx.compose.runtime.mutableStateOf

/**
 * In-app localization used by Compose Multiplatform UI and Android Views.
 * Resolved language never stays as SYSTEM — [set] expands it.
 */
object Strings {
    private val language = mutableStateOf(AppLanguage.EN)

    var current: AppLanguage
        get() = language.value
        private set(value) {
            language.value = value
        }

    fun set(preference: AppLanguage) {
        current = resolve(preference)
    }

    fun resolve(preference: AppLanguage): AppLanguage = when (preference) {
        AppLanguage.SYSTEM -> {
            val lang = defaultLanguageCode()
            if (lang.equals("ru", ignoreCase = true)) AppLanguage.RU else AppLanguage.EN
        }
        else -> preference
    }
}

expect fun defaultLanguageCode(): String

fun t(s: S): String = if (Strings.current == AppLanguage.RU) s.ru else s.en

fun speedProbingText(current: Int, total: Int): String =
    if (Strings.current == AppLanguage.RU) "Проверка скорости $current/$total" else "Speed probing $current/$total"

fun dnsProbingText(current: Int, total: Int): String =
    if (Strings.current == AppLanguage.RU) "Поиск DNS $current/$total" else "DNS probing $current/$total"

enum class S(val en: String, val ru: String) {
    APP_NAME("Smugly", "Смугли"),
    // Screens / drawer / section titles
    HOME("Home", "Главная"),
    WEB_CABINET("My account", "Веб-кабинет"),
    WELCOME_TITLE("Welcome to Smugly", "Добро пожаловать в Смугли"),
    WELCOME_TEXT(
        "To connect, you need a subscription.\nIt takes a minute.",
        "Чтобы подключиться, нужна подписка.\nЭто займёт минуту."
    ),
    BUY_SUBSCRIPTION("Get a subscription", "Приобрести подписку"),
    HAVE_ACCOUNT_SIGN_IN("Already have an account? Sign in", "Уже есть аккаунт? Войти"),
    ACCOUNT_NO_PLAN_TITLE("No active subscription", "Подписка не активна"),
    ACCOUNT_NO_PLAN_TEXT(
        "Buy a subscription in your account — it will appear here by itself.",
        "Оформите подписку в веб-кабинете — она сама появится здесь."
    ),
    ACCOUNT_TRIAL_NEEDS_TELEGRAM(
        "The free trial unlocks after you link Telegram in your account.",
        "Бесплатный период откроется после привязки Telegram в веб-кабинете."
    ),
    ACCOUNT_SIGNED_IN_AS("Signed in as", "Вы вошли как"),
    ACCOUNT_PREPARING("Getting your subscription ready…", "Готовим вашу подписку…"),
    ACCOUNT_SECTION("Account", "Аккаунт"),
    SIGN_IN_BTN("Sign in", "Войти"),
    SIGN_OUT_BTN("Sign out", "Выйти из аккаунта"),
    SIGN_OUT_SHORT("Sign out", "Выйти"),
    ACCOUNT_NOT_SIGNED_IN("Not signed in", "Вы не авторизованы"),
    SIGN_OUT_TITLE("Sign out?", "Выйти из аккаунта?"),
    SIGN_OUT_MESSAGE(
        "The subscription folder will disappear from the app.",
        "Папка с подпиской пропадёт из приложения."
    ),
    LINK_WAITING_TITLE("Continue in the browser", "Продолжите в браузере"),
    LINK_WAITING_TEXT(
        "Sign in or register on the page that just opened. The app will pick everything up by itself.",
        "Войдите или зарегистрируйтесь на открывшейся странице. Приложение всё подхватит само."
    ),
    LINK_REOPEN_BTN("Open again", "Открыть ещё раз"),
    SUBSCRIPTION_TRIAL("Free trial", "Пробный период"),
    EXTEND_SUBSCRIPTION_BTN("Extend subscription", "Продлить подписку"),
    BUY_FULL_SUBSCRIPTION_BTN("Buy a subscription", "Купить подписку"),
    TOAST_ACCOUNT_SIGNED_IN("Signed in", "Вы вошли в аккаунт"),
    TOAST_SUBSCRIPTION_READY("Subscription synced successfully", "Подписка была успешно синхронизирована"),
    TOAST_SUBSCRIPTION_RENEWED("Subscription renewed", "Подписка продлена"),
    TOAST_ACCESS_REISSUED(
        "Access reissued and already imported into the app",
        "Доступы выпущены и уже импортированы в приложение"
    ),
    TOAST_ACCOUNT_GONE("Account not found — sign in again", "Аккаунт не найден — войдите снова"),
    TOAST_ACCOUNT_BANNED("This account is blocked", "Аккаунт заблокирован"),
    TOAST_SESSION_EXPIRED("Session expired — sign in again", "Сессия истекла — войдите снова"),
    TOAST_SIGNED_OUT("Signed out", "Вы вышли из аккаунта"),
    TOAST_SERVER_UNREACHABLE(
        "Could not reach the server. Check the internet and try again",
        "Не удалось связаться с сервером. Проверьте интернет и попробуйте ещё раз"
    ),
    TOAST_LINK_EXPIRED("Sign-in timed out — try again", "Время на вход вышло — попробуйте ещё раз"),
    SETTINGS_ADVANCED_WARNING_TITLE("Advanced settings", "Дополнительные настройки"),
    SETTINGS_ADVANCED_WARNING(
        "Changing these settings is strongly discouraged. The Smugly developers and the service providers are not responsible for the consequences of any changes!",
        "Данные настройки настоятельно не рекомендуется изменять. Разработчики Смугли, а также владельцы сервис-провайдеров не несут ответственности за последствия изменений!"
    ),
    CONTINUE_BTN("Continue", "Продолжить"),
    ACCOUNT_CONNECTING("Connecting to the server…", "Соединяемся с сервером…"),
    ACCOUNT_SIGNING_OUT("Signing out…", "Выходим из аккаунта…"),
    DIAGNOSTICS("Diagnostics", "Диагностика"),
    SETTINGS("Settings", "Настройки"),
    // Short on purpose: the drawer panel is 280dp wide and a longer name wraps to two lines
    // while every other item is one.
    PER_APP("Split tunneling", "Раздельный туннель"),
    NEW_PROFILE_TITLE("New Profile", "Новый профиль"),
    EDIT_PROFILE_TITLE("Edit Profile", "Редактирование"),
    DNS_RESOLVER("DNS Resolver", "DNS-резолвер"),
    AUTHENTICATION("Authentication", "Аутентификация"),
    ADVANCED_CLIENT_ONLY("Advanced (client-only)", "Дополнительно (только клиент)"),
    ACTIONS("Actions", "Действия"),

    // s3-fuckup (S3 dead-drop tunnel)
    PROTOCOL("Protocol", "Протокол"),
    PROTOCOL_SLIPSTREAM("Slipstream (DNS)", "Slipstream (DNS)"),
    PROTOCOL_S3FU("S3 (s3-fuckup)", "S3 (s3-fuckup)"),
    PROTOCOL_TURNFU("VK call (turn-fuckup)", "Звонок VK (turn-fuckup)"),
    TURNFU_SERVER("Server", "Сервер"),
    TURNFU_WORKERS("Streams", "Потоков"),
    TURNFU_MTU("MTU", "MTU"),
    TURNFU_CALL_TITLE("VK call link", "Ссылка на звонок VK"),
    SETTINGS_SECTION_TURNFU("VK call", "Звонок VK"),
    TURNFU_CALL_CONNECT("Connect", "Подключить"),
    TURNFU_CALL_INVALID("This is not a VK call link", "Это не ссылка на звонок VK"),
    TURNFU_CAPTCHA_TITLE("VK check", "Проверка VK"),
    TURNFU_CAPTCHA_NOTIFICATION("VK asks to confirm you are not a robot", "VK просит подтвердить, что вы не робот"),
    PROTOCOL_XRAY("Xray", "Xray"),
    /** Profile-card subtitle: the component actually carrying the traffic. */
    PROTOCOL_XRAY_CORE("Xray-core", "Xray-core"),
    S3_SECTION("S3 (s3-fuckup)", "S3 (s3-fuckup)"),
    S3_ENDPOINT("S3 endpoint", "S3 endpoint"),
    S3_BUCKET("Bucket", "Бакет"),
    S3_BUCKET_HINT("bucket name", "имя бакета"),
    S3_ACCESS_KEY("Access key", "Access key"),
    S3_SECRET_KEY("Secret key", "Secret key"),
    S3_PREFIX("Prefix (server)", "Префикс (сервер)"),
    S3_PREFIX_HINT("s3fu", "s3fu"),
    S3_PSK("PSK", "PSK"),
    S3_PSK_HINT("64 hex chars", "64 hex-символа"),

    // cdn-fuckup (XHTTP packet-up)

    // Xray
    XRAY_SECTION("Xray configuration", "Конфигурация Xray"),
    XRAY_CONFIG_HINT("Xray JSON config", "JSON-конфигурация Xray"),
    XRAY_FORMAT_BTN("FORMAT", "ФОРМАТИРОВАТЬ"),
    XRAY_VALIDATE_BTN("CHECK", "ПРОВЕРИТЬ"),
    XRAY_CORE_VERSION("Xray-core", "Xray-core"),
    XRAY_INPUT("Configuration", "Конфигурация"),
    XRAY_INPUT_MANUAL("Manual", "Вручную"),
    XRAY_PROTOCOL("Xray protocol", "Протокол Xray"),
    XRAY_ADDRESS("Server address", "Адрес сервера"),
    XRAY_UUID("UUID", "UUID"),
    XRAY_FLOW("Flow", "Поток"),
    XRAY_ENCRYPTION("Encryption", "Шифрование"),
    XRAY_SS_METHOD("Method", "Метод"),
    XRAY_NETWORK("Network", "Сеть"),
    XRAY_HEADER_TYPE("Header type", "Тип заголовка"),
    XRAY_HOST("Host", "Хост"),
    XRAY_PATH("Path", "Путь"),
    XRAY_XHTTP_MODE("XHTTP mode", "Режим XHTTP"),
    XRAY_SERVICE_NAME("Service name", "Имя сервиса"),
    XRAY_AUTHORITY("Authority", "Authority"),
    XRAY_GRPC_MODE("gRPC mode", "Режим gRPC"),
    XRAY_SECURITY("Security", "Безопасность"),
    XRAY_SECURITY_NONE("None", "Нет"),
    XRAY_SNI("SNI", "SNI"),
    XRAY_FINGERPRINT("Fingerprint", "Отпечаток"),
    XRAY_ALPN("ALPN", "ALPN"),
    XRAY_PUBLIC_KEY("Public key", "Публичный ключ"),
    XRAY_SHORT_ID("Short ID", "Short ID"),
    XRAY_SPIDER_X("SpiderX", "SpiderX"),
    XRAY_NOT_SET("Not set", "Не выбрано"),
    TOAST_XRAY_NEED_ADDRESS("Enter the server address", "Укажите адрес сервера"),
    TOAST_XRAY_NEED_PORT("Enter a port from 1 to 65535", "Укажите порт от 1 до 65535"),
    TOAST_XRAY_NEED_UUID("Enter the UUID", "Укажите UUID"),
    TOAST_XRAY_NEED_PASSWORD("Enter the password", "Укажите пароль"),
    TOAST_XRAY_NEED_PUBLIC_KEY("Enter the REALITY public key", "Укажите публичный ключ REALITY"),
    EDITOR_SECTION_SERVER("Server", "Сервер"),
    EDITOR_SECTION_ADVANCED("Advanced", "Дополнительно"),
    FIELD_MODE("Mode", "Режим"),
    FIELD_ADDRESS("Address", "Адрес"),
    FIELD_PORT("Port", "Порт"),
    LOCAL_RESOLVER_BTN("Local", "Локальный"),

    // Field labels
    LOCAL_PORT("Local port", "Локальный порт"),
    CONNECTION_MODE("Connection mode", "Режим подключения"),
    LANGUAGE("Language", "Язык"),
    LANGUAGE_SYSTEM("System", "Системный"),
    PROFILE_NAME("Profile name", "Название профиля"),
    DOMAIN("Domain", "Домен"),
    DNS_MODE("DNS mode", "Режим DNS"),
    RESOLVER_HOST("Resolver host", "DNS-резолвер"),
    TRANSPORT("Transport", "Транспорт"),
    RESOLVER_PORT("Resolver port", "Порт резолвера"),
    DNS_QUERY_TYPE("DNS query type", "Тип DNS-запроса"),
    DNS_PATH_MODE("DNS path mode", "Режим пути DNS"),
    AUTH_MODE("Auth mode", "Режим аутентификации"),
    USERNAME("Username", "Логин"),
    PASSWORD("Password", "Пароль"),
    DNS_LABEL_LENGTH("DNS label length", "Длина DNS-метки"),
    DNS_LABEL_LENGTH_JITTER("DNS label length jitter", "Джиттер длины DNS-метки"),
    MAX_POLL_RATE("Download polls per second", "Опросов download в секунду"),
    MAX_DATA_RATE("Upload queries per second", "Запросов upload в секунду"),
    MAX_ACTIVE_CONNECTIONS("Max active connections", "Макс. активных соединений"),
    SOCKS_USERNAME("SOCKS username", "Логин SOCKS"),
    SOCKS_PASSWORD("SOCKS password", "Пароль SOCKS"),
    DNS_RESOLVER_POOL("DNS resolver pool", "Пул DNS-резолверов"),
    DNS_RESOLVER_POOL_HINT("One per line", "По одному на строку"),

    // Pill / spinner option labels
    DNS_MODE_MANUAL("manual dns", "ручной DNS"),
    DNS_MODE_AUTO("auto dns", "авто DNS"),
    PATH_MODE_RECURSIVE("recursive", "рекурсивный"),
    PATH_MODE_AUTHORITATIVE("authoritative", "авторитетный"),
    AUTH_NO_AUTH("no-auth", "без пароля"),
    AUTH_LOGIN_PASSWORD("login/password", "логин/пароль"),
    CONNECTION_MODE_PROXY("proxy", "прокси"),
    CONNECTION_MODE_VPN("vpn", "VPN"),
    /** Same slot on desktop, where the system-wide tunnel is a TUN adapter rather than a VpnService. */
    CONNECTION_MODE_TUN("tun", "TUN"),

    // Checkboxes
    USE_BASE64U_ENCODING("Use base64u encoding", "Использовать кодировку base64u"),
    SHOW_TRAFFIC_NOTIFICATION("Show traffic notification", "Показывать уведомление о трафике"),
    PROTECT_LOCAL_SOCKS("Protect local SOCKS", "Защитить локальный SOCKS"),
    ENABLE_DEBUG_MODE("Enable debug mode", "Включить режим отладки"),

    // Buttons
    LOCAL_BTN("LOCAL", "ЛОКАЛЬНЫЙ"),
    CONNECT_BTN("CONNECT", "ПОДКЛЮЧИТЬ"),
    SHARE_LOG_BTN("SHARE LOG", "ПОДЕЛИТЬСЯ ЛОГОМ"),
    CRASH_REPORT_BTN("CRASH REPORT", "ОТЧЁТ О СБОЕ"),
    DELETE_PROFILE_BTN("DELETE PROFILE", "УДАЛИТЬ ПРОФИЛЬ"),
    CREATE_PROFILE_BTN("CREATE PROFILE", "СОЗДАТЬ ПРОФИЛЬ"),
    SAVE_PROFILE_BTN("SAVE PROFILE", "СОХРАНИТЬ ПРОФИЛЬ"),
    BACK_BTN("BACK", "НАЗАД"),

    // Content descriptions (accessibility)
    CD_MENU("Menu", "Меню"),
    CD_NEW_PROFILE("New profile", "Новый профиль"),
    CD_ADD_PROFILE_MENU("Add profile", "Добавить профиль"),
    CD_BACK("Back", "Назад"),
    CD_EDIT_PROFILE("Edit", "Редактировать"),
    CD_DELETE_PROFILE("Delete profile", "Удалить профиль"),
    CD_PROFILE_MENU("Profile menu", "Меню профиля"),

    // Profile list
    PROFILE_NAME_FALLBACK("Manual", "Ручной"),

    // Add-profile overflow menu (plus button)
    MENU_NEW_PROFILE("New profile", "Новый профиль"),
    MENU_IMPORT_CLIPBOARD("Import from clipboard", "Импорт из буфера обмена"),
    MENU_IMPORT_FILE("Import from file", "Импорт из файла"),

    // Per-profile overflow menu (⋯ button)
    TOAST_NOTHING_TO_EXPORT("Folder has no profiles", "В папке нет профилей"),
    MENU_EXPORT_FOLDER("Export all profiles", "Экспорт всех профилей"),
    MENU_MEASURE_LATENCY("Measure latency", "Измерить задержку"),
    MENU_EXPORT_PROFILE("Export", "Экспортировать"),

    // Dialogs
    DELETE_PROFILE_TITLE("Delete profile?", "Удалить профиль?"),
    DELETE_BTN("Delete", "Удалить"),
    CANCEL_BTN("Cancel", "Отмена"),
    CRASH_REPORT_TITLE("Crash report", "Отчёт о сбое"),
    NO_CRASH_REPORT("No crash report saved yet.", "Отчёт о сбое пока не сохранён."),
    COPY_BTN("Copy", "Копировать"),
    SHARE_BTN("Share", "Поделиться"),
    CLOSE_BTN("Close", "Закрыть"),
    BACKGROUND_WORK_TITLE("Background work", "Работа в фоне"),
    BACKGROUND_WORK_MESSAGE(
        "Allow Smugly to keep working in the background if your Android skin shows such an option.",
        "Разрешите Смугли работать в фоне, если ваша оболочка Android показывает такую опцию."
    ),
    OPEN_SETTINGS_BTN("Open settings", "Открыть настройки"),
    LATER_BTN("Later", "Позже"),

    // Toasts
    TOAST_VPN_PERMISSION_REQUIRED("VPN permission is required", "Требуется разрешение на VPN"),
    TOAST_SWITCHING_PROFILE("switching profile…", "переключение профиля…"),
    TOAST_CANNOT_DELETE_LAST_PROFILE("cannot delete last profile", "нельзя удалить последний профиль"),
    TOAST_PROFILE_DELETED("profile deleted", "профиль удалён"),
    TOAST_PROFILE_CREATED("profile created", "профиль создан"),
    TOAST_PROFILE_SAVED("profile saved", "профиль сохранён"),
    TOAST_START_FAILED("start failed", "не удалось запустить"),
    TOAST_VPN_START_FAILED("vpn start failed", "не удалось запустить VPN"),
    TOAST_NO_LOCAL_DNS("no local DNS", "нет локального DNS"),
    // Covers every import form: slipstream://, s3fu://, xray://, vless:// and raw JSON.
    TOAST_INVALID_PROFILE_LINK("invalid profile link", "неверная ссылка профиля"),
    TOAST_PROFILE_IMPORTED("profile imported", "профиль импортирован"),
    TOAST_CLIPBOARD_EMPTY("clipboard is empty", "буфер обмена пуст"),
    TOAST_IMPORT_FILE_FAILED("could not read import file", "не удалось прочитать файл импорта"),
    TOAST_PROFILE_LINK_COPIED("profile link copied", "ссылка профиля скопирована"),
    TOAST_XRAY_CONFIG_OK("config is valid", "конфигурация корректна"),
    TOAST_XRAY_CONFIG_EMPTY("config is empty", "конфигурация пуста"),
    TOAST_XRAY_NOT_JSON("not valid JSON", "некорректный JSON"),
    TOAST_FILE_LOGGING_DISABLED("file logging is disabled", "логирование в файл отключено"),
    TOAST_LOG_COPIED("log copied to clipboard", "лог скопирован в буфер"),
    TOAST_LOG_EMPTY("log is empty", "лог пуст"),
    TOAST_CRASH_REPORT_COPIED("crash report copied", "отчёт о сбое скопирован"),
    TOAST_LOG_SAVED("log saved", "лог сохранён"),

    // Share chooser titles
    SHARE_LOG_CHOOSER("Share log", "Поделиться логом"),
    SHARE_CRASH_REPORT_CHOOSER("Share crash report", "Поделиться отчётом о сбое"),

    // Connect status
    STATUS_DISCONNECTING("Disconnecting", "Отключение"),
    STATUS_CONNECTING("Connecting", "Подключение"),
    STATUS_CONNECTED("Connected", "Подключено"),
    STATUS_NOT_CONNECTED("Not connected", "Не подключено"),
    STATUS_SPEED_PROBING("Speed probing", "Проверка скорости"),
    STATUS_DNS_PROBING("DNS probing", "Поиск DNS"),
    STATUS_STARTING("Starting", "Запуск"),
    /** Desktop TUN mode: Windows can take tens of seconds to create the adapter, and a silent
     *  spinner for that long is indistinguishable from a hang. */
    STATUS_TUN_ADAPTER("Creating the TUN adapter", "Поднимаем TUN-адаптер"),

    NO_PROFILES_HINT(
        "You have no VPN configurations",
        "У вас нет VPN-конфигураций"
    ),
    /** Same thing inside a subscription folder — the user has profiles, just not in this one. */
    NO_PROFILES_IN_FOLDER_HINT(
        "There are no VPN configurations here",
        "Здесь нет VPN-конфигураций"
    ),
    APP_HTTP_PROXY("HTTP proxy for apps", "HTTP-прокси для приложений"),
    APP_HTTP_PROXY_HINT("Needed for .onion and .i2p", "Нужен для .onion и .i2p"),
    SETTINGS_SECTION_GENERAL("General", "Основные"),
    SETTINGS_SECTION_CONNECTION("Connection", "Подключение"),
    SETTINGS_SECTION_TUNNEL("Tunnel", "Туннель"),
    SETTINGS_SECTION_XRAY("Xray", "Xray"),
    SETTINGS_SECTION_LOCAL_PROXY("Local proxy", "Локальный прокси"),
    SETTINGS_SECTION_SLIPSTREAM("Slipstream", "Slipstream"),
    SETTINGS_SECTION_DEBUG("Debugging", "Отладка"),
    SETTINGS_SECTION_ROOT("Root", "Root"),
    ROOT_MODE("Hide VPN from apps", "Скрыть VPN от приложений"),
    ROOT_HOTSPOT("Share VPN over hotspot", "Раздавать VPN через точку доступа"),
    TOAST_ROOT_DENIED("root access was not granted", "root-доступ не выдан"),
    TOAST_ROOT_START_FAILED("root tunnel did not start", "не удалось поднять root-туннель"),
    DEBUG_MODE_HINT("Keeps a log for Diagnostics", "Пишет журнал для «Диагностики»"),
    AUTO_CONNECT_ON_BOOT("Connect when the device starts", "Подключаться при включении устройства"),
    TUN_MTU("MTU", "MTU"),
    TUN_MTU_RANGE("from 1280 to 9000", "от 1280 до 9000"),
    TUN_IPV6("Send IPv6 through the tunnel", "Пускать IPv6 через туннель"),
    TUN_BYPASS_LAN("Keep the local network out of the tunnel", "Не пускать локальную сеть в туннель"),
    TUN_DNS("DNS servers", "DNS-серверы"),
    TUN_DNS_INVALID("Not an IP address", "Не IP-адрес"),
    ROUTE_DIRECT_RU("Russian sites go direct", "Российские сайты напрямую"),
    ROUTE_BLOCK_ADS("Block ads", "Блокировать рекламу"),
    ALLOW_LAN("Allow connections from the local network", "Разрешить подключения из локальной сети"),
    ALLOW_LAN_HINT("Other devices on the network can use this proxy", "Другие устройства в сети смогут ходить через этот прокси"),
    RESET_SETTINGS_BTN("Reset settings", "Сбросить настройки"),
    RESET_SETTINGS_TITLE("Reset settings?", "Сбросить настройки?"),
    RESET_SETTINGS_TEXT(
        "Everything on this screen returns to its default. Profiles, subscriptions and the account stay.",
        "Всё на этом экране вернётся к значениям по умолчанию. Профили, подписки и аккаунт останутся."
    ),
    RESET_BTN("Reset", "Сбросить"),
    TOAST_SETTINGS_RESET("settings reset", "настройки сброшены"),
    TOAST_SETTINGS_RECONNECT("reconnect to apply", "переподключитесь, чтобы применить"),
    RENAME_FOLDER("Rename", "Переименовать"),
    EDIT_FOLDER("Edit", "Редактировать"),
    FOLDER_NEW_TITLE("New folder", "Новая папка"),
    FOLDER_EDIT_TITLE("Folder settings", "Настройки папки"),
    FOLDER_NAME("Folder name", "Название папки"),
    FOLDER_NAME_HINT("My folder", "Моя папка"),
    FOLDER_DEFAULT_EMPTY_NAME("My folder", "Моя папка"),
    FOLDER_SOURCE("Folder source", "Источник папки"),
    FOLDER_SOURCE_EMPTY("Empty", "Пустая"),
    FOLDER_SOURCE_LINK("From link", "Из ссылки"),
    FOLDER_SOURCE_FILE("From file", "Из файла"),
    FOLDER_PICK_FILE_BTN("Choose file", "Выбрать файл"),
    FOLDER_URL("Subscription link", "Ссылка на подписку"),
    FOLDER_UPDATES_ENABLED("Allow updating", "Разрешить обновление"),
    FOLDER_AUTO_UPDATE("Update automatically", "Обновлять автоматически"),
    FOLDER_ALLOW_REORDER("Allow reordering profiles", "Разрешить перетаскивание профилей"),
    FOLDER_SHOW_INFO("Show info block", "Показывать блок с информацией"),
    MENU_NEW_FOLDER("New folder", "Новая папка"),
    DELETE_FOLDER_TITLE("Delete", "Удалить"),
    DELETE_FOLDER_MESSAGE(
        "The subscription and all its servers will be removed.",
        "Подписка и все её серверы будут удалены."
    ),
    SAVE_BTN("Save", "Сохранить"),

    // Subscriptions (folders)
    HOME_FOLDER("My configs", "Мои конфиги"),
    SUBSCRIPTION_ACTIVE("Subscription active", "Подписка активна"),
    SUBSCRIPTION_INACTIVE("Subscription inactive", "Подписка неактивна"),
    SUBSCRIPTION_USED("used", "использовано"),
    SUBSCRIPTION_EXPIRES_IN("Days left:", "Осталось дней:"),
    SUBSCRIPTION_EXPIRED("Expired", "Истекла"),
    SUBSCRIPTION_UPDATED("updated", "обновлено"),
    SUBSCRIPTION_UPDATED_JUST_NOW("updated just now", "обновлено только что"),
    SUBSCRIPTION_AUTO_UPDATE("auto", "автообновление"),
    SUBSCRIPTION_REFRESH("Refresh subscription", "Обновить подписку"),
    /** Blocking overlay while a brand-new subscription is being fetched. */
    SUBSCRIPTION_IMPORTING("Importing...", "Импортируем…"),
    SUBSCRIPTION_DELETE("Delete subscription", "Удалить подписку"),
    TOAST_SUBSCRIPTION_ADDED("Subscription imported", "Подписка импортирована"),
    TOAST_SUBSCRIPTION_UPDATED("Subscription updated", "Подписка обновлена"),
    TOAST_SUBSCRIPTION_FAILED("Subscription failed", "Ошибка подписки"),
    TOAST_FOLDER_SAVED("Folder saved", "Папка сохранена"),

    // Per-app proxy (split tunnelling)
    PER_APP_MODE_OFF("Off", "Выкл"),
    PER_APP_MODE_ALLOWED("Selected only", "Только выбранные"),
    PER_APP_MODE_DISALLOWED("All but selected", "Кроме выбранных"),
    PER_APP_HINT_OFF(
        "All apps go through the tunnel.",
        "Через туннель идут все приложения."
    ),
    PER_APP_HINT_ALLOWED(
        "Only the selected apps go through the tunnel; every other app connects directly.",
        "Через туннель идут только выбранные приложения, остальные подключаются напрямую."
    ),
    PER_APP_HINT_DISALLOWED(
        "The selected apps connect directly; every other app goes through the tunnel.",
        "Выбранные приложения подключаются напрямую, остальные идут через туннель."
    ),
    PER_APP_HINT_EMPTY_ALLOWED(
        "Nothing is selected, so all apps keep going through the tunnel. Pick at least one app.",
        "Ничего не выбрано, поэтому через туннель пока идут все приложения. Выберите хотя бы одно."
    ),
    PER_APP_SEARCH_HINT("Search apps", "Поиск приложений"),
    PER_APP_SHOW_SYSTEM("Show system apps", "Показывать системные приложения"),
    PER_APP_SECTION_SELECTED("Selected", "Выбранные"),
    PER_APP_SECTION_APPS("Apps", "Приложения"),
    PER_APP_SEARCH_CLEAR("Clear search", "Очистить поиск"),
    PER_APP_CLEAR("Clear", "Снять все"),
    PER_APP_NOTHING_FOUND("Nothing found", "Ничего не найдено"),
    TOAST_PER_APP_RECONNECT(
        "reconnect to apply",
        "переподключитесь, чтобы применить"
    ),

    // In-app updates (GitHub releases)
    UPDATES_SECTION("Updates", "Обновления"),
    UPDATE_AUTO("Check for updates automatically", "Проверять обновления автоматически"),
    UPDATE_CHECK_BTN("Check", "Проверить"),
    UPDATE_CHECKING("Checking…", "Проверяем…"),
    UPDATE_CURRENT_VERSION("Installed version", "Установленная версия"),
    UPDATE_AVAILABLE_TITLE("Update available", "Доступно обновление"),
    UPDATE_DOWNLOADING("Downloading…", "Загружаем…"),
    UPDATE_READY_TITLE("Update downloaded", "Обновление загружено"),
    UPDATE_READY_MESSAGE(
        "Android asks for the last confirmation itself — an app cannot install another one silently.",
        "Последнее подтверждение запрашивает сам Android — приложение не может установить другое молча."
    ),
    UPDATE_INSTALL_BTN("Install", "Установить"),
    UPDATE_NOW_BTN("Update", "Обновить"),
    UPDATE_SKIP_BTN("Skip this version", "Пропустить эту версию"),
    TOAST_UPDATE_CHECKING("checking for updates…", "проверяем обновления…"),
    TOAST_UPDATE_UP_TO_DATE("you have the latest version", "у вас последняя версия"),
    TOAST_UPDATE_FAILED("update check failed", "не удалось проверить обновления"),
    TOAST_UPDATE_DOWNLOAD_FAILED(
        "could not download the update — connect the tunnel and try again",
        "не удалось скачать обновление — подключите туннель и попробуйте снова"
    ),

    // Default profile names
    PROFILE_NAME_DEFAULT_IMPORTED("Profile", "Профиль"),
    PROFILE_NAME_DEFAULT("Slipstream profile", "Профиль Slipstream"),
    PROFILE_NAME_DEFAULT_S3FU("S3 profile", "S3-профиль"),
    PROFILE_NAME_DEFAULT_TURNFU("VK call profile", "Профиль звонка VK"),
    PROFILE_NAME_DEFAULT_XRAY("Xray profile", "Профиль Xray"),
}

/** "N profiles imported" -- pluralized per language. */
fun profilesImportedText(count: Int): String = if (Strings.current == AppLanguage.RU) {
    val plural = when {
        count % 100 in 11..14 -> "профилей"
        count % 10 == 1 -> "профиль"
        count % 10 in 2..4 -> "профиля"
        else -> "профилей"
    }
    "импортировано $count $plural"
} else {
    if (count == 1) "1 profile imported" else "$count profiles imported"
}

fun trialDaysText(days: Int): String = if (Strings.current == AppLanguage.RU) {
    val plural = when {
        days % 100 in 11..14 -> "дней"
        days % 10 == 1 -> "день"
        days % 10 in 2..4 -> "дня"
        else -> "дней"
    }
    "Первые $days $plural — бесплатно"
} else {
    if (days == 1) "The first day is free" else "The first $days days are free"
}
