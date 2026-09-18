<#--
  Il secondo passo di «entra col telefono»: il codice arrivato via SMS.

  Il numero si rimostra, perché chi sbaglia una cifra deve accorgersene qui e non dopo avere
  aspettato un SMS che non arriva; e «cambia numero» è un pulsante dello stesso form, così il
  ritorno al primo passo non chiede JavaScript.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('code'); section>
    <#if section = "header">
        ${msg("loginPhoneCodeTitle")}
    <#elseif section = "form">
        <form id="kc-phone-code-form" action="${url.loginAction}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <p class="${properties.kcInfoAreaWrapperClass!}">
                    ${msg("loginPhoneCodeSentTo", (attemptedPhoneNumber!''))}
                </p>
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <label for="code" class="${properties.kcLabelClass!}">${msg("verificationCode")}</label>
                <input tabindex="1" id="code" name="code" type="text"
                       class="${properties.kcInputClass!}"
                       autocomplete="one-time-code" inputmode="numeric" autofocus
                       aria-invalid="<#if messagesPerField.existsError('code')>true</#if>"/>
            </div>

            <div id="kc-form-buttons" class="${properties.kcFormGroupClass!}">
                <input tabindex="2" name="save" id="kc-login" type="submit"
                       class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}"
                       value="${msg("doSubmit")}"/>
                <input tabindex="3" name="changeNumber" id="kc-change-number" type="submit"
                       class="${properties.kcButtonClass!} ${properties.kcButtonDefaultClass!} ${properties.kcButtonBlockClass!}"
                       value="${msg("loginPhoneChangeNumber")}"/>
            </div>
        </form>
    </#if>
</@layout.registrationLayout>
