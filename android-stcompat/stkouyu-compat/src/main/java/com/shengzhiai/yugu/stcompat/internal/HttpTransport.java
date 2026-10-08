// SPDX-License-Identifier: Apache-2.0
package com.shengzhiai.yugu.stcompat.internal;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** HTTP abstraction so the retry and error logic can be tested without sockets. */
public interface HttpTransport {

    /** One HTTP attempt. */
    final class Request {
        public final String method;
        public final String url;
        public final Map<String, String> headers;
        public final Multipart body;
        public final int connectTimeoutMs;
        public final int readTimeoutMs;

        public Request(String method, String url, Map<String, String> headers, Multipart body,
                       int connectTimeoutMs, int readTimeoutMs) {
            this.method = method;
            this.url = url;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<String, String>(headers));
            this.body = body;
            this.connectTimeoutMs = connectTimeoutMs;
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** Response of one attempt. Header names are lower case. */
    final class Response {
        public final int status;
        public final Map<String, String> headers;
        public final String body;

        public Response(int status, Map<String, String> headers, String body) {
            this.status = status;
            Map<String, String> h = new LinkedHashMap<String, String>();
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getKey() != null) {
                        h.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                    }
                }
            }
            this.headers = Collections.unmodifiableMap(h);
            this.body = body == null ? "" : body;
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Executes one attempt. Implementations register an abort handle with {@code token} and throw
     * {@link IOException} on transport failure (SocketTimeoutException for timeouts).
     */
    Response execute(Request request, CancelToken token) throws IOException;
}
