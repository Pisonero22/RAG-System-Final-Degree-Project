package es.upsa.ragconfiguration;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reescritura de consulta con historial ("query rewriting" / condensación).
 * Convierte seguimientos ("¿y cuánto cuesta?") en consultas autocontenidas
 * ("precio de la PlayStation 5") ANTES de la búsqueda vectorial.
 * Sin memoria y con el modelo por defecto (temp 0.0), igual que el detector.
 */
@RegisterAiService(chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
@ApplicationScoped
public interface QueryRewriteService {

    @SystemMessage("""
        Eres un reescritor de consultas para un buscador de documentos.
        No respondes preguntas ni conversas: solo devuelves la consulta reescrita.
        """)
    @UserMessage("""
        Conversación reciente:
        {historial}

        Mensaje nuevo del usuario: {mensaje}

        Reescribe el mensaje nuevo como UNA consulta de búsqueda autocontenida, en el
        idioma del mensaje, sustituyendo pronombres y referencias ("eso", "el segundo",
        "¿y cuánto cuesta?") por aquello a lo que se refieren en la conversación.

        Reglas:
        - Si el mensaje ya se entiende por sí solo, devuélvelo EXACTAMENTE igual.
        - No añadas información que no esté en la conversación.
        - No respondas a la pregunta.
        - Devuelve SOLO la consulta, en una línea, sin comillas ni explicaciones.

        Ejemplos:
        (hablando de la PlayStation 5) "¿y cuánto cuesta?" -> precio de la PlayStation 5
        (hablando del accidente de la discoteca) "¿cuántos fallecidos hubo?" -> fallecidos en el accidente de la discoteca
        "¿Qué juegos exclusivos tiene la PS5?" -> ¿Qué juegos exclusivos tiene la PS5?
        """)
    String reescribir(String historial, String mensaje);
}