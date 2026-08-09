package es.upsa.ai;

import java.util.Arrays;

/**
 * Los modelos que la aplicación ofrece, en el orden en que aparecen en la interfaz.
 *
 * Único sitio donde vive la lista. Antes estaba duplicada en ChatService.normalizeSlot y en
 * ChatResource.availableModels: añadir o quitar un modelo obligaba a tocar los dos y, si te
 * olvidabas de uno, el desplegable ofrecía un slot que normalizeSlot reconducía en silencio al
 * de por defecto — el usuario creía estar hablando con un modelo y hablaba con otro.
 *
 * OJO con la palabra "defecto", que aquí significa tres cosas distintas y solo una es esta:
 *   1. El modelo SIN nombre de Quarkus (quarkus.langchain4j.ollama.chat-model.model-id) es el
 *      que usan el detector de inyección y el reescritor de consultas. NO es este enum y debe
 *      seguir siendo un modelo pequeño y determinista.
 *   2. DEFAULT, de aquí abajo: el slot que se usa cuando la interfaz no manda ninguno o manda
 *      uno que no existe.
 *   3. El primero del desplegable: es el primero de values(), por el orden de este enum.
 */
public enum ModelSlot {

    /**
     * gpt-oss:20b. El de la demo: MoE de ~21B con ~3,6B activos, de modo que responde a la
     * velocidad de un modelo pequeño con la calidad de uno grande, y cabe holgadamente en
     * memoria junto a bge-m3 y al detector — que es justo lo que qwen3.6:35b no permitía.
     */
    GPTO("gpto", "quarkus.langchain4j.ollama.gpto.chat-model.model-id", "gpt-oss", "gptoss"),

    /** llama3.1:8b. El tier medio de la comparativa y el mismo modelo que el detector. */
    LLAMA("llama", "quarkus.langchain4j.ollama.llama.chat-model.model-id"),

    /** OpenAI real. Solo se ofrece si hay una clave de verdad (ver ChatResource). */
    GPT("gpt", "quarkus.langchain4j.openai.gpt.chat-model.model-name", "openai"),

    /** Razonador: más lento y menos obediente con el system prompt. Útil como contraste. */
    DEEPSEEK("deepseek", "quarkus.langchain4j.ollama.deepseek.chat-model.model-id"),

    /** El más pequeño de la comparativa. */
    MISTRAL("mistral", "quarkus.langchain4j.ollama.mistral.chat-model.model-id");

    /** El que se usa cuando la interfaz no manda nada o manda algo desconocido. */
    public static final ModelSlot DEFAULT = GPTO;

    private final String slot;
    private final String modelProperty;
    private final String[] aliases;

    ModelSlot(String slot, String modelProperty, String... aliases) {
        this.slot = slot;
        this.modelProperty = modelProperty;
        this.aliases = aliases;
    }

    /** El identificador que viaja en el mensaje y que espera @ModelName. */
    public String slot() {
        return slot;
    }

    /** La propiedad de configuración que contiene el modelo REAL de este slot. */
    public String modelProperty() {
        return modelProperty;
    }

    /**
     * Nombre recibido de la interfaz -> slot. Cualquier cosa desconocida cae en el de por
     * defecto: una pestaña antigua que siga mandando "qwen" no rompe nada, simplemente
     * responde el modelo de por defecto.
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