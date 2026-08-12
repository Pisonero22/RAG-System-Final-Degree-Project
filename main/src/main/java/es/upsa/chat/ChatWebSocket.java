package es.upsa.chat;

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
     * The message travelling over the socket, in both directions.
     *
     * The last four fields are filled in by the server only: the browser sends type, from,
     * message and llm, and Jackson leaves the rest at null/0 without complaining. One record is
     * shared for both directions because the alternative — two nearly identical records —
     * duplicates the contract and guarantees they drift apart one day.
     *
     * @param model   the REAL model that answered, to show it in the bubble
     * @param millis  how long the generation took
     * @param sources the retrieved chunks, with branch and score
     */
    public record ChatMessage(MessageType type, String from, String message, String llm,
                              boolean fromAssistant, String model, long millis,
                              List<RagRetriever.Retrieved> sources) {

        /** A message with no retrieval trace: the user's echo, notices and errors. */
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

    /** Closing the tab wipes the conversation. Why it is not persisted: InMemoryChatMemoryStore. */
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

