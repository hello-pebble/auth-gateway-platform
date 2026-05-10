package com.pebble.gateway.config

import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.core.Ordered
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@Component
class CookieToAuthorizationFilter : GlobalFilter, Ordered {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
        val request = exchange.request

        // Authorization 헤더가 이미 있으면 그대로 통과
        if (request.headers.containsKey(HttpHeaders.AUTHORIZATION)) {
            return chain.filter(exchange)
        }

        // accessToken 쿠키를 Authorization: Bearer 헤더로 변환
        val accessToken = request.cookies.getFirst("accessToken")?.value
            ?: return chain.filter(exchange)

        val mutated = exchange.mutate()
            .request(
                request.mutate()
                    .header(HttpHeaders.AUTHORIZATION, "Bearer $accessToken")
                    .build()
            )
            .build()

        return chain.filter(mutated)
    }

    override fun getOrder(): Int = -1
}
