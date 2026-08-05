package es.upsa.websockets;

import es.upsa.rag.RagChatService;
import es.upsa.store.RagChatMemoryStore;
import es.upsa.store.readerFiles.DocumentFromFileCSV;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WebSocket(path = "/chat/{username}")
public class ChatWebSocket {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocket.class);

    @Inject
    RagChatService ragChatService;
    @Inject
    RagChatMemoryStore memoryStore;
    @Inject
    WebSocketConnection connection;

    public enum MessageType {USER_JOINED, USER_LEFT, CHAT_MESSAGE}
    public record ChatMessage(MessageType type, String from, String message, String llm, boolean fromAssistant) {}
    @OnError
    public void onError(Throwable t) {
        log.error("WebSocket error for user '{}'", connection.pathParam("username"), t);
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, "system",
                "Ha ocurrido un error. Vuelve a intentarlo.", null, true));
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

        String respuesta = ragChatService.chat(username, message.message, message.llm);
        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, message.llm, respuesta, message.llm,true));
    }
}