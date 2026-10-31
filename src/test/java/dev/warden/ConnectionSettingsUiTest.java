package dev.warden;

import dev.warden.dashboard.ConnectionSettingsUi;
import dev.warden.testing.Check;
import dev.warden.testing.Suite;
import java.util.Set;
import java.util.regex.Pattern;

/** Static security/accessibility contracts; the dashboard integration tests exercise HTTP. */
public final class ConnectionSettingsUiTest implements Suite {
    @Override public String name() { return "connection-settings-ui"; }

    @Override public void run(Check check) {
        String html = ConnectionSettingsUi.html();
        check.contains("the editor has an accessible section label", html, "aria-labelledby=\"connection-editor-title\"");
        check.contains("the section offers connection creation", html, "Добавить подключение");
        check.contains("JoinGonka can be selected separately from GonkaRouter", html,
                "<option value=\"joingonka\">JoinGonka Gateway</option>");
        check.contains("the form asks for a variable name, not a credential", html, "Укажите имя переменной, не значение ключа");
        check.contains("native check is described without implying model verification", html, "Проверка ключа не вызывает модель");
        check.contains("deleting a connection is distinguished from key revocation", html, "Удаление подключения не отзывает ключ");
        check.contains("changes do not implicitly enable a model profile", html, "Сохранение не добавляет модель в состав ролей");

        var inputs = Pattern.compile("<input\\b[^>]*name=\"([^\"]+)\"[^>]*>").matcher(html);
        Set<String> allowedNames = Set.of("id", "base_url", "env", "enabled");
        int count = 0;
        while (inputs.find()) {
            count++;
            check.that("the editor accepts only nonsecret input fields", allowedNames.contains(inputs.group(1)));
            check.that("the editor does not collect a password", !inputs.group().contains("type=\"password\""));
        }
        check.eq("all expected text/reference controls are present", 4, count);
        check.that("API key values have no input field", !html.contains("name=\"api_key\"") && !html.contains("name=\"key\""));
        check.that("the browser does not persist credentials or drafts", !html.contains("localStorage") && !html.contains("sessionStorage"));
        check.that("server data cannot reach an HTML insertion sink", !html.contains("innerHTML") && !html.contains("outerHTML") && !html.contains("insertAdjacentHTML"));
        check.contains("server values use text nodes", html, "textContent");
        check.contains("the script has a private scope alongside dashboard globals", html, "(()=>{");
        check.that("rendered JavaScript has no literal newline in the join string",
                !html.contains("join('\n')") && !html.contains("join(\"\n\")"));
        check.contains("requests carry the process CSRF token", html, "'X-Warden-Token':processToken");
        check.eq("the process token is inserted once in a nonsecret data attribute", 1,
                html.split(Pattern.quote("{{SETTINGS_TOKEN}}"), -1).length - 1);
        check.contains("edits carry the originally opened revision", html, "payload.revision=editing.revision");
        check.contains("revision conflicts retain drafts", html, "Черновик сохранён");
        check.contains("reopening the same connection keeps the unsaved draft", html,
                "const same=editing&&editing.id===connection.id");
        check.contains("delete asks before removing a connection", html, "window.confirm(");
        check.contains("successful mutations notify the surrounding settings view", html, "warden:connections-changed");
        check.contains("feedback is available to assistive technology", html, "aria-live=\"polite\"");
        check.contains("editing keeps connection identity fixed", html, "idInput.readOnly=true");
        check.contains("only explicit custom local connections can omit auth", html, "Без ключа — локальный API");
        for (String providerCode : Set.of("role_api_rate_limited", "role_api_network_error",
                "role_api_credits_exhausted", "role_api_access_denied")) {
            check.contains("native provider-check failures have a service-specific explanation", html, providerCode);
        }
        check.contains("unknown provider failures do not masquerade as a lost panel connection",
                html, "error?.code?.startsWith('role_api_')");
        check.contains("provider-check fallback does not claim a model was called", html,
                "Сервис не подтвердил подключение. Проверка не завершена; модель не вызывалась.");
        check.contains("a separate message remains for panel transport failures", html,
                "Нет связи с панелью. Изменения не подтверждены.");
        check.that("the default editor has no vault placeholder", !html.contains("{{OS_STORE"));
        check.that("the default editor has no password field", !html.contains("type=\"password\""));
        String vault = ConnectionSettingsUi.html(true);
        check.contains("a working vault can accept a write-only key", vault, "type=\"password\"");
        check.contains("the vault field is not an api-key parameter", vault, "name=\"secret\"");
        check.contains("the key is cleared before the reference is saved", vault, "secretInput.value=''");
        check.contains("the key is posted only to the vault operation", vault, "request('auth-set'");
        check.that("the vault page does not persist browser storage",
                !vault.contains("localStorage") && !vault.contains("sessionStorage"));
        check.that("the vault page does not gain an HTML insertion sink",
                !vault.contains("innerHTML") && !vault.contains("outerHTML"));
    }
}
