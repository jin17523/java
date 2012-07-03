/*******************************************************************************
 * Copyright (c) 2012, Institute for Pervasive Computing, ETH Zurich.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the Institute nor the names of its contributors
 * may be used to endorse or promote products derived from this software
 * without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE INSTITUTE AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE INSTITUTE OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * 
 * This file is part of the Californium (Cf) CoAP framework.
 ******************************************************************************/

package ch.ethz.inf.vs.californium.layers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.EnglishReasonPhraseCatalog;
import org.apache.http.impl.nio.DefaultHttpServerIODispatch;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.DefaultNHttpServerConnectionFactory;
import org.apache.http.impl.nio.reactor.DefaultListeningIOReactor;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.BasicAsyncRequestConsumer;
import org.apache.http.nio.protocol.BasicAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncExchange;
import org.apache.http.nio.protocol.HttpAsyncRequestConsumer;
import org.apache.http.nio.protocol.HttpAsyncRequestHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerRegistry;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.ListeningIOReactor;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.params.SyncBasicHttpParams;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpRequestHandler;
import org.apache.http.protocol.ImmutableHttpProcessor;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;

import ch.ethz.inf.vs.californium.coap.Message;
import ch.ethz.inf.vs.californium.coap.Request;
import ch.ethz.inf.vs.californium.coap.Response;
import ch.ethz.inf.vs.californium.util.HttpTranslator;
import ch.ethz.inf.vs.californium.util.TranslationException;

/**
 * Class encapsulating the logic of a web server. The class create a receiver
 * thread that it is always blocked on the listen primitive. For each connection
 * this thread creates a new thread that handles the client/server dialog.
 * 
 * @author Francesco Corazza
 * 
 */
public class HttpStack extends UpperLayer {
	private static final String LOCAL_RESOURCE_NAME = "proxy";
	private static final String SERVER_NAME = "Californium Http Proxy";
	private ConcurrentHashMap<Request, Semaphore> sleepingThreads = new ConcurrentHashMap<Request, Semaphore>();

