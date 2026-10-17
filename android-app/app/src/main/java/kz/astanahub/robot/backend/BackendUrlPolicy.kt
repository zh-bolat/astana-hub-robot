package kz.astanahub.robot.backend

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Какие адреса сервера можно сохранить с экрана диагностики (защита от подмены сервера посетителем).
 * - https — только хосты из [allowedHosts] (хост BACKEND_URL + BACKEND_ALLOWED_HOSTS);
 * - при [allowPrivateNetwork] (только debug) — ещё http/https на приватные IPv4 (10/8, 172.16/12, 192.168/16; 10.0.2.2).
 * Логин/пароль, query и fragment в адресе запрещены.
 */
class BackendUrlPolicy(
    allowedHosts: Collection<String>,
    private val allowPrivateNetwork: Boolean,
) {
    private val hosts: Set<String> = allowedHosts.mapNotNull(::cleanHost).toSet()

    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val message: String) : Result
    }

    /** Дополняет схему (https; http — только приватный IP в debug) и путь /v1. Мусор → пусто. */
    fun normalize(raw: String): String = SwitchableBackendApi.normalizeUserUrl(raw, httpForPrivateIp = allowPrivateNetwork)

    fun validate(raw: String): Result {
        val normalized = normalize(raw)
        if (normalized.isEmpty()) return Result.Invalid("Адрес пустой или некорректный")
        val url = normalized.toHttpUrlOrNull() ?: return Result.Invalid("Адрес некорректный")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result.Invalid("Логин и пароль в адресе не допускаются")
        }
        if (url.query != null || url.fragment != null) {
            return Result.Invalid("Адрес не должен содержать ? или #")
        }
        val host = url.host.lowercase()
        val privateIp = allowPrivateNetwork && isPrivateIpv4(host)
        return when (url.scheme) {
            "https" -> if (host in hosts || privateIp) Result.Valid(normalized)
                else Result.Invalid("Сервер $host не в списке разрешённых (BACKEND_ALLOWED_HOSTS)")
            "http" -> when {
                !allowPrivateNetwork -> Result.Invalid("В этой сборке разрешён только https")
                privateIp -> Result.Valid(normalized)
                else -> Result.Invalid("http — только для локальной сети (10.x, 172.16–31.x, 192.168.x); иначе https")
            }
            else -> Result.Invalid("Только http/https")
        }
    }

    fun isAllowed(raw: String): Boolean = validate(raw) is Result.Valid

    companion object {
        /** Хост BACKEND_URL + список через запятую из BACKEND_ALLOWED_HOSTS. */
        fun fromBuildConfig(backendUrl: String, allowedHostsCsv: String, debug: Boolean): BackendUrlPolicy {
            val defaultHost = backendUrl.trim().takeIf { it.isNotEmpty() }
                ?.let { if (it.contains("://")) it else "https://$it" }
                ?.toHttpUrlOrNull()?.host
            val extra = allowedHostsCsv.split(',')
            return BackendUrlPolicy(listOfNotNull(defaultHost) + extra, allowPrivateNetwork = debug)
        }

        /** 10.0.0.0/8 (включая 10.0.2.2 эмулятора), 172.16.0.0/12, 192.168.0.0/16. */
        fun isPrivateIpv4(host: String): Boolean {
            val parts = host.split('.')
            if (parts.size != 4) return false
            val octets = parts.map { p ->
                if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return false
                p.toInt().takeIf { it in 0..255 } ?: return false
            }
            return when {
                octets[0] == 10 -> true
                octets[0] == 172 && octets[1] in 16..31 -> true
                octets[0] == 192 && octets[1] == 168 -> true
                else -> false
            }
        }

        private fun cleanHost(raw: String): String? =
            raw.trim().lowercase().trimEnd('.').takeIf { it.isNotEmpty() }
    }
}
