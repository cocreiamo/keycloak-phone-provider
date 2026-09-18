package cc.coopersoft.keycloak.phone.authentication.authenticators.browser;

import cc.coopersoft.keycloak.phone.Utils;
import cc.coopersoft.keycloak.phone.authentication.forms.SupportPhonePages;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneProvider;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneVerificationCodeProvider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.protocol.oidc.OIDCLoginProtocol;
import org.keycloak.services.validation.Validation;

/**
 * <strong>Entrare col solo numero di telefono, dal browser.</strong>
 *
 * <p>Questo progetto sa già fare tre cose col telefono: accedere col telefono <em>più la
 * password</em>, usare l'SMS come <em>secondo</em> fattore, e — solo via Direct Grant, cioè da una
 * API — entrare col solo numero creando la persona se non c'è. Quello che mancava è la stessa terza
 * cosa <strong>dal browser</strong>, che è la porta principale di un prodotto rivolto a chi compra
 * casa: chiede un numero, manda un codice, e chi lo digita è dentro. Nessuna password da inventare,
 * nessuna email da confermare.
 *
 * <p>La logica di fondo non è nuova e non è stata riscritta: è quella di {@code
 * EverybodyPhoneAuthenticator}, che valida il codice, trova la persona per numero o la crea, e
 * segna il numero come verificato. Qui cambia solo <strong>da dove arrivano numero e codice</strong>
 * — due form invece che i parametri di una richiesta — e il fatto che ogni passo è una pagina.
 *
 * <p><strong>Due passi e non uno.</strong> Una pagina sola con «numero», «mandami il codice» e
 * «codice» vorrebbe del JavaScript per la chiamata di mezzo, e il template che questo progetto ha
 * per quel caso tira Vue e axios da una CDN. Due pagine servite dal server non hanno bisogno di
 * niente, reggono senza JavaScript, e non allargano la superficie di una pagina di login.
 */
public class PhoneLoginAuthenticator implements Authenticator {

  private static final Logger logger = Logger.getLogger(PhoneLoginAuthenticator.class);

  private static final String PAGE_NUMBER = "login-phone-number.ftl";
  private static final String PAGE_CODE = "login-phone-code.ftl";

  /** Il numero a cui il codice è partito, fra il primo passo e il secondo. */
  static final String NOTE_PHONE_NUMBER = "rogita.phone.number";

  @Override
  public void authenticate(AuthenticationFlowContext context) {
    context.challenge(numberForm(context, null, null));
  }

  @Override
  public void action(AuthenticationFlowContext context) {
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    String pending = context.getAuthenticationSession().getAuthNote(NOTE_PHONE_NUMBER);

    // «Cambia numero» torna al primo passo, e butta quello in attesa: senza, chi sbaglia una cifra
    // resterebbe su una pagina che aspetta un codice che non arriverà mai.
    if (form.containsKey("changeNumber") || pending == null) {
      askForCode(context, form.getFirst(SupportPhonePages.FIELD_PHONE_NUMBER));
      return;
    }
    verifyCode(context, pending, form.getFirst(SupportPhonePages.FIELD_VERIFICATION_CODE));
  }