	/**
	 * Instantiates a new http stack on the requested port.
	 * 
	 * @param httpPort
	 *            the http port
	 * @throws IOException
	 *             Signals that an I/O exception has occurred.
	 */
	public HttpStack(int httpPort) throws IOException {

		// HTTP parameters for the server
		HttpParams params = new SyncBasicHttpParams();
		params.setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000).setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024).setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true).setParameter(CoreProtocolPNames.ORIGIN_SERVER, SERVER_NAME);

		// Create HTTP protocol processing chain
		HttpProcessor httpproc = new ImmutableHttpProcessor(new HttpResponseInterceptor[] {
				// Use standard server-side protocol interceptors
		new ResponseDate(), new ResponseServer(), new ResponseContent(), new ResponseConnControl() });
		// Create request handler registry
		HttpAsyncRequestHandlerRegistry registry = new HttpAsyncRequestHandlerRegistry();

		// register the handler that will reply to the proxy requests
		registry.register("/" + LOCAL_RESOURCE_NAME + "/*", new ProxyAsyncRequestHandler(LOCAL_RESOURCE_NAME));
		// Register the default handler for root URIs
		// wrapping a common request handler with an async request handler
		registry.register("*", new BasicAsyncRequestHandler(new BaseRequestHandler()));

		// Create server-side HTTP protocol handler
		HttpAsyncService protocolHandler = new HttpAsyncService(httpproc, new DefaultConnectionReuseStrategy(), registry, params);

		// Create HTTP connection factory
		NHttpConnectionFactory<DefaultNHttpServerConnection> connFactory = new DefaultNHttpServerConnectionFactory(params);

		// Create server-side I/O event dispatch
		final IOEventDispatch ioEventDispatch = new DefaultHttpServerIODispatch(protocolHandler, connFactory);

		final ListeningIOReactor ioReactor;
		try {
			// Create server-side I/O reactor
			ioReactor = new DefaultListeningIOReactor();
			// Listen of the given port
			ioReactor.listen(new InetSocketAddress(httpPort));

			// create the listener thread
			Thread listener = new Thread() {

				@Override
				public void run() {
					// Starts the reactor and initiates the dispatch of I/O
					// event notifications to the given IOEventDispatch.
					try {
						ioReactor.execute(ioEventDispatch);
					} catch (IOException e) {
						LOG.severe("Interrupted");
					}
				}

			};

			listener.setDaemon(false);
			listener.start();
		} catch (IOException e) {
			LOG.severe("I/O error: " + e.getMessage());
		}

		LOG.info("Shutdown");
	}

	/**
	 * Checks if is waiting.
	 * 
	 * @param message
	 *            the message
	 * @return true, if is waiting
	 */
	public boolean isWaiting(Message message) {
		if (!(message instanceof Response)) {
			return false;
		}

		Request request = ((Response) message).getRequest();
		// return pendingResponsesMap.containsKey(request);
		return sleepingThreads.containsKey(request);
	}

	/*
	 * (non-Javadoc)
	 * @see
	 * ch.ethz.inf.vs.californium.layers.UpperLayer#doSendMessage(ch.ethz.inf
	 * .vs.californium.coap.Message)
	 */
	@Override
	protected void doSendMessage(Message message) throws IOException {
		// check only if the message is a response
		if (message instanceof Response) {
			// retrieve the request linked to the response
			Response coapResponse = (Response) message;
			Request coapRequest = coapResponse.getRequest();

			// get the associated semaphore and release it to wake up the
			// sleeping thread
			Semaphore semaphore = sleepingThreads.get(coapRequest);
			semaphore.release();
		}
	}

	/**
	 * The Class BaseRequestHandler.
	 * 
	 * @author Francesco Corazza
	 */
	private class BaseRequestHandler implements HttpRequestHandler {

		@Override
		public void handle(HttpRequest httpRequest, HttpResponse httpResponse, HttpContext httpContext) throws HttpException, IOException {
			httpResponse.setStatusCode(HttpStatus.SC_OK);
			httpResponse.setEntity(new StringEntity("Californium Proxy server"));
		}
	}

	/**
	 * Class associated with the http service to translate the http requests in
	 * coap requests.
	 * 
	 * @author Francesco Corazza
	 */
	private class ProxyAsyncRequestHandler implements
			HttpAsyncRequestHandler<HttpRequest> {

		// the class is thread-safe because the local resource is set in the
		// constructor and then only read by the methods
		private final String localResource;

		/**
		 * Instantiates a new proxy request handler.
		 * 
		 * @param localResource
		 *            the local resource
		 */
		public ProxyAsyncRequestHandler(String localResource) {
			super();

			this.localResource = localResource;
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * org.apache.http.nio.protocol.HttpAsyncRequestHandler#handle(java.
		 * lang.Object, org.apache.http.nio.protocol.HttpAsyncExchange,
		 * org.apache.http.protocol.HttpContext)
		 */
		@Override
		public void handle(HttpRequest httpRequest, final HttpAsyncExchange httpExchange, HttpContext httpContext) throws HttpException, IOException {

			try {
				// translate the request in a valid coap request
				final Request coapRequest = HttpTranslator.getCoapRequest(httpRequest, localResource);

				// create the a mutex to handle the producer/consumer pattern
				final Semaphore semaphore = new Semaphore(0);

				// the new anonymous thread will wait for the completion of the
				// coap request
				Thread worker = new Thread() {
					private static final long TIMEOUT = 5000; // TODO

					@Override
					public void run() {

						try {
							// waiting for the coap response
							semaphore.tryAcquire(TIMEOUT, TimeUnit.MILLISECONDS);
						} catch (InterruptedException e) {
							// if the thread is interrupted, terminate
							if (isInterrupted()) {
								sendSimpleHttpResponse(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
								return;
							}
						}

						// get the coap response, the request will be filled
						// with the response the thread waking up this thread
						Response coapResponse = coapRequest.getResponse();

						if (coapResponse != null) {
							// get the sample http response
							HttpResponse httpResponse = httpExchange.getResponse();

							// translate the coap response in an http response
							try {
								HttpTranslator.getHttpResponse(coapResponse, httpResponse);
							} catch (TranslationException e) {
								sendSimpleHttpResponse(httpExchange, HttpStatus.SC_INTERNAL_SERVER_ERROR);
								return;
							}

							// send the response
							httpExchange.submitResponse();
						} else {
							sendSimpleHttpResponse(httpExchange, HttpStatus.SC_GATEWAY_TIMEOUT);
						}
					}
				};

				// put in the map the request that
				sleepingThreads.put(coapRequest, semaphore);

				// starting the "consumer thread"
				worker.start();

				// send the coap request in the upper layer
				doReceiveMessage(coapRequest);
			} catch (TranslationException e) {

				sendSimpleHttpResponse(httpExchange, HttpStatus.SC_NOT_IMPLEMENTED);

				return;
			}
		}

		/*
		 * (non-Javadoc)
		 * @see
		 * org.apache.http.nio.protocol.HttpAsyncRequestHandler#processRequest
		 * (org.apache.http.HttpRequest, org.apache.http.protocol.HttpContext)
		 */
		@Override
		public HttpAsyncRequestConsumer<HttpRequest> processRequest(HttpRequest httpRequest, HttpContext httpContext) throws HttpException, IOException {
			// DEBUG
			System.out.println(">> Request: " + httpRequest);

			// Buffer request content in memory for simplicity
			return new BasicAsyncRequestConsumer();
		}

		/**
		 * @param httpExchange
		 */
		private void sendSimpleHttpResponse(HttpAsyncExchange httpExchange, int httpCode) {
			// get the empty response
			HttpResponse httpResponse = httpExchange.getResponse();

			// create and set the status line
			StatusLine statusLine = new BasicStatusLine(HttpVersion.HTTP_1_1, httpCode, EnglishReasonPhraseCatalog.INSTANCE.getReason(httpCode, Locale.ENGLISH));
			httpResponse.setStatusLine(statusLine);

			// send the error response
			httpExchange.submitResponse();
		}
	}
}
