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
        container.setMaxTextMessageBufferSize(16 * 1024);
        container.setMaxBinaryMessageBufferSize(4 * 1024);
        return container;
    }
}
