package cc.coopersoft.keycloak.phone.providers.sender;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.coopersoft.keycloak.phone.providers.exception.MessageSendException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/**
 * Il corriere contro un gemello che parla il dialetto di openapi.it. Prova ciò che una finta non
 * proverebbe: <strong>la lingua</strong> — dove va la chiave dell'account, cosa c'è nello scope del
 * token, che forma ha il corpo del messaggio — e il comportamento che costa soldi se è sbagliato:
 * un token coniato una volta sola, e buttato quando il fornitore lo rifiuta.
 */
class OpenapiSmsSenderServiceTest {

  @Test
  void conia_il_token_in_basic_e_manda_il_messaggio_col_bearer() throws Exception {
    try (OpenapiTwin twin = new OpenapiTwin()) {
      senderOver(twin).sendMessage("+393331234567", "il tuo codice è 123456");

      List<OpenapiTwin.Heard> heard = twin.heard();
      assertEquals(2, heard.size(), "prima si conia il token, poi si manda il messaggio");

      OpenapiTwin.Heard conio = heard.get(0);
      assertEquals(
          "/token", conio.path(), "il percorso del conio è /token: /tokens risponde 401 in produzione");
      assertEquals(
          "Basic " + base64("prova@example.test:chiave-finta"),
          conio.authorization(),
          "la chiave dell'account vive solo qui, in Basic, e non tocca mai l'API dei messaggi");
      assertTrue(
          conio.body().contains("\"POST:127.0.0.1:" + porta(twin) + "/IT-messages\""),
          "lo scope nomina lo stesso host a cui si parla, o openapi.it risponde «API not enabled»");

      OpenapiTwin.Heard invio = heard.get(1);
      assertEquals("/IT-messages", invio.path());
      assertEquals("Bearer bearer-1", invio.authorization());
      assertTrue(invio.body().contains("\"sender\":\"Rogita\""), "il mittente registrato");
      assertTrue(invio.body().contains("\"recipient\":\"+393331234567\""));
      assertTrue(invio.body().contains("il tuo codice è 123456"));
    }
  }

  @Test
  void il_token_si_riusa_finche_vale() throws Exception {
    try (OpenapiTwin twin = new OpenapiTwin()) {
      OpenapiSmsSenderService sender = senderOver(twin);
      sender.sendMessage("+393331234567", "uno");
      sender.sendMessage("+393339876543", "due");

      assertEquals(1, twin.minted(), "un conio per messaggio raddoppierebbe le chiamate");
      assertEquals(3, twin.heard().size(), "un conio e due messaggi");
    }
  }

  @Test
  void un_token_rifiutato_si_butta_e_il_prossimo_invio_ne_conia_un_altro() throws Exception {
    try (OpenapiTwin twin = new OpenapiTwin()) {
      OpenapiSmsSenderService sender = senderOver(twin);
      sender.sendMessage("+393331234567", "uno");

      twin.failNextMessageWith(401);
      MessageSendException rifiutato =
          assertThrows(MessageSendException.class, () -> sender.sendMessage("+393331234567", "due"));
      assertEquals("openapi_token_rejected", rifiutato.getErrorCode());

      // **Il punto della prova**: il tentativo dopo non riusa il token che il fornitore ha
      // rifiutato. Senza buttarlo, ogni invio successivo fallirebbe allo stesso modo, per sempre.
      sender.sendMessage("+393331234567", "tre");
      assertEquals(2, twin.minted());
      assertEquals("Bearer bearer-2", twin.heard().get(twin.heard().size() - 1).authorization());
    }
  }

  @Test
  void un_rifiuto_del_fornitore_porta_il_codice_che_il_corpo_dichiara() throws Exception {
    try (OpenapiTwin twin = new OpenapiTwin()) {
      OpenapiSmsSenderService sender = senderOver(twin);
      twin.failNextMessageWith(400);

      MessageSendException rifiutato =
          assertThrows(MessageSendException.class, () -> sender.sendMessage("+393331234567", "uno"));

      // openapi.it mette il motivo nel corpo, non nello status: chi legge il log vuole quello.
      assertEquals("429", rifiutato.getErrorCode());
    }
  }

  @Test
  void un_token_senza_scadenza_leggibile_non_ferma_niente() throws Exception {
    try (OpenapiTwin twin = new OpenapiTwin()) {
      twin.silentAboutExpiry();
      OpenapiSmsSenderService sender = senderOver(twin);

      sender.sendMessage("+393331234567", "uno");
      sender.sendMessage("+393331234567", "due");

      // Senza una scadenza dichiarata si tiene la propria, che è un'ora: il secondo invio riusa.
      assertEquals(1, twin.minted());
    }
  }

  private static OpenapiSmsSenderService senderOver(OpenapiTwin twin) {
    return new OpenapiSmsSenderService(scope(twin.configuration()), session());
  }

  private static String porta(OpenapiTwin twin) {
    return twin.url().substring(twin.url().lastIndexOf(':') + 1);
  }

  private static String base64(String raw) {
    return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** La configurazione nella forma in cui Keycloak la consegna: una mappa dietro un'interfaccia. */
  private static Config.Scope scope(Map<String, String> values) {
    return (Config.Scope)
        Proxy.newProxyInstance(
            Config.Scope.class.getClassLoader(),
            new Class<?>[] {Config.Scope.class},
            (proxy, method, args) -> {
              if (method.getName().equals("get") && args != null && args.length >= 1) {
                String found = values.get(String.valueOf(args[0]));
                return found != null ? found : (args.length > 1 ? args[1] : null);
              }
              return null;
            });
  }

  /**
   * La sessione più piccola che la classe base regge: le serve solo il nome del realm, per il
   * messaggio predefinito. Costruirne una vera vorrebbe un Keycloak acceso, e qui la cosa sotto
   * prova è il dialetto del corriere, non Keycloak.
   */
  private static KeycloakSession session() {
    RealmModel realm =
        (RealmModel)
            Proxy.newProxyInstance(
                RealmModel.class.getClassLoader(),
                new Class<?>[] {RealmModel.class},
                (proxy, method, args) ->
                    method.getName().equals("getDisplayName") ? "Rogita" : null);
    KeycloakContext context =
        (KeycloakContext)
            Proxy.newProxyInstance(
                KeycloakContext.class.getClassLoader(),
                new Class<?>[] {KeycloakContext.class},
                (proxy, method, args) -> method.getName().equals("getRealm") ? realm : null);
    return (KeycloakSession)
        Proxy.newProxyInstance(
            KeycloakSession.class.getClassLoader(),
            new Class<?>[] {KeycloakSession.class},
            (proxy, method, args) -> method.getName().equals("getContext") ? context : null);
  }
}
