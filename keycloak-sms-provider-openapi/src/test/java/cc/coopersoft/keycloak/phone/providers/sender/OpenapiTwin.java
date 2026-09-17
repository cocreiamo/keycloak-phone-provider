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
 * Il gemello di openapi.it: **parla il suo dialetto, copiato**, non ricordato — la busta {@code
 * data}, il {@code token} con il suo {@code expireAt}, il {@code error} numerico nel corpo quando
 * qualcosa va storto. Il server è quello del JDK, così non entra nessuna dipendenza nel progetto.
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

  /** Cosa mettere in `expireAt`: `null` significa «non lo dico», ed è un caso che capita. */
  private volatile String expireAt = "2099-01-01T00:00:00Z";

  OpenapiTwin() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/tokens", this::tokens);
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
    expireAt = null;
  }

  private void tokens(HttpExchange exchange) throws IOException {
    record(exchange);
    int number = minted.incrementAndGet();
    String expiry = expireAt == null ? "" : ",\"expireAt\":\"" + expireAt + "\"";
    reply(exchange, 200, "{\"data\":{\"token\":\"bearer-" + number + "\"" + expiry + "}}");
  }

  private void messages(HttpExchange exchange) throws IOException {
    record(exchange);
    int status = nextMessageStatus;
    nextMessageStatus = 0;
    if (status == 0) {
      reply(exchange, 200, "{\"data\":{\"id\":\"msg-1\"}}");
      return;
    }
    reply(exchange, status, "{\"error\":429}");
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
