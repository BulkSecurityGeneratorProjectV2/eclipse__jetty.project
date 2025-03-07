//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.session;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * AbstractClusteredInvalidationSessionTest
 *
 * Goal of the test is to be sure that invalidating a session on one node
 * result in the session being unavailable in the other node also. This
 * simulates an environment without a sticky load balancer. In this case,
 * you must use session eviction, to try to ensure that as the session
 * bounces around it gets a fresh load of data from the SessionDataStore.
 */
public abstract class AbstractClusteredInvalidationSessionTest extends AbstractTestBase
{
    @Test
    public void testInvalidation() throws Exception
    {
        String contextPath = "/";
        String servletMapping = "/server";
        int maxInactiveInterval = 30;
        int scavengeInterval = 1;
        DefaultSessionCacheFactory cacheFactory1 = new DefaultSessionCacheFactory();
        cacheFactory1.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
        SessionDataStoreFactory storeFactory1 = createSessionDataStoreFactory();
        ((AbstractSessionDataStoreFactory)storeFactory1).setGracePeriodSec(scavengeInterval);

        TestServer server1 = new TestServer(0, maxInactiveInterval, scavengeInterval, cacheFactory1, storeFactory1);
        ServletContextHandler context = server1.addContext(contextPath);
        context.addServlet(TestServlet.class, servletMapping);
        TestHttpChannelCompleteListener scopeListener = new TestHttpChannelCompleteListener();
        server1.getServerConnector().addBean(scopeListener);

        try
        {
            server1.start();
            int port1 = server1.getPort();

            DefaultSessionCacheFactory cacheFactory2 = new DefaultSessionCacheFactory();
            cacheFactory2.setEvictionPolicy(SessionCache.EVICT_ON_SESSION_EXIT);
            SessionDataStoreFactory storeFactory2 = createSessionDataStoreFactory();
            ((AbstractSessionDataStoreFactory)storeFactory2).setGracePeriodSec(scavengeInterval);
            TestServer server2 = new TestServer(0, maxInactiveInterval, scavengeInterval, cacheFactory2, storeFactory2);
            server2.addContext(contextPath).addServlet(TestServlet.class, servletMapping);

            try
            {
                server2.start();
                int port2 = server2.getPort();
                HttpClient client = new HttpClient();
                QueuedThreadPool executor = new QueuedThreadPool();
                client.setExecutor(executor);
                client.start();

                try
                {
                    String[] urls = new String[2];
                    urls[0] = "http://localhost:" + port1 + contextPath + servletMapping.substring(1);
                    urls[1] = "http://localhost:" + port2 + contextPath + servletMapping.substring(1);

                    // Create the session on node1
                    CountDownLatch latch = new CountDownLatch(1);
                    scopeListener.setExitSynchronizer(latch);
                    ContentResponse response1 = client.GET(urls[0] + "?action=init");

                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());
                    String sessionCookie = response1.getHeaders().get("Set-Cookie");
                    assertTrue(sessionCookie != null);

                    //ensure request is fully finished processing
                    latch.await(5, TimeUnit.SECONDS);

                    // Be sure the session is also present in node2
                    latch = new CountDownLatch(1);
                    scopeListener.setExitSynchronizer(latch);
                    Request request2 = client.newRequest(urls[1] + "?action=increment");
                    ContentResponse response2 = request2.send();
                    assertEquals(HttpServletResponse.SC_OK, response2.getStatus());

                    //ensure request is fully finished processing
                    latch.await(5, TimeUnit.SECONDS);

                    // Invalidate on node1
                    latch = new CountDownLatch(1);
                    scopeListener.setExitSynchronizer(latch);
                    Request request1 = client.newRequest(urls[0] + "?action=invalidate");
                    response1 = request1.send();
                    assertEquals(HttpServletResponse.SC_OK, response1.getStatus());

                    //ensure request is fully finished processing
                    latch.await(5, TimeUnit.SECONDS);

                    // Be sure on node2 we don't see the session anymore
                    request2 = client.newRequest(urls[1] + "?action=test");
                    response2 = request2.send();
                    assertEquals(HttpServletResponse.SC_OK, response2.getStatus());
                }
                finally
                {
                    client.stop();
                }
            }
            finally
            {
                server2.stop();
            }
        }
        finally
        {
            server1.stop();
        }
    }

    public static class TestServlet extends HttpServlet
    {
        private static final long serialVersionUID = 1L;

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            String action = request.getParameter("action");
            if ("init".equals(action))
            {
                HttpSession session = request.getSession(true);
                session.setAttribute("value", 0);
            }
            else if ("increment".equals(action))
            {
                HttpSession session = request.getSession(false);
                int value = (Integer)session.getAttribute("value");
                session.setAttribute("value", value + 1);
            }
            else if ("invalidate".equals(action))
            {
                HttpSession session = request.getSession(false);
                session.invalidate();

                try
                {
                    session.invalidate();
                    fail("Session should be invalid");
                }
                catch (IllegalStateException e)
                {
                    //expected
                }
            }
            else if ("test".equals(action))
            {
                HttpSession session = request.getSession(false);
                assertEquals(null, session);
            }
        }
    }
}
