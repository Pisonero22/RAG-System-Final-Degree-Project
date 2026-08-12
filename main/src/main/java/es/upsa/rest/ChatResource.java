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
     * The real model configured in each slot, so the UI can show honest labels: if
     * OLLAMA_CHAT_MODEL changes the model behind the 'llama' slot, the dropdown follows without
     * anyone touching the HTML.
     *
     * Public on purpose. The UI needs it before connecting, and all it gives away is model
     * identifiers — no secrets.
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
