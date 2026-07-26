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
        idioma del mensaje. Sustituye pronombres, elipsis y referencias implícitas
        (cualquier palabra que solo se entienda leyendo la conversación) por aquello
        a lo que se refieren.

        Reglas:
        - Si el mensaje ya se entiende por sí solo, devuélvelo EXACTAMENTE igual.
        - No añadas información que no esté en la conversación.
        - No respondas a la pregunta.
        - Devuelve SOLO la consulta, en una línea, sin comillas ni explicaciones.

        Ejemplos (el patrón vale para cualquier atributo: precio, stock, fecha, autor...):
        (hablando de la PlayStation 5) "¿y cuánto cuesta?" -> precio de la PlayStation 5
        (hablando de un dron DJI) "¿y cuánto stock hay?" -> stock disponible del dron DJI
        (hablando del accidente de la discoteca) "¿cuántos fallecidos hubo?" -> fallecidos en el accidente de la discoteca
        (el asistente mencionó la PS5 y un dron DJI) "háblame del segundo" -> dron DJI
        "¿Qué juegos exclusivos tiene la PS5?" -> ¿Qué juegos exclusivos tiene la PS5?
        """)
    String reescribir(String historial, String mensaje);
}