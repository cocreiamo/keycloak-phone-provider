<#--
  Il primo passo di «entra col telefono»: si chiede il numero e basta.

  **Nessun JavaScript, e nessuna CDN.** Il template che questo progetto ha per il numero tira Vue e
  axios da jsdelivr per fare la chiamata di mezzo: su una pagina di login vuol dire che chi serve
  quella CDN può cambiare ciò che l'utente vede mentre digita un numero. Due pagine servite dal
  server non hanno bisogno di niente, e reggono anche senza JavaScript.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('phoneNumber'); section>
    <#if section = "header">
        ${msg("loginPhoneTitle")}
    <#elseif section = "form">
        <form id="kc-phone-number-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <label for="phoneNumber" class="${properties.kcLabelClass!}">${msg("phoneNumber")}</label>
                <input tabindex="1" id="phoneNumber" name="phoneNumber" type="tel"
                       class="${properties.kcInputClass!}"
                       value="${(attemptedPhoneNumber!'')}"
                       autocomplete="tel" inputmode="tel" autofocus
                       aria-invalid="<#if messagesPerField.existsError('phoneNumber')>true</#if>"/>
            </div>

            <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                <input tabindex="2" name="save" id="kc-login" type="submit"
                       class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       value="${msg("sendVerificationCode")}"/>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
