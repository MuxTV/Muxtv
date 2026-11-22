package app.muxtv.localcontrol.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * Public-to-the-selected-LAN ONLY bootstrap. No application data, token or credentials embedded.
 * The QR secret is a URL fragment and will never be included in the HTTP GET request.
 */
internal fun Application.installPairingBootstrapPage(
    activeEndpoint: () -> LanEndpoint?,
    remotePeer: (ApplicationCall) -> String = { it.request.local.remoteHost },
    limiter: LocalRequestLimiter,
) {
    routing {
        get("/pair") {
            if (!call.isSafeBootstrap(activeEndpoint, remotePeer, limiter)) return@get
            call.respondText(PAIRING_HTML, ContentType.Text.Html)
        }
        get("/pair.js") {
            if (!call.isSafeBootstrap(activeEndpoint, remotePeer, limiter)) return@get
            call.respondText(PAIRING_JAVASCRIPT, ContentType.parse("text/javascript"))
        }
    }
}

private suspend fun ApplicationCall.isSafeBootstrap(
    activeEndpoint: () -> LanEndpoint?,
    remotePeer: (ApplicationCall) -> String,
    limiter: LocalRequestLimiter,
): Boolean {
    response.headers.append(HttpHeaders.CacheControl, "no-store")
    response.headers.append("Pragma", "no-cache")
    response.headers.append("Referrer-Policy", "no-referrer")
    response.headers.append("X-Content-Type-Options", "nosniff")
    response.headers.append("X-Frame-Options", "DENY")
    response.headers.append(
        "Content-Security-Policy",
        "default-src 'none'; script-src 'self'; connect-src 'self'; " +
            "style-src 'none'; img-src 'none'; base-uri 'none'; form-action 'none'; " +
            "frame-ancestors 'none'",
    )
    val endpoint = activeEndpoint()
    if (endpoint == null) {
        respond(HttpStatusCode.ServiceUnavailable)
        return false
    }
    // Browser GET navigation cannot carry Origin or custom request markers.
    // Only these two unprivileged static routes have that exception.
    if (request.headers.getAll(HttpHeaders.Host) != listOf(endpoint.authority) ||
        !LanAddressPolicy.isPrivateLiteral(remotePeer(this))
    ) {
        respond(HttpStatusCode.Forbidden)
        return false
    }
    if (!limiter.permit(remotePeer(this))) {
        respond(HttpStatusCode.TooManyRequests)
        return false
    }
    return true
}

private const val PAIRING_HTML = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>MuxTV — сопряжение с телевизором</title>
</head>
<body>
<main>
<h1>MuxTV</h1>
<p>Сопряжение с телевизором. Соединение по HTTP в локальной сети не зашифровано.
Не вводите пароли IPTV-провайдера на этой странице.</p>
<label for="clientLabel">Название устройства</label>
<input id="clientLabel" type="text" maxlength="48" autocomplete="off" value="Телефон">
<button id="pairButton" type="button">Запросить сопряжение</button>
<p id="status" role="status">Подтвердите сопряжение на телевизоре.</p>
</main>
<script src="/pair.js" defer></script>
</body>
</html>
"""

/**
 * No remote scripts, persistence, credentials in URL query, analytics or console output.
 * Token exists only in this ephemeral JS execution scope until redemption.
 */
private const val PAIRING_JAVASCRIPT = """
(() => {
  'use strict';
  const token = window.location.hash.startsWith('#')
    ? window.location.hash.slice(1)
    : '';
  window.history.replaceState(null, '', '/pair');
  const status = document.getElementById('status');
  const button = document.getElementById('pairButton');
  const label = document.getElementById('clientLabel');
  const header = {'Content-Type':'application/json', 'X-MuxTV-Client':'1'};
  const request = async (path, data) => {
    const response = await fetch(path, {
      method: 'POST', mode: 'same-origin', cache: 'no-store',
      credentials: 'omit', headers: header, body: JSON.stringify(data)
    });
    if (response.status === 429) throw new Error('rate');
    if (response.status === 403) return null;
    if (!response.ok) throw new Error('invalid');
    return await response.json();
  };
  button.addEventListener('click', async () => {
    button.disabled = true;
    if (!/^[0-9a-f]{64}$/.test(token)) {
      status.textContent = 'QR-код недействителен. Откройте новый на телевизоре.';
      return;
    }
    try {
      const claim = await request('/v1/pair/claim', {
        proof:token, clientLabel:label.value.trim()
      });
      if (!claim) throw new Error('expired');
      status.textContent = 'Подтвердите устройство на экране ТВ.';
      for (let attempt = 0; attempt < 46; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 2500));
        const grant = await request('/v1/pair/redeem', {
          claimId:claim.claimId, proof:token
        });
        if (grant) {
          status.textContent = 'Сопряжение подтверждено. Управление источниками будет добавлено отдельно.';
          return;
        }
      }
      status.textContent = 'Время сопряжения истекло. Откройте новый QR-код.';
    } catch (_) {
      status.textContent = 'Не удалось выполнить сопряжение. Повторите на телевизоре.';
    }
  });
})();
"""
