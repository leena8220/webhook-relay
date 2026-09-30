package dev.gdguesser.webhookrelay.api

import dev.gdguesser.webhookrelay.core.RelayProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@SpringBootApplication(scanBasePackages = ["dev.gdguesser.webhookrelay"])
@EnableConfigurationProperties(RelayProperties::class)
class RelayApiApplication

fun main(args: Array<String>) {
    SpringApplication.run(RelayApiApplication::class.java, *args)
}

@Component
class CorrelationIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val supplied = request.getHeader(HEADER)
        val correlationId =
            if (supplied != null && VALID_CORRELATION_ID.matches(supplied)) {
                supplied
            } else {
                UUID.randomUUID().toString()
            }
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER = "X-Correlation-ID"
        const val MDC_KEY = "correlationId"
        private val VALID_CORRELATION_ID = Regex("[A-Za-z0-9._:-]{1,100}")

        fun current(): String = MDC.get(MDC_KEY) ?: UUID.randomUUID().toString()
    }
}