  /** Primo passo: il numero arriva, il codice parte, e la pagina diventa quella del codice. */
  private void askForCode(AuthenticationFlowContext context, String raw) {
    String phoneNumber;
    try {
      phoneNumber = Utils.canonicalizePhoneNumber(context.getSession(), raw);
    } catch (Exception malformed) {
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.NUMBER_INVALID.message()));
      return;
    }

    PhoneProvider phones = context.getSession().getProvider(PhoneProvider.class);
    int expires;
    try {
      expires =
          phones.sendTokenCode(
              phoneNumber, context.getConnection().getRemoteAddr(), TokenCodeType.AUTH, null);
    } catch (ForbiddenException abused) {
      // Il limite per numero e per indirizzo lo tiene il provider, ed è la difesa che conta:
      // ogni SMS si paga, e chi bussa a raffica lo fa perché costa a noi.
      logger.warnf("troppi codici chiesti per %s", phoneNumber);
      context.challenge(
          numberForm(context, raw, SupportPhonePages.Errors.ABUSED.message()));
      return;
    } catch (Exception failed) {
      logger.warn("il codice non è partito", failed);
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.FAIL.message()));
      return;
    }

    context.getAuthenticationSession().setAuthNote(NOTE_PHONE_NUMBER, phoneNumber);
    context.challenge(codeForm(context, phoneNumber, expires, null));
  }

  /** Secondo passo: il codice è quello, e allora la persona c'è o nasce adesso. */
  private void verifyCode(AuthenticationFlowContext context, String phoneNumber, String code) {
    if (Validation.isBlank(code)) {
      context.failureChallenge(
          AuthenticationFlowError.INVALID_CREDENTIALS,
          codeForm(context, phoneNumber, 0, SupportPhonePages.Errors.NOT_MATCH.message()));
      return;
    }

    PhoneVerificationCodeProvider codes =
        context.getSession().getProvider(PhoneVerificationCodeProvider.class);
    TokenCodeRepresentation ongoing = codes.ongoingProcess(phoneNumber, TokenCodeType.AUTH);
    if (ongoing == null) {
      // Scaduto, o già speso: si torna al numero, perché un codice che non c'è non si corregge.
      context.getAuthenticationSession().removeAuthNote(NOTE_PHONE_NUMBER);
      context.failureChallenge(
          AuthenticationFlowError.EXPIRED_CODE,
          numberForm(context, phoneNumber, SupportPhonePages.Errors.NO_PROCESS.message()));
      return;
    }
    if (!ongoing.getCode().equals(code)) {
      // **Si risponde di no, non si alza**: il tentativo sbagliato deve restare contato, e una
      // pagina d'errore lascia riprovare senza rimandare un SMS.
      context.failureChallenge(
          AuthenticationFlowError.INVALID_CREDENTIALS,
          codeForm(context, phoneNumber, 0, SupportPhonePages.Errors.NOT_MATCH.message()));
      return;
    }

    Optional<UserModel> found =
        Utils.findUserByPhone(context.getSession(), context.getRealm(), phoneNumber);
    UserModel user = found.orElseGet(() -> createUser(context, phoneNumber));
    if (user == null) {
      return;
    }

    context.setUser(user);
    codes.tokenValidated(user, phoneNumber, ongoing.getId(), false);
    context.getAuthenticationSession().removeAuthNote(NOTE_PHONE_NUMBER);
    context.success();
  }

  /**
   * Chi entra per la prima volta nasce qui, con il numero come nome utente. Se quel nome fosse già
   * di qualcun altro — un utente creato per altra via — il conflitto si dice, non si aggira.
   */
  private UserModel createUser(AuthenticationFlowContext context, String phoneNumber) {
    KeycloakSession session = context.getSession();
    RealmModel realm = context.getRealm();
    if (session.users().getUserByUsername(realm, phoneNumber) != null) {
      context.failureChallenge(
          AuthenticationFlowError.USER_CONFLICT,
          codeForm(context, phoneNumber, 0, SupportPhonePages.Errors.EXISTS.message()));
      return null;
    }
    UserModel created = session.users().addUser(realm, phoneNumber);
    created.setEnabled(true);
    context.getAuthenticationSession().setClientNote(OIDCLoginProtocol.LOGIN_HINT_PARAM, phoneNumber);
    return created;
  }

  private Response numberForm(AuthenticationFlowContext context, String phoneNumber, String error) {
    var form =
        context
            .form()
            .setAttribute(SupportPhonePages.ATTRIBUTE_SUPPORT_PHONE, true)
            .setAttribute(SupportPhonePages.ATTEMPTED_PHONE_NUMBER, phoneNumber == null ? "" : phoneNumber);
    if (error != null) {
      form.setError(error);
    }
    return form.createForm(PAGE_NUMBER);
  }

  private Response codeForm(
      AuthenticationFlowContext context, String phoneNumber, int expires, String error) {
    var form =
        context
            .form()
            .setAttribute(SupportPhonePages.ATTRIBUTE_SUPPORT_PHONE, true)
            .setAttribute(SupportPhonePages.ATTEMPTED_PHONE_NUMBER, phoneNumber)
            .setAttribute("expires", expires);
    if (error != null) {
      form.setError(error);
    }
    return form.createForm(PAGE_CODE);
  }

  /** Chi entra da qui non esiste ancora: pretendere una persona renderebbe il gesto impossibile. */
  @Override
  public boolean requiresUser() {
    return false;
  }

  @Override
  public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
    return true;
  }

  @Override
  public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {}

  @Override
  public void close() {}
}
