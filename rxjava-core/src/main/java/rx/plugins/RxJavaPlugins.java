/**
 * Copyright 2013 Netflix, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package rx.plugins;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Test;

/**
 * Registry for plugin implementations that allows global override and handles the retrieval of correct implementation based on order of precedence:
 * <ol>
 * <li>plugin registered globally via <code>register</code> methods in this class</li>
 * <li>plugin registered and retrieved using {@link java.lang.System#getProperty(String)} (see get methods for property names)</li>
 * <li>default implementation</li>
 * </ol>
 * See the RxJava GitHub Wiki for more information: <a href="https://github.com/Netflix/RxJava/wiki/Plugins">https://github.com/Netflix/RxJava/wiki/Plugins</a>.
 */
public class RxJavaPlugins {
    private final static RxJavaPlugins INSTANCE = new RxJavaPlugins();

    private final AtomicReference<RxJavaErrorHandler> errorHandler = new AtomicReference<RxJavaErrorHandler>();

    private RxJavaPlugins() {

    }

    public static RxJavaPlugins getInstance() {
        return INSTANCE;
    }

    /**
     * Retrieve instance of {@link RxJavaErrorHandler} to use based on order of precedence as defined in {@link RxJavaPlugins} class header.
     * <p>
     * Override default by using {@link #registerErrorHandler(RxJavaErrorHandler)} or setting property: <code>rxjava.plugin.RxJavaErrorHandler.implementation</code> with the full classname to
     * load.
     * 
     * @return {@link RxJavaErrorHandler} implementation to use
     */
    public RxJavaErrorHandler getErrorHandler() {
        if (errorHandler.get() == null) {
            // check for an implementation from System.getProperty first
            Object impl = getPluginImplementationViaProperty(RxJavaErrorHandler.class);
            if (impl == null) {
                // nothing set via properties so initialize with default 
                errorHandler.compareAndSet(null, RxJavaErrorHandlerDefault.getInstance());
                // we don't return from here but call get() again in case of thread-race so the winner will always get returned
            } else {
                // we received an implementation from the system property so use it
                errorHandler.compareAndSet(null, (RxJavaErrorHandler) impl);
            }
        }
        return errorHandler.get();
    }

    /**
     * Register a {@link HystrixEventNotifier} implementation as a global override of any injected or default implementations.
     * 
     * @param impl
     *            {@link HystrixEventNotifier} implementation
     * @throws IllegalStateException
     *             if called more than once or after the default was initialized (if usage occurs before trying to register)
     */
    public void registerErrorHandler(RxJavaErrorHandler impl) {
        if (!errorHandler.compareAndSet(null, impl)) {
            throw new IllegalStateException("Another strategy was already registered.");
        }
    }

    private static Object getPluginImplementationViaProperty(Class<?> pluginClass) {
        String classSimpleName = pluginClass.getSimpleName();
        /*
         * Check system properties for plugin class.
         * <p>
         * This will only happen during system startup thus it's okay to use the synchronized System.getProperties
         * as it will never get called in normal operations.
         */
        String implementingClass = System.getProperty("rxjava.plugin." + classSimpleName + ".implementation");
        if (implementingClass != null) {
            try {
                Class<?> cls = Class.forName(implementingClass);
                // narrow the scope (cast) to the type we're expecting
                cls = cls.asSubclass(pluginClass);
                return cls.newInstance();
            } catch (ClassCastException e) {
                throw new RuntimeException(classSimpleName + " implementation is not an instance of " + classSimpleName + ": " + implementingClass);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(classSimpleName + " implementation class not found: " + implementingClass, e);
            } catch (InstantiationException e) {
                throw new RuntimeException(classSimpleName + " implementation not able to be instantiated: " + implementingClass, e);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(classSimpleName + " implementation not able to be accessed: " + implementingClass, e);
            }
        } else {
            return null;
        }
    }

    public static class UnitTest {

        @After
        public void reset() {
            // use private access to reset so we can test different initializations via the public static flow
            RxJavaPlugins.getInstance().errorHandler.set(null);
        }

        @Test
        public void testEventNotifierDefaultImpl() {
            RxJavaErrorHandler impl = RxJavaPlugins.getInstance().getErrorHandler();
            assertTrue(impl instanceof RxJavaErrorHandlerDefault);
        }

        @Test
        public void testEventNotifierViaRegisterMethod() {
            RxJavaPlugins.getInstance().registerErrorHandler(new RxJavaErrorHandlerTestImpl());
            RxJavaErrorHandler impl = RxJavaPlugins.getInstance().getErrorHandler();
            assertTrue(impl instanceof RxJavaErrorHandlerTestImpl);
        }

        @Test
        public void testEventNotifierViaProperty() {
            try {
                String fullClass = getFullClassNameForTestClass(RxJavaErrorHandlerTestImpl.class);
                System.setProperty("rxjava.plugin.RxJavaErrorHandler.implementation", fullClass);
                RxJavaErrorHandler impl = RxJavaPlugins.getInstance().getErrorHandler();
                assertTrue(impl instanceof RxJavaErrorHandlerTestImpl);
            } finally {
                System.clearProperty("rxjava.plugin.RxJavaErrorHandler.implementation");
            }
        }

        // inside UnitTest so it is stripped from Javadocs
        public static class RxJavaErrorHandlerTestImpl extends RxJavaErrorHandler {
            // just use defaults
        }

        private static String getFullClassNameForTestClass(Class<?> cls) {
            return RxJavaPlugins.class.getPackage().getName() + "." + RxJavaPlugins.class.getSimpleName() + "$UnitTest$" + cls.getSimpleName();
        }
    }

}
