/*
 * Copyright (c) 2026, skia-fx. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  The skia-fx project
 * designates this particular file as subject to the "Classpath" exception
 * as provided in the LICENSE file that accompanied this code.
 */
package javafx.scene.web;

import java.util.List;
import java.util.Objects;

import javafx.util.Subscription;

/**
 * Package-private {@link Network} implementation. A thin facade over
 * {@link WebEngine}'s package-private interceptor registry — only the
 * {@link Network} interface is public; this class never appears in any signature.
 */
final class NetworkImpl implements Network {

    private final WebEngine engine;

    NetworkImpl(WebEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @Override
    public Subscription add(NetworkFilter filter, NetworkInterceptor interceptor) {
        return engine.addInterceptor(filter, interceptor);
    }

    @Override
    public List<NetworkInterceptor> interceptors() {
        return engine.interceptorSnapshot();
    }

    @Override
    public void clear() {
        engine.clearInterceptors();
    }
}
