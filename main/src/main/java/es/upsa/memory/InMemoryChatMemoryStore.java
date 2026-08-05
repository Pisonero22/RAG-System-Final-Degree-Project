package es.upsa.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Conversational memory, kept in the JVM heap.
 *
 * Deliberately NOT persisted: the conversation is ephemeral by design (it is deleted on
 * @OnClose), while the persistent knowledge lives in the vector index. Persisting it would
 * add nothing, because no conversation survives its WebSocket connection.
 *
 * Known consequence, accepted: two browser tabs using the same username share one memory,
 * so closing one wipes the other's history.
 */
@ApplicationScoped
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> memories = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return List.copyOf(memories.getOrDefault(memoryId, List.of()));
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        memories.put(memoryId, List.copyOf(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        memories.remove(memoryId);
    }
}
