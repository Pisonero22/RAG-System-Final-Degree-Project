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
        - SEGUIMIENTO CORTO: si el mensaje tiene menos de cinco palabras o empieza por "y",
          es casi seguro un seguimiento. Identifica el SUJETO principal del último
          intercambio (el producto, documento o entidad del que se estaba hablando) y
          escríbelo explícitamente en la consulta.
        - TEMA NUEVO: si el mensaje pregunta por algo que NO se ha mencionado antes,
          devuélvelo EXACTAMENTE igual. Nunca lo relaciones con el tema anterior.
        - YA AUTOCONTENIDO: si el mensaje se entiende por sí solo, devuélvelo igual.
        - NUNCA lo acortes a palabras sueltas: escribe una frase completa.
        - Conserva TODOS los matices (marca, variante, formato, "normal", "premium").
        - No añadas información que no esté en la conversación. No respondas a la pregunta.
        - Devuelve SOLO la consulta, en una línea, sin comillas ni explicaciones.
        - AFIRMACIÓN: si el mensaje no es una pregunta sino un dato que el usuario
          cuenta sobre sí mismo ("me llamo...", "vivo en...", "estudio..."),
          devuélvelo EXACTAMENTE igual. No lo conviertas en pregunta.

        Ejemplos:
        (hablando de la PlayStation 5) "¿y cuánto cuesta?" -> precio de la PlayStation 5
        (hablando del yogur natural) "¿y el formato ahorro?" -> precio del yogur natural formato ahorro
        (hablando de la leche entera premium) "¿y la normal?" -> precio de la leche entera normal, no premium
        (hablando de un torneo de ajedrez) "¿Cómo se llama el buque de investigación?" -> ¿Cómo se llama el buque de investigación?
        "¿Dónde se ha celebrado el torneo de ajedrez?" -> ¿Dónde se ha celebrado el torneo de ajedrez?
        """)
    String reescribir(String historial, String mensaje);
}