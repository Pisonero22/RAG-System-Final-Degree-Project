package es.upsa.store;

import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class ChatMemoryStore {
    private final Map<String, ChatMemory> memories = new ConcurrentHashMap<>();

    public ChatMemory getOrCreate(String username) {
        return memories.computeIfAbsent(username,
                u -> MessageWindowChatMemory.withMaxMessages(8));
    }

    public void clear(String username) {
        memories.remove(username);
    }
}