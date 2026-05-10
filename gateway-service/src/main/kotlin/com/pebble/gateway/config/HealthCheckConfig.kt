package com.pebble.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.ReactiveHealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

@Configuration
class HealthCheckConfig {

    @Bean
    fun webClient(builder: WebClient.Builder): WebClient = builder.build()

    @Bean
    fun authServiceHealthIndicator(
        webClient: WebClient,
        @Value("\${AUTH_SERVICE_URL:http://localhost:8080}") authUrl: String
    ): ReactiveHealthIndicator = createServiceHealthIndicator(webClient, "Auth Service", authUrl)

    @Bean
    fun matchingServiceHealthIndicator(
        webClient: WebClient,
        @Value("\${MATCHING_SERVICE_URL:http://localhost:8081}") matchingUrl: String
    ): ReactiveHealthIndicator = createServiceHealthIndicator(webClient, "Matching Service", matchingUrl)

    @Bean
    fun taskServiceHealthIndicator(
        webClient: WebClient,
        @Value("\${TASK_SERVICE_URL:http://localhost:8083}") taskUrl: String
    ): ReactiveHealthIndicator = createServiceHealthIndicator(webClient, "Task Service", taskUrl)

    @Bean
    fun previewServiceHealthIndicator(
        webClient: WebClient,
        @Value("\${PREVIEW_SERVICE_URL:http://localhost:8084}") previewUrl: String
    ): ReactiveHealthIndicator = createServiceHealthIndicator(webClient, "Preview Service", previewUrl)

    private fun createServiceHealthIndicator(
        webClient: WebClient,
        serviceName: String,
        baseUrl: String
    ): ReactiveHealthIndicator {
        return ReactiveHealthIndicator {
            webClient.get()
                .uri("$baseUrl/actuator/health")
                .retrieve()
                .toEntity(Map::class.java)
                .map { response ->
                    val status = response.body?.get("status") as? String ?: "UNKNOWN"
                    if (status == "UP") {
                        Health.up().withDetail("service", serviceName).withDetail("url", baseUrl).build()
                    } else {
                        Health.down().withDetail("service", serviceName).withDetail("url", baseUrl).withDetail("status", status).build()
                    }
                }
                .onErrorResume { e ->
                    Mono.just(Health.down().withDetail("service", serviceName).withDetail("url", baseUrl).withException(e).build())
                }
        }
    }
}
