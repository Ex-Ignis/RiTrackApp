package es.hargos.ritrack.config;

import es.hargos.ritrack.websocket.RiderLocationWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RiderLocationWebSocketHandler riderLocationWebSocketHandler;

    @Value("${websocket.allowed-origins:localhost}")
    private String[] allowedOrigins;

    public WebSocketConfig(RiderLocationWebSocketHandler riderLocationWebSocketHandler) {
        this.riderLocationWebSocketHandler = riderLocationWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {

        // Registrar el handler para ubicaciones de riders
        registry.addHandler(riderLocationWebSocketHandler, "/ws/rider-locations")
                .setAllowedOrigins(allowedOrigins) // En producción, especifica los orígenes permitidos
                .withSockJS() // Habilita SockJS como fallback para navegadores que no soportan WebSocket nativo
                .setWebSocketEnabled(true);

        // También registrar sin SockJS para conexiones WebSocket nativas
        registry.addHandler(riderLocationWebSocketHandler, "/ws/rider-locations-native")
                .setAllowedOrigins(allowedOrigins);
    }
}
