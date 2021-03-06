/* (c) 2015 Boundless, http://boundlessgeo.com
 * This code is licensed under the GPL 2.0 license.
 */
package com.boundlessgeo.geoserver.api.controllers;

import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.geoserver.GeoServerConfigurationLock;
import org.geoserver.platform.ExtensionPriority;
import org.geoserver.platform.GeoServerExtensions;
import org.geoserver.rest.AbstractGeoServerController;
import org.geoserver.rest.DispatcherCallback;
import org.geoserver.rest.DispatcherCallbackAdapter;
import org.geoserver.rest.catalog.AbstractCatalogController;
import org.geotools.util.logging.Logging;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

/**
 * Inspired by the RestConfigurationLockCallback.
 */
public class ConfigurationLockInterceptor extends DispatcherCallbackAdapter implements ExtensionPriority {

    // visible for testing
    final ThreadLocal<GeoServerConfigurationLock.LockType> THREAD_LOCK = new ThreadLocal<>();

    private GeoServerConfigurationLock lock;

    private static final Logger logger = Logging.getLogger(ConfigurationLockInterceptor.class);

    @Override
    public void finished(HttpServletRequest request, HttpServletResponse response) {
        // @todo - async post operations will get unlocked before critical section!
        //         this applies to importing as of writing. since the lock is
        //         reentrant, nested locks don't hurt
        GeoServerConfigurationLock.LockType lockType = THREAD_LOCK.get();
        if (lockType != null) {
            THREAD_LOCK.remove();
            lock.unlock();
        }
    }

    GeoServerConfigurationLock lock() {
        if (lock == null) {
            lock = GeoServerExtensions.bean(GeoServerConfigurationLock.class);
        }
        return lock;
    }

    @Override
    public void dispatched(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Object controller = DispatcherCallback.getControllerBean(handler);
        if (controller instanceof ApiController) {
            if (THREAD_LOCK.get() != null) {
                throw new RuntimeException("existing lock found on " + request.getPathInfo());
            }
            String method = request.getMethod().toLowerCase();
            GeoServerConfigurationLock.LockType lockType;
            switch (method) {
                case "get":
                case "head":
                case "options":
                    lockType = GeoServerConfigurationLock.LockType.READ;
                    break;
                default:
                    // defaulting to a write lock is probably the safest bet here
                    // unless a new, popular read method is invented sometime...
                    lockType = GeoServerConfigurationLock.LockType.WRITE;
            }
            if (logger.isLoggable(Level.FINE)) {
                logger.fine("DEBUG LOCK: " + lockType);
            }
            THREAD_LOCK.set(lockType);
            lock().lock(lockType);
        }
    }

    @Override
    public int getPriority() {
        //Make sure this gets called after RestConfigurationLockCallback
        return LOWEST+1;
    }
}
