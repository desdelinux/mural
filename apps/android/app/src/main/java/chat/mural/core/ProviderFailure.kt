package chat.mural.core

enum class ProviderFailureKind {
    authentication, modelAccess, quota, rateLimit, unavailable, invalidRequest, unknown;

    companion object {
        fun classify(status: Int, code: String?): ProviderFailureKind = when {
            status == 401 -> authentication
            status == 403 || status == 404 -> modelAccess
            status == 429 -> if (code == "insufficient_quota") quota else rateLimit
            status == 408 || status >= 500 -> unavailable
            status == 400 || status == 422 -> invalidRequest
            else -> unknown
        }
        fun safeCode(value: String?): String? = value?.takeIf { it in setOf(
            "invalid_api_key", "insufficient_quota", "rate_limit_exceeded", "model_not_found", "permission_denied", "server_error") }
        fun safeReference(value: String?): String? = value?.takeIf { Regex("[A-Za-z0-9_-]{1,128}").matches(it) }
    }
}
