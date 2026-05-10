package com.pebble.gateway.config

import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.time.Instant

@RestController
class DashboardController(
    private val authServiceHealthIndicator: ReactiveHealthIndicator,
    private val matchingServiceHealthIndicator: ReactiveHealthIndicator,
    private val taskServiceHealthIndicator: ReactiveHealthIndicator,
    private val previewServiceHealthIndicator: ReactiveHealthIndicator,
) {

    private val serviceIndicators = linkedMapOf(
        "auth"     to lazy { authServiceHealthIndicator },
        "matching" to lazy { matchingServiceHealthIndicator },
        "task"     to lazy { taskServiceHealthIndicator },
        "preview"  to lazy { previewServiceHealthIndicator },
    )

    @GetMapping("/api/dashboard", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun dashboard(): Mono<Map<String, Any>> {
        val checks = serviceIndicators.entries.map { (name, indicator) ->
            indicator.value.health()
                .map { health ->
                    val url = health.details["url"] as? String ?: ""
                    name to mapOf(
                        "status" to health.status.code,
                        "url"    to url
                    )
                }
                .onErrorReturn(name to mapOf("status" to "DOWN", "url" to ""))
        }

        return Flux.merge(checks)
            .collectMap({ it.first }, { it.second })
            .map { services ->
                val allUp = services.values.all { (it["status"] as? String) == "UP" }
                mapOf(
                    "gateway"   to "online",
                    "timestamp" to Instant.now().toString(),
                    "overall"   to if (allUp) "UP" else "DEGRADED",
                    "services"  to services
                )
            }
    }
}