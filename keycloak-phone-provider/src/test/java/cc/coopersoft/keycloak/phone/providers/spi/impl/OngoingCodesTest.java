package cc.coopersoft.keycloak.phone.providers.spi.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cc.coopersoft.keycloak.phone.providers.jpa.TokenCode;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Due codici validi per lo stesso numero sono uno stato che il modello ammette: due richieste che
 * si incrociano mentre il primo SMS parte ne salvano uno ciascuna. Prima la verifica chiedeva «il»
 * codice in corso e cadeva con NonUniqueResultException (staging, 2026-09-25).
 */
class OngoingCodesTest {

  private static TokenCode code(String id, String value, long createdAt) {
    TokenCode token = new TokenCode();
    token.setId(id);
    token.setCode(value);
    token.setCreatedAt(new Date(createdAt));
    return token;
  }

  @Test
  void nessun_codice_in_corso_è_un_vuoto_non_un_errore() {
    assertTrue(OngoingCodes.newest(List.of()).isEmpty());
    assertTrue(OngoingCodes.matching(List.of(), "123456").isEmpty());
  }

  @Test
  void con_due_codici_in_corso_il_più_recente_è_quello_in_corso() {
    List<TokenCode> both = List.of(code("a", "111111", 1_000), code("b", "222222", 5_000));

    assertEquals("b", OngoingCodes.newest(both).orElseThrow().getId());
  }

  @Test
  void con_due_codici_in_corso_vale_ciascuno_dei_due_perché_entrambi_sono_arrivati_al_telefono() {
    List<TokenCode> both = List.of(code("a", "111111", 1_000), code("b", "222222", 5_000));

    assertEquals("a", OngoingCodes.matching(both, "111111").orElseThrow().getId());
    assertEquals("b", OngoingCodes.matching(both, "222222").orElseThrow().getId());
  }

  @Test
  void un_codice_che_nessuno_ha_ricevuto_non_vale() {
    List<TokenCode> both = List.of(code("a", "111111", 1_000), code("b", "222222", 5_000));

    assertTrue(OngoingCodes.matching(both, "333333").isEmpty());
    assertTrue(OngoingCodes.matching(both, null).isEmpty());
  }
}
