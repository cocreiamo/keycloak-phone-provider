package cc.coopersoft.keycloak.phone.providers.sender;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Il gemello di openapi.it: **parla il suo dialetto, copiato** dalle risposte vere del 2026-09-21 —
 * {@code POST /token} con una risposta piatta ({@code token}, {@code expire} in secondi, {@code
 * success}), la busta {@code data} solo attorno ai messaggi, {@code code} nel corpo di un errore
 * dell'API dei messaggi. La versione di prima parlava {@code /tokens} e {@code data.token}: ogni
 * prova era verde, e il fornitore rispondeva 401. Il server è quello del JDK, così non entra nessuna
 * dipendenza nel progetto.
 *
 * <p>Una finta direbbe soltanto che il codice chiama i metodi che ci si aspetta; un gemello dice
 * che il codice parla la lingua giusta — che è la differenza fra un test verde e un SMS che parte.
 */
final class OpenapiTwin implements AutoCloseable {

  /** Ogni richiesta arrivata, nell'ordine: è ciò su cui le prove asseriscono. */
  record Heard(String path, String authorization, String body) {}

  private final HttpServer server;
  private final List<Heard> heard = new ArrayList<>();
  private final AtomicInteger minted = new AtomicInteger();

  /** Quale esito dare al prossimo messaggio: 0 = va bene, altrimenti lo stato da rispondere. */
  private volatile int nextMessageStatus = 0;

  /** Cosa mettere in `expire`, in secondi: `null` significa «non lo dico», ed è un caso che capita. */
  private volatile Long expire = 4070908800L;

  OpenapiTwin() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/token", this::token);
    server.createContext("/IT-messages", this::messages);
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  List<Heard> heard() {
    return List.copyOf(heard);
  }

  int minted() {
    return minted.get();
  }

  void failNextMessageWith(int status) {
    nextMessageStatus = status;
  }

  void silentAboutExpiry() {
    expire = null;
  }

  private void token(HttpExchange exchange) throws IOException {
    record(exchange);
    int number = minted.incrementAndGet();
    String expiry = expire == null ? "" : ",\"expire\":" + expire;
    reply(
        exchange,
        200,
        "{\"scopes\":[\"POST:sms.openapi.com/IT-messages\"]"
            + expiry
            + ",\"token\":\"bearer-"
            + number
            + "\",\"success\":true,\"message\":\"\",\"error\":null}");
  }

  private void messages(HttpExchange exchange) throws IOException {
    record(exchange);
    int status = nextMessageStatus;
    nextMessageStatus = 0;
    if (status == 0) {
      reply(exchange, 200, "{\"data\":{\"id\":\"msg-1\"}}");
      return;
    }
    reply(exchange, status, "{\"code\":429,\"message\":\"Too Many Requests\"}");
  }

  private void record(HttpExchange exchange) throws IOException {
    String body;
    try (InputStream stream = exchange.getRequestBody()) {
      body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    heard.add(
        new Heard(
            exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("authorization"),
            body));
  }

  private static void reply(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("content-type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  /** La configurazione che punta al gemello, nella forma che `Config.Scope` restituisce. */
  Map<String, String> configuration() {
    return Map.of(
        "baseUrl", url(),
        "oauthUrl", url(),
        "email", "prova@example.test",
        "apiKey", "chiave-finta",
        "sender", "Rogita");
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
