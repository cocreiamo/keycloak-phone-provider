package cc.coopersoft.keycloak.phone.authentication.requiredactions;

import cc.coopersoft.keycloak.phone.Utils;
import cc.coopersoft.keycloak.phone.authentication.forms.SupportPhonePages;
import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneProvider;
import cc.coopersoft.keycloak.phone.providers.spi.PhoneVerificationCodeProvider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.keycloak.authentication.InitiatedActionSupport;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.Constants;
import org.keycloak.models.UserModel;
import org.keycloak.services.validation.Validation;

/**
 * <strong>Ogni persona ha un numero verificato, anche chi è entrata da un'altra porta.</strong>
 *
 * <p>Chi entra col telefono lo ha già; chi entra da Google, o nasce dall'API di amministrazione,
 * no. L'azione scatta da sé per chiunque non lo abbia ({@link #evaluateTriggers}) e salta chi lo
 * ha: non serve marcarla predefinita, e non si ripresenta a chi ha già fatto il suo dovere.
 *
 * <p>Tre differenze dalla versione di questo progetto, tutte volute:
 *
 * <ul>
 *   <li><strong>Le pagine sono quelle del login col telefono</strong>, servite dal server in due
 *       passi. L'originale caricava Vue e axios da una CDN, e Vue senza versione: la pagina di login
 *       avrebbe eseguito l'ultima libreria pubblicata da chiunque controlli quel pacchetto.
 *   <li><strong>Un numero verificato da un altro account si rifiuta prima di mandare il
 *       codice.</strong> L'originale lo accettava e lo toglieva all'altro account, che è una scelta
 *       sul riciclo dei numeri; la nostra è che un numero è una persona, e chi lo trova già preso
 *       entra col telefono e collega da lì l'altra porta.
 *   <li>Il codice non si scrive nel log.
 * </ul>
 */
public class UpdatePhoneNumberRequiredAction implements RequiredActionProvider {

  public static final String PROVIDER_ID = "UPDATE_PHONE_NUMBER";

  private static final Logger logger = Logger.getLogger(UpdatePhoneNumberRequiredAction.class);

  private static final String PAGE_NUMBER = "login-phone-number.ftl";
  private static final String PAGE_CODE = "login-phone-code.ftl";

  static final String NOTE_PHONE_NUMBER = "rogita.phone.pending";

  @Override
  public InitiatedActionSupport initiatedActionSupport() {
    return InitiatedActionSupport.SUPPORTED;
  }

  @Override
  public void evaluateTriggers(RequiredActionContext context) {
    if (!hasVerifiedPhone(context.getUser())) {
      context.getUser().addRequiredAction(PROVIDER_ID);
    }
  }

  @Override
  public void requiredActionChallenge(RequiredActionContext context) {
    // Avviata dall'applicazione per cambiare numero, parte anche per chi un numero lo ha già.
    if (hasVerifiedPhone(context.getUser()) && !initiatedByApplication(context)) {
      context.success();
      return;
    }
    context.challenge(numberForm(context, null, null));
  }

  @Override
  public void processAction(RequiredActionContext context) {
    MultivaluedMap<String, String> form = context.getHttpRequest().getDecodedFormParameters();
    String pending = context.getAuthenticationSession().getAuthNote(NOTE_PHONE_NUMBER);

    if (form.containsKey("changeNumber") || pending == null) {
      context.getAuthenticationSession().removeAuthNote(NOTE_PHONE_NUMBER);
      askForCode(context, form.getFirst(SupportPhonePages.FIELD_PHONE_NUMBER));
      return;
    }
    verifyCode(context, pending, form.getFirst(SupportPhonePages.FIELD_VERIFICATION_CODE));
  }

  private void askForCode(RequiredActionContext context, String raw) {
    if (Validation.isBlank(raw)) {
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.MISSING.message()));
      return;
    }
    String phoneNumber;
    try {
      phoneNumber = Utils.canonicalizePhoneNumber(context.getSession(), raw);
    } catch (Exception malformed) {
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.NUMBER_INVALID.message()));
      return;
    }

    boolean takenByAnother =
        Utils.findUserByPhone(context.getSession(), context.getRealm(), phoneNumber)
            .filter(other -> !other.getId().equals(context.getUser().getId()))
            .isPresent();
    if (takenByAnother) {
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.EXISTS.message()));
      return;
    }

    int expires;
    try {
      expires =
          context
              .getSession()
              .getProvider(PhoneProvider.class)
              .sendTokenCode(
                  phoneNumber, context.getConnection().getRemoteAddr(), TokenCodeType.VERIFY, null);
    } catch (ForbiddenException abused) {
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.ABUSED.message()));
      return;
    } catch (Exception failed) {
      logger.warn("il codice di verifica non è partito", failed);
      context.challenge(numberForm(context, raw, SupportPhonePages.Errors.FAIL.message()));
      return;
    }

    context.getAuthenticationSession().setAuthNote(NOTE_PHONE_NUMBER, phoneNumber);
    context.challenge(codeForm(context, phoneNumber, expires, null));
  }

  private void verifyCode(RequiredActionContext context, String phoneNumber, String code) {
    PhoneVerificationCodeProvider codes =
        context.getSession().getProvider(PhoneVerificationCodeProvider.class);
    TokenCodeRepresentation ongoing = codes.ongoingProcess(phoneNumber, TokenCodeType.VERIFY);
    if (ongoing == null) {
      context.getAuthenticationSession().removeAuthNote(NOTE_PHONE_NUMBER);
      context.challenge(
          numberForm(context, phoneNumber, SupportPhonePages.Errors.NO_PROCESS.message()));
      return;
    }
    if (Validation.isBlank(code) || !ongoing.getCode().equals(code)) {
      context.challenge(
          codeForm(context, phoneNumber, 0, SupportPhonePages.Errors.NOT_MATCH.message()));
      return;
    }

    codes.tokenValidated(context.getUser(), phoneNumber, ongoing.getId(), false);
    context.getAuthenticationSession().removeAuthNote(NOTE_PHONE_NUMBER);
    context.success();
  }

  private static boolean hasVerifiedPhone(UserModel user) {
    return !Validation.isBlank(user.getFirstAttribute("phoneNumber"))
        && "true".equals(user.getFirstAttribute("phoneNumberVerified"));
  }

  private static boolean initiatedByApplication(RequiredActionContext context) {
    return PROVIDER_ID.equals(context.getAuthenticationSession().getClientNote(Constants.KC_ACTION));
  }

  private Response numberForm(RequiredActionContext context, String phoneNumber, String error) {
    var form =
        context
            .form()
            .setAttribute(SupportPhonePages.ATTRIBUTE_SUPPORT_PHONE, true)
            .setAttribute(
                SupportPhonePages.ATTEMPTED_PHONE_NUMBER, phoneNumber == null ? "" : phoneNumber);
    if (error != null) {
      form.setError(error);
    }
    return form.createForm(PAGE_NUMBER);
  }

  private Response codeForm(
      RequiredActionContext context, String phoneNumber, int expires, String error) {
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

  @Override
  public void close() {}
}
