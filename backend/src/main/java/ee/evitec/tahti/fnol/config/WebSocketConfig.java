package ee.evitec.tahti.fnol.config;

import ee.evitec.tahti.fnol.ws.AudioStreamHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String AUDIO_PATH = "/ws/audio";

    private final AudioStreamHandler audioStreamHandler;

    public WebSocketConfig(AudioStreamHandler audioStreamHandler) {
        this.audioStreamHandler = audioStreamHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Development setting: any origin (Wio, browser tab, telephony adapter). Tighten for production.
        registry.addHandler(audioStreamHandler, AUDIO_PATH).setAllowedOriginPatterns("*");
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        var container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(1024 * 1024);   // 1 MB per frame is plenty for PCM chunks
        container.setMaxTextMessageBufferSize(64 * 1024);
        container.setMaxSessionIdleTimeout(10 * 60 * 1000L);    // 10 min without frames -> close
        return container;
    }
}
