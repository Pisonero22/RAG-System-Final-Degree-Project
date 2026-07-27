package es.upsa.websockets;

import es.upsa.rag.RagChatService;
import es.upsa.store.RagChatMemoryStore;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnOpen;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocket;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.inject.Inject;

@WebSocket(path = "/chat/{username}")
public class ChatWebSocket {

    @Inject
    RagChatService ragChatService;
    @Inject
    RagChatMemoryStore memoryStore;
    @Inject
    WebSocketConnection connection;

    public enum MessageType {USER_JOINED, USER_LEFT, CHAT_MESSAGE}
    public record ChatMessage(MessageType type, String from, String message,String llm) {}


    @OnOpen
    public ChatMessage onOpen() {
        return new ChatMessage(MessageType.USER_JOINED, connection.pathParam("username"), null,null);
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
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, username, message.message, message.llm));

        String respuesta = ragChatService.chat(username, message.message, message.llm);
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, message.llm, respuesta, message.llm));
    }
}