package es.upsa.ai;

import java.util.Arrays;

/**
 * The models the application offers, in the order they show up in the interface.
 *
 * The single place where that list lives. It used to be duplicated in ChatService.normalizeSlot
 * and in ChatResource.availableModels: adding or removing a model meant touching both, and
 * forgetting one left the dropdown offering a slot that normalizeSlot quietly redirected to the
 * default — the user thought they were talking to one model and were talking to another.
 *
 * Careful with the word "default", which means three different things around here and only one
 * of them is this enum:
 *   1. Quarkus's UNNAMED model (quarkus.langchain4j.ollama.chat-model.model-id) is the one the
 *      injection detector and the query rewriter use. It is not this enum, and it has to stay
 *      small and deterministic.
 *   2. DEFAULT, below: the slot used when the interface sends nothing, or sends something that
 *      does not exist.
 *   3. The first entry of the dropdown: that is simply values()[0], the order of this enum.
 */
public enum ModelSlot {

    /**
     * gpt-oss:20b, the one used for the demo. A ~21B MoE with ~3.6B active, so it answers at the
     * speed of a small model with the quality of a large one, and it still fits in memory next
     * to bge-m3 and the detector — which is exactly what qwen3.6:35b did not.
     */
    GPTO("gpto", "quarkus.langchain4j.ollama.gpto.chat-model.model-id", "gpt-oss", "gptoss"),

    /** llama3.1:8b. The middle tier of the comparison, and the same model the detector runs on. */
    LLAMA("llama", "quarkus.langchain4j.ollama.llama.chat-model.model-id"),

    /** The real OpenAI. Only offered when there is an actual key (see ChatResource). */
    GPT("gpt", "quarkus.langchain4j.openai.gpt.chat-model.model-name", "openai"),

    /** A reasoning model: slower and less obedient with the system prompt. Useful as a contrast. */
    DEEPSEEK("deepseek", "quarkus.langchain4j.ollama.deepseek.chat-model.model-id"),

    /** The smallest of the comparison. */
    MISTRAL("mistral", "quarkus.langchain4j.ollama.mistral.chat-model.model-id");

    /** Used when the interface sends nothing, or sends something nobody recognises. */
    public static final ModelSlot DEFAULT = GPTO;

    private final String slot;
    private final String modelProperty;
    private final String[] aliases;

    ModelSlot(String slot, String modelProperty, String... aliases) {
        this.slot = slot;
        this.modelProperty = modelProperty;
        this.aliases = aliases;
    }

    /** The identifier that travels in the message and that {@code @ModelName} expects. */
    public String slot() {
        return slot;
    }

    /** The config property holding the REAL model behind this slot. */
    public String modelProperty() {
        return modelProperty;
    }

    /**
     * Name coming from the interface -> slot. Anything unknown falls back to the default: an old
     * tab still sending "qwen" breaks nothing, it just gets answered by the default model.
     */
    public static ModelSlot from(String name) {
        if (name == null || name.isBlank()) {
            return DEFAULT;
        }
        String normalized = name.trim().toLowerCase();
        return Arrays.stream(values())
                .filter(s -> s.slot.equals(normalized)
                        || Arrays.asList(s.aliases).contains(normalized))
                .findFirst()
                .orElse(DEFAULT);
    }
}