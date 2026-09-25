package cc.coopersoft.keycloak.phone.providers.spi.impl;

import cc.coopersoft.keycloak.phone.providers.jpa.TokenCode;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * **I codici in corso di un numero sono un insieme, non una riga.** Il controllo «c'è già un codice?»
 * e il salvataggio del nuovo stanno ai due capi dell'invio dell'SMS, in una transazione che si chiude
 * a fine richiesta: due richieste che si incrociano ne salvano uno ciascuna, e il telefono li riceve
 * entrambi. Chi chiedeva «il» codice in corso cadeva con NonUniqueResultException — staging,
 * 2026-09-25. Qui si sceglie esplicitamente: il più recente è quello in corso, e vale ogni codice che
 * il telefono ha ricevuto.
 */
public final class OngoingCodes {

  private OngoingCodes() {}

  public static Optional<TokenCode> newest(List<TokenCode> ongoing) {
    return ongoing.stream().max(Comparator.comparing(TokenCode::getCreatedAt));
  }

  public static Optional<TokenCode> matching(List<TokenCode> ongoing, String code) {
    if (code == null) return Optional.empty();
    byte[] typed = code.getBytes(StandardCharsets.UTF_8);
    return ongoing.stream()
        .filter(token -> MessageDigest.isEqual(token.getCode().getBytes(StandardCharsets.UTF_8), typed))
        .max(Comparator.comparing(TokenCode::getCreatedAt));
  }
}
