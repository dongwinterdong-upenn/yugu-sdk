package com.shengzhiai.yugu.testing;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Starts the Node mock once and stops it when the whole test plan has finished. */
public final class MockServerExtension implements BeforeAllCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
        context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL)
                .getOrComputeIfAbsent("yugu-mock-server", k -> new Resource(MockServer.get()), Resource.class);
    }

    static final class Resource implements ExtensionContext.Store.CloseableResource {
        private final MockServer server;

        Resource(MockServer server) {
            this.server = server;
        }

        @Override
        public void close() {
            server.stop();
        }
    }
}
