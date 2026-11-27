package com.yuzhua.config;

import com.yuzhua.security.WsHandshakeInterceptor;
import com.yuzhua.websocket.ConsultWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private static final int BYTES_PER_KIB = 1024;
    private static final int MAX_TEXT_BUFFER_BYTES = 16 * BYTES_PER_KIB;
    private static final int MAX_BINARY_BUFFER_BYTES = 4 * BYTES_PER_KIB;

    private final ConsultWebSocketHandler consultHandler;
    private final WsHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(consultHandler, "/ws/consult")
                .addInterceptors(handshakeInterceptor)
                // 原因: 允许开发期 Vite 跨域连接，鉴权由握手拦截器强制校验 token
                .setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_TEXT_BUFFER_BYTES);
        container.setMaxBinaryMessageBufferSize(MAX_BINARY_BUFFER_BYTES);
        return container;
    }
}
