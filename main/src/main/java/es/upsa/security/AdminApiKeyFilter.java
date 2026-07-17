package es.upsa.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Provider
@AdminEndpoint
@Priority(Priorities.AUTHENTICATION)
public class AdminApiKeyFilter implements ContainerRequestFilter {

    @ConfigProperty(name = "admin.api-key")
    String expectedKey;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String key = ctx.getHeaderString("X-API-KEY");
        if (key == null || !MessageDigest.isEqual(
                key.getBytes(StandardCharsets.UTF_8),
                expectedKey.getBytes(StandardCharsets.UTF_8))) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity("API key inválida o ausente")
                    .build());
        }
    }
}