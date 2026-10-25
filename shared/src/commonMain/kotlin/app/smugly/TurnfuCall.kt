package app.smugly

object TurnfuCall {
    private const val MARKER = "/call/join/"

    fun hash(input: String): String? {
        var value = input.trim().trim('<', '>', '"', '\'')
        if (value.isEmpty()) return null
        val lower = value.lowercase()
        val at = lower.indexOf(MARKER)
        if (at >= 0) {
            value = value.substring(at + MARKER.length)
        } else if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return null
        }
        value = value.substringBefore('?').substringBefore('#').trim('/').substringBefore('/')
        return value.takeIf { it.length >= 16 && it.none(Char::isWhitespace) }
    }

    fun url(input: String): String? = hash(input)?.let { "https://vk.ru/call/join/$it" }

    fun findIn(text: String): String? =
        text.split(' ', '\n', '\r', '\t').firstOrNull { it.contains(MARKER, ignoreCase = true) }?.let(::url)
}
