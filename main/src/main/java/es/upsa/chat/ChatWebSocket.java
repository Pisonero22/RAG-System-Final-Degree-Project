package es.upsa.chat;

import es.upsa.memory.InMemoryChatMemoryStore;
import es.upsa.search.RagRetriever;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@WebSocket(path = "/chat/{username}")
public class ChatWebSocket {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocket.class);

    @Inject
    ChatService chatService;
    @Inject
    InMemoryChatMemoryStore memoryStore;
    @Inject
    WebSocketConnection connection;

    /**
     * El mensaje que viaja por el socket, en los dos sentidos.
     *
     * Los cuatro últimos campos solo los rellena el servidor: el navegador manda
     * type, from, message y llm, y Jackson deja el resto a null/0 sin protestar.
     * Se comparte el mismo record en ambas direcciones porque el alternativo
     * —dos records casi idénticos— duplica el contrato y garantiza que algún día
     * se desincronicen.
     *
     * @param model   el modelo REAL que respondió, para enseñarlo en la burbuja
     * @param millis  lo que tardó la generación
     * @param sources los fragmentos recuperados, con origen y puntuación
     */
    public record ChatMessage(MessageType type, String from, String message, String llm,
                              boolean fromAssistant, String model, long millis,
                              List<RagRetriever.Retrieved> sources) {

        /** Un mensaje sin traza de recuperación: eco del usuario, avisos y errores. */
        static ChatMessage plain(MessageType type, String from, String message,
                                 String llm, boolean fromAssistant) {
            return new ChatMessage(type, from, message, llm, fromAssistant, null, 0L, List.of());
        }
    }


    public enum MessageType {USER_JOINED, CHAT_MESSAGE}


    @OnError
    public ChatMessage onError(Throwable t) {
        log.error("WebSocket error for user '{}'", connection.pathParam("username"), t);
        return ChatMessage.plain(MessageType.CHAT_MESSAGE, "system",
                "Ha ocurrido un error. Vuelve a intentarlo.", null, true);
    }

    @OnOpen
    public ChatMessage onOpen() {
        return ChatMessage.plain(MessageType.USER_JOINED, connection.pathParam("username"), null,null,false);
    }

    @OnClose
    public void onClose() {
        memoryStore.deleteMessages(connection.pathParam("username"));
    }


    @OnTextMessage
    public void onMessage(ChatMessage message) {


        if (message.message() == null || message.message().isBlank()) {
            return;
        }
        String username = connection.pathParam("username");

        // Eco del mensaje del usuario SOLO a su propia conexión.
        connection.sendTextAndAwait(ChatMessage.plain(MessageType.CHAT_MESSAGE, username,
                message.message(), message.llm(), false));

        ChatService.Answer answer = chatService.chat(username, message.message(), message.llm());

        connection.sendTextAndAwait(new ChatMessage(MessageType.CHAT_MESSAGE, message.llm(),
                answer.text(), message.llm(), true,
                answer.model(), answer.millis(), answer.sources()));
    }

}

