package com.github.stefanbirkner.micrometer.jersey;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.ServletModule;
import com.ning.http.client.AsyncHttpClient;
import com.palominolabs.config.ConfigModuleBuilder;
import com.palominolabs.jersey.dispatchwrapper.ResourceMethodWrappedDispatchModule;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.IOException;
import java.util.*;

import static java.util.Collections.singleton;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FullStackTest {

    private static final int PORT = 18080;
    private final AsyncHttpClient httpClient = new AsyncHttpClient();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private Server server;

    @Before
    public void setUp(
    ) throws Exception {
        Injector injector = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                binder().requireExplicitBindings();
                install(new ResourceMethodWrappedDispatchModule());
                install(new ServletModule() {
                    @Override
                    protected void configureServlets() {
                        serve("/*").with(GuiceContainer.class);
                    }
                });
                install(new JerseyServletModule());
                bind(GuiceFilter.class);
                bind(GuiceContainer.class);
                bind(EnabledOnClass.class);
                bind(DisabledOnClass.class);
                bind(EnabledOnClassDisabledOnMethod.class);

                install(new ConfigModuleBuilder().build());
                install(new ResourceMethodMicrometerModule());
                bind(MeterRegistry.class).annotatedWith(JerseyResourceMicrometer.class).toInstance(meterRegistry);
            }
        });

        server = getServer(injector.getInstance(GuiceFilter.class));
        server.start();
    }

    @After
    public void tearDown(
    ) throws Exception {
        server.stop();
    }

    @Test
    public void testFullStack(
    ) throws Exception {
        assertEquals(200,
            httpClient.prepareGet("http://localhost:" + PORT + "/enabledOnClass").execute().get().getStatusCode());

        // these other two resource classes should not have metrics
        assertEquals(200,
            httpClient.prepareGet("http://localhost:" + PORT + "/disabledOnClass").execute().get().getStatusCode());

        assertEquals(200,
            httpClient.prepareGet("http://localhost:" + PORT + "/enabledOnClassDisabledOnMethod").execute().get()
                .getStatusCode());

        Set<String> meterNames = meterRegistry.getMeters()
            .stream()
            .map(Meter::getId)
            .map(Meter.Id::getName)
            .collect(toSet());

        assertEquals(
            singleton(
                "com.github.stefanbirkner.micrometer.jersey.FullStackTest$EnabledOnClass./enabledOnClass"
            ),
            meterNames
        );

        // check values

        Timer timer = meterRegistry.timer(
            "com.github.stefanbirkner.micrometer.jersey.FullStackTest$EnabledOnClass./enabledOnClass",
            "method", "GET",
            "status", "200");
        assertEquals(1, timer.count());
        assertTrue(timer.mean(MILLISECONDS) > 0D);
    }

    private Server getServer(
        GuiceFilter filter
    ) {
        Server server = new Server(PORT);
        ServletContextHandler servletHandler = new ServletContextHandler();

        servletHandler.addServlet(new ServletHolder(new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException,
                IOException {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.setContentType("text/plain");
                resp.setContentType("UTF-8");
                resp.getWriter().append("404");
            }
        }), "/*");

        // add guice servlet filter
        servletHandler.addFilter(new FilterHolder(filter), "/*", EnumSet.allOf(DispatcherType.class));

        server.setHandler(servletHandler);

        return server;
    }

    @Path("enabledOnClass")
    @ResourceMetrics
    public static class EnabledOnClass {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Path("disabledOnClass")
    @ResourceMetrics(enabled = false)
    public static class DisabledOnClass {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Path("enabledOnClassDisabledOnMethod")
    @ResourceMetrics
    public static class EnabledOnClassDisabledOnMethod {
        @GET
        @ResourceMetrics(enabled = false)
        public String get() {
            return "ok";
        }
    }
}
