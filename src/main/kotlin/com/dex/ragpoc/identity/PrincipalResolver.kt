package com.dex.ragpoc.identity

import com.dex.ragpoc.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Component

data class Principal(
    val subject: String,
    val displayName: String,
    val email: String? = null,
)

class AuthenticationRequiredException : RuntimeException()

@Component
class PrincipalResolver(
    private val properties: AppProperties,
) {
    fun resolve(request: HttpServletRequest): Principal {
        if (properties.auth.mode == "local") {
            return Principal(
                subject = properties.auth.localSubject,
                displayName = properties.auth.localDisplayName,
                email = properties.auth.localEmail,
            )
        }
        val subject =
            request.getHeader(properties.auth.identityHeader)?.takeIf(String::isNotBlank)
                ?: throw AuthenticationRequiredException()
        return Principal(
            subject = subject,
            displayName = request.getHeader(properties.auth.identityNameHeader)?.takeIf(String::isNotBlank) ?: subject,
            email = request.getHeader(properties.auth.identityEmailHeader)?.takeIf(String::isNotBlank),
        )
    }
}
