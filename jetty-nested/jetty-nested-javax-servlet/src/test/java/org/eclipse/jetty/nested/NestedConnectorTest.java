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

package org.eclipse.jetty.nested;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.StringRequestContent;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class NestedConnectorTest
{
    private static Server _server;
    private static ServerConnector _connector;
    private static HttpClient _httpClient;

    @BeforeAll
    public static void before() throws Exception
    {
        _server = new Server();
        _connector = new ServerConnector(_server);
        _server.addConnector(_connector);

        // Create a servlet which nests a Jetty server.
        JettyNestedJavaxServlet jettyNestedServlet = new JettyNestedJavaxServlet();
        ServletContextHandler nestedHandler = new ServletContextHandler();
        nestedHandler.setContextPath("/nested");
        nestedHandler.addServlet(TestServlet.class, "/");
        jettyNestedServlet.getServer().setHandler(nestedHandler);

        // Add the Nested Servlet to the server.
        ServletContextHandler contextHandler = new ServletContextHandler();
        contextHandler.setContextPath("/nested");
        contextHandler.addServlet(new ServletHolder(jettyNestedServlet), "/");
        _server.setHandler(contextHandler);

        // Start server and client.
        _server.start();
        _httpClient = new HttpClient();
        _httpClient.start();
    }

    @AfterAll
    public static void after() throws Exception
    {
        _httpClient.stop();
        _server.stop();
    }

    public static class TestServlet extends HttpServlet
    {
        @Override
        protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
        {
            ServletInputStream inputStream = req.getInputStream();
            String requestContent = IO.toString(inputStream);
            PrintWriter writer = resp.getWriter();
            writer.println("method: " + req.getMethod());
            writer.println("requestContent: " + requestContent);
            writer.println("requestURI: " + req.getRequestURI());
            writer.println("requestURL: " + req.getRequestURL());
            writer.println("params: " + new JSON().toJSON(req.getParameterMap()));
            writer.println("contextPath: " + req.getContextPath());
            writer.println("servletPath: " + req.getServletPath());
            writer.println("pathInfo: " + req.getPathInfo());
            writer.println("query: " + req.getQueryString());
        }
    }

    @Test
    public void testGet() throws Exception
    {
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort());
        ContentResponse response = _httpClient.GET(uri);
        System.err.println(response.getHeaders());
        System.err.println(response.getContentAsString());
    }

    @Test
    public void testPost() throws Exception
    {
        URI uri = URI.create("http://localhost:" + _connector.getLocalPort() + "/nested/test/servlet/tester?param1=value1&param2=value2");
        ContentResponse response = _httpClient.POST(uri).body(new StringRequestContent("this is the request content")).send();
        System.err.println(response.getHeaders());
        System.err.println(response.getContentAsString());
    }
}
