package com.pebble.preview

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.bind.annotation.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@SpringBootApplication
class PreviewApplication

fun main(args: Array<String>) {
    runApplication<PreviewApplication>(*args)
}

@Configuration
class PreviewSecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() } // 모든 요청 무인증 허용
        return http.build()
    }
}

data class PreviewItem(val id: Long, val title: String, val description: String)

@RestController
@RequestMapping("/api/v1/preview")
class PreviewController {
    private val items = ConcurrentHashMap<Long, PreviewItem>()
    private val idCounter = AtomicLong(1)

    init {
        items[idCounter.get()] = PreviewItem(idCounter.getAndIncrement(), "첫 번째 프리뷰", "인증 없이 볼 수 있는 콘텐츠입니다.")
        items[idCounter.get()] = PreviewItem(idCounter.getAndIncrement(), "시스템 공지", "게이트웨이 무인증 접근 테스트 중입니다.")
    }

    @GetMapping
    fun getAll(@RequestHeader headers: Map<String, String>): Map<String, Any> {
        return mapOf(
            "service" to "Preview Service",
            "accessInfo" to mapOf(
                "method" to "No-Auth (PermitAll)",
                "viaGateway" to headers.containsKey("x-forwarded-host"),
                "gatewayHeaders" to headers.filterKeys { it.startsWith("x-") }
            ),
            "data" to items.values.toList()
        )
    }

    @PostMapping
    fun create(@RequestBody item: PreviewItem): PreviewItem {
        val newItem = item.copy(id = idCounter.getAndIncrement())
        items[newItem.id] = newItem
        return newItem
    }

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): String {
        items.remove(id)
        return "Deleted $id"
    }
}
