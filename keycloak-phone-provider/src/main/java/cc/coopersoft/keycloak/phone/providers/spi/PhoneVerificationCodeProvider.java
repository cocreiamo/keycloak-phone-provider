package cc.coopersoft.keycloak.phone.providers.spi;

import cc.coopersoft.keycloak.phone.providers.constants.TokenCodeType;
import cc.coopersoft.keycloak.phone.providers.representations.TokenCodeRepresentation;
import org.keycloak.models.UserModel;
import org.keycloak.provider.Provider;

public interface PhoneVerificationCodeProvider extends Provider {

    /** Il codice in corso più recente, o null: dice se c'è un processo, non quale codice vale. */
    TokenCodeRepresentation ongoingProcess(String phoneNumber, TokenCodeType tokenCodeType);

    /** Il codice in corso uguale a {@code code}, o null: i codici in corso di un numero sono un insieme. */
    TokenCodeRepresentation ongoingProcess(String phoneNumber, TokenCodeType tokenCodeType, String code);

    /**
     * Serializza i processi di uno stesso numero fino alla fine della transazione: chi arriva dopo
     * vede il codice di chi è arrivato prima, invece di mandarne un secondo.
     */
    void serializeProcess(String phoneNumber, TokenCodeType tokenCodeType);

    boolean isAbusing(String phoneNumber, TokenCodeType tokenCodeType,String sourceAddr ,int sourceHourMaximum,int targetHourMaximum);

    void persistCode(TokenCodeRepresentation tokenCode, TokenCodeType tokenCodeType, int tokenExpiresIn);

    void validateCode(UserModel user, String phoneNumber, String code);

    void validateCode(UserModel user, String phoneNumber, String code, TokenCodeType tokenCodeType);

    void validateProcess(String tokenCodeId, UserModel user);

    //void cleanUpAction(UserModel user, boolean isOTP);

    void tokenValidated(UserModel user, String phoneNumber, String tokenCodeId, boolean isOTP);
}
