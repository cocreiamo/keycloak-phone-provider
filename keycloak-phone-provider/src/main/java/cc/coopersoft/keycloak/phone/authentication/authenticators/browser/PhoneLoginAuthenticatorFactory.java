package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import java.util.List;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel.Requirement;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

/** Entrare col solo numero, dal browser: due pagine, nessuna password. */
public class PhoneLoginAuthenticatorFactory implements AuthenticatorFactory {

  public static final String PROVIDER_ID = "phone-login-authenticator";

  private static final PhoneLoginAuthenticator SINGLETON = new PhoneLoginAuthenticator();

  /**
   * {@code ALTERNATIVE} perché convive con le altre porte: chi ha una passkey non passa di qui, e
   * chi ha un cookie valido nemmeno. {@code REQUIRED} la renderebbe l'unica strada.
   */
  private static final Requirement[] REQUIREMENTS = {
    Requirement.REQUIRED, Requirement.ALTERNATIVE, Requirement.DISABLED
  };

  @Override
  public String getId() {
    return PROVIDER_ID;
  }

  @Override
  public String getDisplayType() {
    return "Phone Login";
  }

  @Override
  public String getHelpText() {
    return "Entra con il solo numero di telefono: chiede il numero, manda un codice, e crea la"
        + " persona se non esiste ancora.";
  }

  @Override
  public String getReferenceCategory() {
    return "phone";
  }

  @Override
  public boolean isConfigurable() {
    return false;
  }

  @Override
  public Requirement[] getRequirementChoices() {
    return REQUIREMENTS;
  }

  @Override
  public boolean isUserSetupAllowed() {
    return false;
  }

  @Override
  public List<ProviderConfigProperty> getConfigProperties() {
    return List.of();
  }

  @Override
  public Authenticator create(KeycloakSession session) {
    return SINGLETON;
  }

  @Override
  public void init(Config.Scope config) {}

  @Override
  public void postInit(KeycloakSessionFactory factory) {}

  @Override
  public void close() {}
}
