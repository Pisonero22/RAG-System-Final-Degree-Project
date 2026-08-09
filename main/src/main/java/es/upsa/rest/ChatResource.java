package es.upsa.rest;


import es.upsa.ai.ModelSlot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.Config;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
@Path("/service")
public class ChatResource {

    @Inject
    Config config;

    /**
     * Modelos reales configurados en cada slot, para que la UI muestre
     * etiquetas verídicas (si OLLAMA_CHAT_MODEL cambia el modelo del slot
     * 'llama', el desplegable lo refleja sin tocar el HTML). Público a
     * propósito: la UI lo necesita antes de conectar y solo expone
     * identificadores de modelo, ningún secreto.
     */
    @GET
    @Path("/models")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> availableModels() {

        Map<String, String> models = new LinkedHashMap<>();
        for (ModelSlot slot : ModelSlot.values()) {
            if (slot == ModelSlot.GPT && config.getOptionalValue(
                            "quarkus.langchain4j.openai.gpt.api-key", String.class)
                    .filter(k -> !k.isBlank() && !"dummy".equals(k)).isEmpty()) {
                continue;
            }
            models.put(slot.slot(),
                    config.getOptionalValue(slot.modelProperty(), String.class).orElse("desconocido"));
        }
        return models;
    }


}
