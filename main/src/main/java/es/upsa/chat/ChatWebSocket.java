package es.upsa.chat;

import es.upsa.memory.InMemoryChatMemoryStore;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket(path = "/chat/{username}")
public class ChatWebSocket {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocket.class);

    @Inject
    ChatService chatService;
    @Inject
    InMemoryChatMemoryStore memoryStore;
    @Inject
    WebSocketConnection connection;

    public enum MessageType {USER_JOINED, USER_LEFT, CHAT_MESSAGE}
    public record ChatMessage(MessageType type, String from, String message, String llm, boolean fromAssistant) {}

    @OnError
    public ChatMessage onError(Throwable t) {
        log.error("WebSocket error for user '{}'", connection.pathParam("username"), t);
        return new ChatMessage(MessageType.CHAT_MESSAGE, "system",
                "Ha ocurrido un error. Vuelve a intentarlo.", null, true);
    }

    @OnOpen
    public ChatMessage onOpen() {
        return new ChatMessage(MessageType.USER_JOINED, connection.pathParam("username"), null,null,false);
    }

    @OnClose
    public void onClose() {
        memoryStore.deleteMessages(connection.pathParam("username"));
    }


    @OnTextMessage
    public void onMessage(ChatMessage message) {
        if (message.message == null || message.message.isBlank()) {
            return;
        }

        String username = connection.pathParam("username");

        // Eco del mensaje del usuario SOLO a su propia conexión.
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, username, message.message, message.llm,false));

        String answer = chatService.chat(username, message.message, message.llm);
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, message.llm, answer, message.llm,true));
    }
}
