package aussie.core.port.out;

import io.vertx.core.http.HttpClient;
import io.vertx.mutiny.ext.web.client.WebClient;

/** Application-owned outbound clients with shared TLS policy and bounded pools. */
public interface OutboundHttpClients {

    WebClient webClient();

    WebClient jwksWebClient();

    HttpClient httpClient();
}
