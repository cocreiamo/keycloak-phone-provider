package cc.coopersoft.keycloak.phone.providers.sender;

import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import cc.coopersoft.keycloak.phone.providers.spi.FullSmsSenderAbstractService;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.util.JsonSerialization;

/**
 * Il corriere degli SMS verso l'Italia, openapi.it.
 *
 * <p>La sua autenticazione è a <strong>due passi</strong>, e non è un dettaglio: la chiave
 * dell'account non si manda mai all'API dei messaggi — serve, in Basic, a coniare un Bearer che
 * scade. Lo scope del token deve nominare <strong>lo stesso host</strong> a cui poi si parla:
 * chiedere lo scope di produzione all'host di prova risponde «API not enabled». Verificato dal vivo
 * il 2026-09-16, e la stessa cosa la dice l'adapter TypeScript di rogita, che parla allo stesso
 * fornitore dal worker.
 *
 * <p>Il token si tiene finché vale, perché coniarne uno per messaggio raddoppia le chiamate e le
 * occasioni di sbagliare. Un 401 lo butta e lascia fallire l'invio: chi riprova è chi ha in mano il
 * tentativo, non questa classe.
 */
public class OpenapiSmsSenderService extends FullSmsSenderAbstractService {

  private static final Logger logger = Logger.getLogger(OpenapiSmsSenderService.class);

  /** Quanto prima della scadenza si conia il successivo: un token che scade a metà volo è un 401. */
  private static final Duration MARGIN = Duration.ofMinutes(1);

  private static final long TTL_SECONDS = 3600;

  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private final String baseUrl;
  private final String oauthUrl;
  private final String email;
  private final String apiKey;
  private final String sender;

  private String token;
  private Instant tokenExpiresAt;

  public OpenapiSmsSenderService(Config.Scope config, KeycloakSession session) {
    super(session);
    this.baseUrl = trimTrailingSlash(required(config, "baseUrl"));
    this.oauthUrl = trimTrailingSlash(required(config, "oauthUrl"));
    this.email = required(config, "email");
    this.apiKey = required(config, "apiKey");
    this.sender = required(config, "sender");
  }

  @Override
  public void sendMessage(String phoneNumber, String message) throws MessageSendException {
    HttpResponse<String> response = post(token(), phoneNumber, message);
    if (response.statusCode() == 401) {
      // Un token può scadere fra il conio e l'invio: si butta quello in mano, e il tentativo
      // fallisce. Riprovare qui nasconderebbe un corriere che non risponde più.
      this.token = null;
      throw new MessageSendException(401, "openapi_token_rejected", "openapi.it non ha accettato il token");
    }
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new MessageSendException(
          response.statusCode(), codeOf(response.body()), "openapi.it ha rifiutato il messaggio");
    }
    logger.debugf("SMS recapitato a openapi.it per %s", phoneNumber);
  }

  private HttpResponse<String> post(String bearer, String phoneNumber, String message)
      throws MessageSendException {
    String body;
    try {
      body =
          JsonSerialization.writeValueAsString(
              java.util.Map.of("sender", sender, "recipient", phoneNumber, "message", message));
    } catch (Exception cause) {
      throw new MessageSendException(500, "openapi_body_unwritable", cause.getMessage());
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(baseUrl + "/IT-messages"))
            .header("authorization", "Bearer " + bearer)
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    return send(request, "openapi_send_failed");
  }

  private String token() throws MessageSendException {
    if (token != null && tokenExpiresAt != null && Instant.now().plus(MARGIN).isBefore(tokenExpiresAt)) {
      return token;
    }
    String credentials =
        Base64.getEncoder()
            .encodeToString((email + ":" + apiKey).getBytes(StandardCharsets.UTF_8));
    String body;
    try {
      body =
          JsonSerialization.writeValueAsString(
              java.util.Map.of(
                  "name",
                  "rogita",
                  "scopes",
                  // `getAuthority` e non `getHost`: l'autorità porta anche la porta, ed è ciò
                  // che l'adapter TypeScript manda — `new URL(...).host` in JavaScript è
                  // `host:porta`. In produzione la porta non c'è e le due grafie coincidono, ma
                  // farle divergere qui significherebbe che il gemello prova una lingua diversa
                  // da quella che il fornitore sente.
                  java.util.List.of("POST:" + URI.create(baseUrl).getAuthority() + "/IT-messages"),
                  "ttl",
                  TTL_SECONDS));
    } catch (Exception cause) {
      throw new MessageSendException(500, "openapi_body_unwritable", cause.getMessage());
    }
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(oauthUrl + "/tokens"))
            .header("authorization", "Basic " + credentials)
            .header("content-type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<String> response = send(request, "openapi_token_failed");
    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      throw new MessageSendException(
          response.statusCode(), codeOf(response.body()), "openapi.it non conia il token");
    }
    minted(response.body());
    return token;
  }

  /** Ciò che openapi.it mette attorno a ogni risposta: la busta si chiama {@code data}. */
  private void minted(String raw) throws MessageSendException {
    JsonNode data = read(raw).path("data");
    String value = data.path("token").asText(null);
    if (value == null || value.isEmpty()) {
      throw new MessageSendException(502, "openapi_token_missing", "openapi.it ha risposto senza token");
    }
    this.token = value;
    this.tokenExpiresAt = expiry(data.path("expireAt").asText(null));
  }

  /**
   * Senza una scadenza leggibile si tiene la propria: meglio coniarne uno in più che usarne uno
   * morto.
   */
  private static Instant expiry(String declared) {
    if (declared == null || declared.isEmpty()) return Instant.now().plusSeconds(TTL_SECONDS);
    try {
      return Instant.parse(declared);
    } catch (DateTimeParseException ignored) {
      return Instant.now().plusSeconds(TTL_SECONDS);
    }
  }

  /** Il codice che openapi.it mette nel corpo: è lui che dice cosa è andato storto, non lo status. */
  private static String codeOf(String raw) {
    try {
      JsonNode error = read(raw).path("error");
      return error.isMissingNode() ? "openapi_unknown_error" : error.asText("openapi_unknown_error");
    } catch (MessageSendException ignored) {
      return "openapi_unreadable_error";
    }
  }

  private static JsonNode read(String raw) throws MessageSendException {
    try {
      return JsonSerialization.mapper.readTree(raw == null ? "{}" : raw);
    } catch (Exception cause) {
      throw new MessageSendException(502, "openapi_unreadable", cause.getMessage());
    }
  }

  private HttpResponse<String> send(HttpRequest request, String code) throws MessageSendException {
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new MessageSendException(503, code, "invio interrotto");
    } catch (Exception cause) {
      throw new MessageSendException(503, code, "openapi.it non risponde: " + cause.getMessage());
    }
  }

  /**
   * Il factory ha già preteso questi valori in {@code init}, cioè all'avvio: qui si legge e basta.
   * Se un giorno il controllo si spostasse, questo metodo tornerebbe a mentire in silenzio.
   */
  private static String required(Config.Scope config, String key) {
    String value = config.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "il corriere openapi.it vuole la configurazione '" + key + "', che non è arrivata");
    }
    return value;
  }

  private static String trimTrailingSlash(String url) {
    return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
  }

  @Override
  public void close() {}
}
