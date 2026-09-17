package cc.coopersoft.keycloak.phone.providers.sender;

import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderService;
import cc.coopersoft.keycloak.phone.providers.spi.MessageSenderServiceProviderFactory;
import java.util.List;
import org.keycloak.Config.Scope;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Il corriere di rogita per gli SMS in Italia. Si sceglie nel realm col suo id, {@code openapi}, e
 * la sua configurazione arriva da Keycloak — cinque valori, di cui uno solo è un segreto.
 *
 * <p><strong>La configurazione si controlla qui, in {@code init}, e non nel provider.</strong> Un
 * factory nasce all'avvio, un provider alla prima richiesta che ne ha bisogno: controllare là
 * vorrebbe dire scoprire un refuso nel nome di una variabile alla prima persona che si iscrive, di
 * notte. Qui invece Keycloak non parte, e chi ha sbagliato lo legge subito.
 *
 * <p><strong>Come si configura, verificato dal vivo sulla 26.5.5</strong>, perché tre cose si
 * possono sbagliare e nessuna delle tre dà un errore che lo dice:
 *
 * <ol>
 *   <li><strong>lo SPI si chiama {@code messageSenderService}</strong>, non «phone…» come il
 *       progetto che lo ospita: l'opzione è {@code --spi-message-sender-service--openapi--…}, e
 *       sbagliare il nome dello SPI non produce nessun errore — l'opzione viene semplicemente
 *       ignorata, e il corriere si ritrova senza configurazione;
 *   <li><strong>la proprietà si scrive in kebab-case e arriva camelCase</strong>: {@code base-url}
 *       sulla riga di comando è {@code config.get("baseUrl")} qui dentro. Scrivere {@code baseUrl}
 *       nell'opzione non arriva;
 *   <li><strong>i separatori sono due trattini</strong>, e come variabile d'ambiente diventano due
 *       underscore. Il formato vecchio con un trattino solo Keycloak lo accetta, ma avverte che
 *       <em>non lo tratta come opzione di build</em> — che è il modo silenzioso di non funzionare.
 * </ol>
 *
 * <p>Il controllo qui sotto esiste per rendere rumorose tutte e tre.
 */
public class OpenapiMessageSenderServiceProviderFactory implements MessageSenderServiceProviderFactory {

  /** I cinque valori senza i quali un SMS non parte, e con cui `Config.Scope` li nomina. */
  private static final List<String> REQUIRED =
      List.of("baseUrl", "oauthUrl", "email", "apiKey", "sender");

  private Scope config;

  @Override
  public MessageSenderService create(KeycloakSession session) {
    return new OpenapiSmsSenderService(config, session);
  }

  @Override
  public void init(Scope config) {
    this.config = config;
    for (String key : REQUIRED) {
      String value = config.get(key);
      if (value == null || value.isBlank()) {
        throw new IllegalStateException(
            "il corriere SMS openapi.it vuole la configurazione '"
                + key
                + "', che non è arrivata: --spi-message-sender-service--openapi--"
                + kebab(key));
      }
    }
  }

  /** `baseUrl` sulla riga di comando si scrive `base-url`: il messaggio d'errore lo dice. */
  private static String kebab(String key) {
    return key.replaceAll("([a-z])([A-Z])", "$1-$2").toLowerCase();
  }

  @Override
  public void postInit(KeycloakSessionFactory keycloakSessionFactory) {}

  @Override
  public void close() {}

  @Override
  public String getId() {
    return "openapi";
  }
}
