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

    public enum MessageType {USER_JOINED, USER_LEFT, CHAT_MESSAGE}
    public record ChatMessage(MessageType type, String from, String message,String llm) {
    }
    @Inject
    WebSocketConnection connection;

    @OnOpen(broadcast = true)
    public ChatMessage onOpen() {
        return new ChatMessage(MessageType.USER_JOINED, connection.pathParam("username"), null,null);
    }

    @OnClose
    public void onClose() {
        ChatMessage departure = new ChatMessage(MessageType.USER_LEFT, connection.pathParam("username"), null,null);
        memoryStore.deleteMessages(connection.pathParam("username"));
        connection.broadcast().sendTextAndAwait(departure);

    }
    @OnTextMessage
    public void onMessage(ChatMessage message) {
        // Mensaje vacío o solo espacios: se ignora sin llegar al pipeline.
        // Sin este filtro, Query.from("") lanza IllegalArgumentException
        // ("text cannot be null or blank") y el usuario ve un error genérico.
        if (message.message == null || message.message.isBlank()) {
            return;
        }
        String username = connection.pathParam("username");
        // Enviar primero el mensaje del usuario a todos los clientes
        ChatMessage userMsg = new ChatMessage(MessageType.CHAT_MESSAGE, username, message.message,message.llm);
        connection.broadcast().sendTextAndAwait(userMsg);

        // Obtener respuesta de la IA y también enviarla a todos los clientes
        String responseWithRag2 = ragChatService.chat(username,message.message, message.llm);
        ChatMessage aiMsg = new ChatMessage(MessageType.CHAT_MESSAGE, message.llm, responseWithRag2,message.llm);
        connection.broadcast().sendTextAndAwait(aiMsg);
    }
}