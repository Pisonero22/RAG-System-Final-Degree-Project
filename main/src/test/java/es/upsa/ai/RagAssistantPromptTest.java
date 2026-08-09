package es.upsa.ai;

import dev.langchain4j.service.SystemMessage;
import es.upsa.search.RagRetriever;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La regla 4 del system prompt cita LITERALMENTE la cadena que RagRetriever devuelve cuando no
 * recupera nada. Son dos copias del mismo texto en dos ficheros distintos: si una cambia y la otra
 * no, la regla deja de dispararse y el modelo tratará el aviso de "sin contexto" como si fuera
 * contexto real. No hay ninguna otra cosa que detecte esa desincronización.
 */
class RagAssistantPromptTest {

    @Test
    @DisplayName("la regla 4 del prompt cita textualmente RagRetriever.NO_CONTEXT")
    void thePromptQuotesTheNoContextConstant() throws NoSuchMethodException {
        Method chat = RagAssistant.class.getMethod("chat",
                String.class, String.class, String.class, String.class, String.class);

        SystemMessage systemMessage = chat.getAnnotation(SystemMessage.class);
        assertNotNull(systemMessage, "RagAssistant.chat ha perdido su @SystemMessage");

        String prompt = String.join("\n", systemMessage.value());

        assertTrue(prompt.contains(RagRetriever.NO_CONTEXT),
                "El prompt ya no contiene la cadena exacta de RagRetriever.NO_CONTEXT ("
                        + RagRetriever.NO_CONTEXT + "): la regla 4 no se disparará nunca.");
    }

    @Test
    @DisplayName("las variables del prompt siguen siendo las que inyecta ChatService")
    void thePromptDeclaresItsTwoPlaceholders() throws NoSuchMethodException {
        String prompt = String.join("\n", RagAssistant.class.getMethod("chat",
                        String.class, String.class, String.class, String.class, String.class)
                .getAnnotation(SystemMessage.class).value());

        assertTrue(prompt.contains("{context}"), "falta el marcador {context}");
        assertTrue(prompt.contains("{interpretacion}"), "falta el marcador {interpretacion}");
    }
}