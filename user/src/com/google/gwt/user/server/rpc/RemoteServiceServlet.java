/*
 * Copyright 2008 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.user.server.rpc;

import static com.google.gwt.user.client.rpc.RpcRequestBuilder.MODULE_BASE_HEADER;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.google.gwt.user.client.rpc.IncompatibleRemoteServiceException;
import com.google.gwt.user.client.rpc.RpcTokenException;
import com.google.gwt.user.client.rpc.SerializationException;

/**
 * The servlet base class for your RPC service implementations that
 * automatically deserializes incoming requests from the client and serializes
 * outgoing responses for client/server RPCs.
 */
public class RemoteServiceServlet extends AbstractRemoteServiceServlet
    implements SerializationPolicyProvider {

  /**
   * Loads a serialization policy stored as a servlet resource in the same
   * ServletContext as this servlet. Returns null if not found.
   * (Used by HybridServiceServlet.)
   */
  static SerializationPolicy loadSerializationPolicy(HttpServlet servlet,
      HttpServletRequest request, String moduleBaseURL, String strongName) {
    // The request can tell you the path of the web app relative to the
    // container root.
    String contextPath = request.getContextPath();

    String modulePath = null;
    if (moduleBaseURL != null) {
      try {
        modulePath = new URL(moduleBaseURL).getPath();
      } catch (MalformedURLException ex) {
        // log the information, we will default
        servlet.log("Malformed moduleBaseURL: " + moduleBaseURL, ex);
      }
    }

    SerializationPolicy serializationPolicy = null;

    /*
     * Check that the module path must be in the same web app as the servlet
     * itself. If you need to implement a scheme different than this, override
     * this method.
     */
    if (modulePath == null || !modulePath.startsWith(contextPath)) {
      String message = "ERROR: The module path requested, "
          + modulePath
          + ", is not in the same web application as this servlet, "
          + contextPath
          + ".  Your module may not be properly configured or your client and server code maybe out of date.";
      servlet.log(message);
    } else {
      // Strip off the context path from the module base URL. It should be a
      // strict prefix.
      String contextRelativePath = modulePath.substring(contextPath.length());

      String serializationPolicyFilePath = SerializationPolicyLoader.getSerializationPolicyFileName(contextRelativePath
          + strongName);

      // Open the RPC resource file and read its contents.
      InputStream is = servlet.getServletContext().getResourceAsStream(
          serializationPolicyFilePath);
      try {
        if (is != null) {
          try {
            serializationPolicy = SerializationPolicyLoader.loadFromStream(is,
                null);
          } catch (ParseException e) {
            servlet.log("ERROR: Failed to parse the policy file '"
                + serializationPolicyFilePath + "'", e);
          } catch (IOException e) {
            servlet.log("ERROR: Could not read the policy file '"
                + serializationPolicyFilePath + "'", e);
          }
        } else {
          String message = "ERROR: The serialization policy file '"
              + serializationPolicyFilePath
              + "' was not found; did you forget to include it in this deployment?";
          servlet.log(message);
        }
      } finally {
        if (is != null) {
          try {
            is.close();
          } catch (IOException e) {
            // Ignore this error
          }
        }
      }
    }

    return serializationPolicy;
  }

  private static final SerializationPolicyClient CODE_SERVER_CLIENT =
      new SerializationPolicyClient(5000, 5000);

  /**
   * A cache of moduleBaseURL and serialization policy strong name to
   * {@link SerializationPolicy}.
   */
  private final Map<String, SerializationPolicy> serializationPolicyCache = new HashMap<String, SerializationPolicy>();

  /**
   * The implementation of the service.
   */
  private final Object delegate;

  /**
   * The HTTP port of a Super Dev Mode code server running on localhost where this servlet will
   * download serialization policies. (If set to zero, this feature is disabled and no download
   * will be attempted.)
   */
  private int codeServerPort = 0;

  /**
   * The default constructor used by service implementations that
   * extend this class.  The servlet will delegate AJAX requests to
   * the appropriate method in the subclass.
   */
  public RemoteServiceServlet() {
    this.delegate = this;
  }

  /**
   * The wrapping constructor used by service implementations that are
   * separate from this class.  The servlet will delegate AJAX
   * requests to the appropriate method in the given object.
   */
  public RemoteServiceServlet(Object delegate) {
    this.delegate = delegate;
  }

  /**
   * Overridden to load the gwt.codeserver.port system property.
   */
  @Override
  public void init(ServletConfig config) throws ServletException {
    super.init(config);
    codeServerPort = getCodeServerPort();
  }

  /**
   * Returns the value of the gwt.codeserver.port system property, or zero if not defined.
   *
   * @throws ServletException if the system property has an invalid value.
   */
  private int getCodeServerPort() throws ServletException {
    String value = System.getProperty("gwt.codeserver.port");
    if (value == null) {
      return 0;
    }

    try {
      int port = Integer.parseInt(value);
      if (port >= 0 && port < 65536) {
        return port;
      }
      // invalid because negative; fall through

    } catch (NumberFormatException e) {
      // fall through
    }

    // Fail loudly so that that a configuration error will be noticed.
    throw new ServletException("Invalid value of gwt.codeserver.port system property;"
        + " expected an integer in the range [1-65535] but got: " + value);
  }

  /**
   * Extract the module's base path from the current request.
   *
   * @return the module's base path, modulo protocol and host, as reported by
   *         {@link com.google.gwt.core.client.GWT#getModuleBaseURL()} or
   *         <code>null</code> if the request did not contain the
   *         {@value com.google.gwt.user.client.rpc.RpcRequestBuilder#MODULE_BASE_HEADER} header
   */
  protected String getRequestModuleBasePath() {
    try {
      String header = getThreadLocalRequest().getHeader(MODULE_BASE_HEADER);
      if (header == null) {
        return null;
      }
      String path = new URL(header).getPath();
      String contextPath = getThreadLocalRequest().getContextPath();
      if (!path.startsWith(contextPath)) {
        return null;
      }
      return path.substring(contextPath.length());
    } catch (MalformedURLException e) {
      return null;
    }
  }

  @Override
  public final SerializationPolicy getSerializationPolicy(String moduleBaseURL,
      String strongName) {

    SerializationPolicy serializationPolicy = getCachedSerializationPolicy(
        moduleBaseURL, strongName);
    if (serializationPolicy != null) {
      return serializationPolicy;
    }

    serializationPolicy = doGetSerializationPolicy(getThreadLocalRequest(),
        moduleBaseURL, strongName);

    // Try SuperDevMode, if configured.
    if (serializationPolicy == null) {
      String url = getCodeServerPolicyUrl(strongName);
      if (url != null) {
        serializationPolicy = loadPolicyFromCodeServer(url);
      }
    }

    if (serializationPolicy == null) {
      // Failed to get the requested serialization policy; use the default
      log(
          "WARNING: Failed to get the SerializationPolicy '"
              + strongName
              + "' for module '"
              + moduleBaseURL
              + "'; a legacy, 1.3.3 compatible, serialization policy will be used.  You may experience SerializationExceptions as a result.");
      serializationPolicy = RPC.getDefaultSerializationPolicy();
    }

    // This could cache null or an actual instance. Either way we will not
    // attempt to lookup the policy again.
    putCachedSerializationPolicy(moduleBaseURL, strongName, serializationPolicy);

    return serializationPolicy;
  }

  /**
   * Process a call originating from the given request. This method calls
   * {@link RemoteServiceServlet#checkPermutationStrongName()} to prevent
   * possible XSRF attacks and then decodes the <code>payload</code> using
   * {@link RPC#decodeRequest(String, Class, SerializationPolicyProvider)}
   * to do the actual work.
   * Once the request is decoded {@link RemoteServiceServlet#processCall(RPCRequest, Writer)}
   * will be called.
   * <p>
   * Subclasses may optionally override this method to handle the payload in any
   * way they desire (by routing the request to a framework component, for
   * instance). The {@link HttpServletRequest} and {@link HttpServletResponse}
   * can be accessed via the {@link #getThreadLocalRequest()} and
   * {@link #getThreadLocalResponse()} methods.
   * </p>
   * This is public so that it can be unit tested easily without HTTP.
   *
   * @param payload the UTF-8 request payload
   * @param writer the writer to write the output to
   * @return a string which encodes either the method's return, a checked
   *         exception thrown by the method, or an
   *         {@link IncompatibleRemoteServiceException}
   * @throws SerializationException if we cannot serialize the response
   * @throws UnexpectedException if the invocation throws a checked exception
   *           that is not declared in the service method's signature
   * @throws RuntimeException if the service method throws an unchecked
   *           exception (the exception will be the one thrown by the service)
   */
  public void processCall(String payload, Writer writer) throws SerializationException {
    // First, check for possible XSRF situation
    checkPermutationStrongName();

    RPCRequest rpcRequest;
    try {
      rpcRequest = RPC.decodeRequest(payload, delegate.getClass(), this);
      processCall(rpcRequest, writer);
    } catch (IncompatibleRemoteServiceException ex) {
      log(
          "An IncompatibleRemoteServiceException was thrown while processing this call.",
          ex);
      RPC.encodeResponseForFailedRequest(null, ex, writer);
    }
  }

  /**
   * Process an already decoded RPC request. Uses the
   * {@link RPC#invokeAndEncodeResponse(Object, java.lang.reflect.Method, Object[], Writer)}
   * method to do the actual work.
   * <p>
   * Subclasses may optionally override this method to handle the decoded rpc
   * request in any way they desire (by routing the request to a framework
   * component, for instance).
   * The {@link HttpServletRequest} and {@link HttpServletResponse}
   * can be accessed via the {@link #getThreadLocalRequest()} and
   * {@link #getThreadLocalResponse()} methods.
   * </p>
   * This is public so that it can be unit tested easily without HTTP.
   *
   * @param rpcRequest the already decoded RPC request
   * @param writer output to be written to this writer
   * @return a string which encodes either the method's return, a checked
   *         exception thrown by the method, or an
   *         {@link IncompatibleRemoteServiceException}
   * @throws SerializationException if we cannot serialize the response
   * @throws UnexpectedException if the invocation throws a checked exception
   *           that is not declared in the service method's signature
   * @throws RuntimeException if the service method throws an unchecked
   *           exception (the exception will be the one thrown by the service)
   */
  public void processCall(RPCRequest rpcRequest, Writer writer) throws SerializationException {
    try {
      onAfterRequestDeserialized(rpcRequest);
      RPC.invokeAndEncodeResponse(delegate, rpcRequest.getMethod(),
          rpcRequest.getParameters(), rpcRequest.getSerializationPolicy(),
          rpcRequest.getFlags(), writer);
    } catch (IncompatibleRemoteServiceException ex) {
      log(
          "An IncompatibleRemoteServiceException was thrown while processing this call.",
          ex);
      RPC.encodeResponseForFailedRequest(rpcRequest, ex, writer);
    } catch (RpcTokenException tokenException) {
      log("An RpcTokenException was thrown while processing this call.",
          tokenException);
      RPC.encodeResponseForFailedRequest(rpcRequest, tokenException, writer);
    }
  }

  /**
   * Standard HttpServlet method: handle the POST.
   * 
   * This doPost method swallows ALL exceptions, logs them in the
   * ServletContext, and returns a GENERIC_FAILURE_MSG response with status code
   * 500.
   * 
   * @throws ServletException
   * @throws SerializationException
   */
  @Override
  public final void processPost(HttpServletRequest request,
      HttpServletResponse response) throws IOException, ServletException,
      SerializationException {
    // Read the request fully.
    //
    String requestPayload = readContent(request);

    // Let subclasses see the serialized request.
    //
    onBeforeRequestDeserialized(requestPayload);
    
    // Create Writer on response, considering GZIP compression if requested
    //
    final Writer responseWriter = createWriterForResponse(request, response);
    final boolean onAfterResponseSerializedOverridden = isOnAfterResponseSerializedOverridden();
    final Writer writer;
    final StringWriter writerForResponseCopy;
    if (onAfterResponseSerializedOverridden) {
      writerForResponseCopy = new StringWriter();
      writer = new TeeWriter(responseWriter, writerForResponseCopy);
    } else {
      writerForResponseCopy = null;
      writer = responseWriter;
    }

    // Invoke the core dispatching logic, which returns the serialized
    // result.
    //
    processCall(requestPayload, writer);

    if (onAfterResponseSerializedOverridden) {
      // Let subclasses see the serialized response.
      //
      onAfterResponseSerialized(writerForResponseCopy.toString());
    }

    // Finish the response.
    //
    writer.flush();
    writer.close();
  }

  private boolean isOnAfterResponseSerializedOverridden() {
    try {
      Class<?> c = getClass();
      boolean foundOverride = false;
      while (!foundOverride && c != RemoteServiceServlet.class) {
        try {
          final Method m = c.getDeclaredMethod("onAfterResponseSerialized", new Class<?>[] { String.class });
          foundOverride = m != null;
          // no need to check visibility because an override must be at least protected or public
        } catch (NoSuchMethodException e) {
          // ignore; the method wasn't found in the class referenced by c
        }
        c = c.getSuperclass();
      }
      return foundOverride;
    } catch (SecurityException e) {
      throw new RuntimeException("Couldn't find onAfterResponseSerialized method", e);
    }
  }

  /**
   * This method is called by {@link #processCall(String, Writer)} and will throw a
   * SecurityException if {@link #getPermutationStrongName()} returns
   * <code>null</code>. This method can be overridden to be a no-op if there are
   * clients that are not expected to provide the
   * {@value com.google.gwt.user.client.rpc.RpcRequestBuilder#STRONG_NAME_HEADER}
   * header.
   * 
   * @throws SecurityException if {@link #getPermutationStrongName()} returns
   *           <code>null</code>
   */
  protected void checkPermutationStrongName() throws SecurityException {
    if (getPermutationStrongName() == null) {
      throw new SecurityException(
          "Blocked request without GWT permutation header (XSRF attack?)");
    }
  }

  /**
   * Loads the {@link SerializationPolicy} for given module base URL and strong name.
   * Returns the policy if successful or null if not found. Due to caching, this method
   * will only be called once for each combination of moduleBaseURL and strongName.</p>
   *
   * <p>The default implementation loads serialization policies stored as servlet resources
   * in the same ServletContext as this servlet.
   *
   * <p>Override this method to load the {@link SerializationPolicy} using an
   * alternative approach.
   * 
   * @param request the HTTP request being serviced
   * @param moduleBaseURL as specified in the incoming payload
   * @param strongName a strong name that uniquely identifies a serialization
   *          policy file
   */
  protected SerializationPolicy doGetSerializationPolicy(
      HttpServletRequest request, String moduleBaseURL, String strongName) {
    return RemoteServiceServlet.loadSerializationPolicy(this, request, moduleBaseURL, strongName);
  }

  /**
   * Returns a URL for fetching a serialization policy from a Super Dev Mode code server.
   *
   * <p>By default, returns null. If the {@code gwt.codeserver.port} system property is set,
   * returns a URL under {@code http://localhost:{port}}.
   *
   * <p>To use a server not on localhost, you must override this method. If you do so,
   * consider the security implications: the policy server and network transport must be
   * trusted or this could be used as a way to disable security checks for some
   * GWT-RPC requests, allowing access to arbitrary Java classes.
   *
   * @param strongName the strong name from the GWT-RPC request (already validated).
   * @return the URL to use or {@code null} if no request should be made.
   */
  protected String getCodeServerPolicyUrl(String strongName) {
    if (codeServerPort <= 0) {
      return null;
    }
    return "http://localhost:" + codeServerPort + "/policies/" + strongName + ".gwt.rpc";
  }

  /**
   * Loads a serialization policy from a Super Dev Mode code server.
   * (Not used unless {@link #getCodeServerPolicyUrl} returns a URL.)
   *
   * <p>The default version is a simple implementation built on java.net.URL that does
   * no authentication. It should only be used during development.</p>
   */
  protected SerializationPolicy loadPolicyFromCodeServer(String url) {
    SerializationPolicyClient.Logger adapter = new SerializationPolicyClient.Logger() {

      @Override
      public void logInfo(String message) {
        RemoteServiceServlet.this.log(message);
      }

      @Override
      public void logError(String message, Throwable throwable) {
        RemoteServiceServlet.this.log(message, throwable);
      }
    };
    return CODE_SERVER_CLIENT.loadPolicy(url, adapter);
  }

  /**
   * Override this method to examine the serialized response that will be
   * returned to the client. The default implementation does nothing and need
   * not be called by subclasses.
   * 
   * @param serializedResponse
   */
  protected void onAfterResponseSerialized(String serializedResponse) {
  }

  /**
   * Override this method to examine the serialized version of the request
   * payload before it is deserialized into objects. The default implementation
   * does nothing and need not be called by subclasses.
   * 
   * @param serializedRequest
   */
  protected void onBeforeRequestDeserialized(String serializedRequest) {
  }

  private SerializationPolicy getCachedSerializationPolicy(
      String moduleBaseURL, String strongName) {
    synchronized (serializationPolicyCache) {
      return serializationPolicyCache.get(moduleBaseURL + strongName);
    }
  }

  private void putCachedSerializationPolicy(String moduleBaseURL,
      String strongName, SerializationPolicy serializationPolicy) {
    synchronized (serializationPolicyCache) {
      serializationPolicyCache.put(moduleBaseURL + strongName,
          serializationPolicy);
    }
  }

  private Writer createWriterForResponse(HttpServletRequest request,
      HttpServletResponse response) throws IOException {
    boolean gzipEncode = RPCServletUtils.acceptsGzipEncoding(request);
    return RPCServletUtils.createWriterForResponse(getServletContext(), response, gzipEncode);
  }
}
