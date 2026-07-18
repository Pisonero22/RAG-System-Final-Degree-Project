package es.upsa.store;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RagChatMemoryStore implements ChatMemoryStore {

    private final Map<Object, List<ChatMessage>> memories = new ConcurrentHashMap<>();

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        return memories.getOrDefault(memoryId,List.of());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        memories.put(memoryId,messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        memories.remove(memoryId);
    }
}
