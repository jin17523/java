package ch.inf.vs.californium.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import ch.inf.vs.californium.Server;
import ch.inf.vs.californium.coap.CoAP.ResponseCode;
import ch.inf.vs.californium.coap.CoAP.Type;
import ch.inf.vs.californium.coap.EmptyMessage;
import ch.inf.vs.californium.coap.Request;
import ch.inf.vs.californium.coap.Response;
import ch.inf.vs.californium.network.Endpoint;
import ch.inf.vs.californium.network.EndpointAddress;
import ch.inf.vs.californium.network.EndpointManager;
import ch.inf.vs.californium.network.Exchange;
import ch.inf.vs.californium.network.MessageIntercepter;
import ch.inf.vs.californium.network.NetworkConfig;
import ch.inf.vs.californium.resources.ResourceBase;

/**
 * This test tests that a server removes all observe relations to a client if a
 * notification fails to transmit.
 * <p>
 * The server (7777) has two observable resources X and Y. The client (5683)
 * sends a request A to resource X and a request B to resource Y to observe
 * both. Next, resource X changes and tries to notify request A. However, the
 * notification goes lost (Implementation: ClientMessageInterceptor on the
 * client cancels it). The server retransmits the notification but it goes lost
 * again. The server now counts 2 failed transmissions. Next, the resource
 * changes and issues a new notification. The server cancels the old
 * notification but keeps the retransmission count (2) and the current timeout.
 * After the forth retransmission the server gives up and assumes the client
 * 5683 is offline. The server removes all relations with 5683.
 * <p>
 * In this test, retransmission is done constantly after 2 seconds (timeout does
 * not increase). It should be checked manually that the retransmission counter
 * is not reseted when a resource issues a new notification.
 */
@Ignore // This test takes around 11.2 seconds
public class ObserveTest2 {

	public static final String TARGET_X = "resX";
	public static final String TARGET_Y = "resY";
	public static final String RESPONSE = "hi";
	
	private Server server;
	private MyResource resourceX;
	private MyResource resourceY;
	private ClientMessageInterceptor interceptor;
	
	private boolean waitforit = true;
	
	private int serverPort;
	private String uriX;
	private String uriY;
	
	@Before
	public void startupServer() {
		System.out.println("\nStart "+getClass().getSimpleName());
		createServer();
		this.interceptor = new ClientMessageInterceptor();
		EndpointManager.getEndpointManager().getDefaultEndpoint().addInterceptor(interceptor);
	}
	
	@After
	public void shutdownServer() {
		server.destroy();
		System.out.println("End "+getClass().getSimpleName());
	}
	
	@Test
	public void testObserveLifecycle() throws Exception {
		// setup observe relation to resource X and Y
		Request requestA = Request.newGet();
		requestA.setURI(uriX);
		requestA.setObserve();
		requestA.send();
		
		Request requestB = Request.newGet();
		requestB.setURI(uriY);
		requestB.setObserve();
		requestB.send();
		
		// ensure relations are established
		Response resp1 = requestA.waitForResponse(100);
		assertNotNull(resp1);
		assertTrue(resp1.getOptions().hasObserve());
		assertTrue(resourceX.getObserverCount() == 1);
		assertEquals(resp1.getPayloadString(), resourceX.currentResponse);

		Response resp2 = requestB.waitForResponse(100);
		assertNotNull(resp2);
		assertTrue(resp2.getOptions().hasObserve());
		assertTrue(resourceY.getObserverCount() == 1);
		assertEquals(resp2.getPayloadString(), resourceY.currentResponse);
		
		// change resource but lose response
		Thread.sleep(50);
		resourceX.changed(); // change to "resX sais hi for the 2 time"
		// => trigger notification (which will go lost, see ClientMessageInterceptor)
		
		// wait for the server to timeout, see ClientMessageInterceptor.
		while(waitforit) {
			Thread.sleep(1000);
		}
		
		Thread.sleep(2000);
		
		// the server should now have canceled all observer relations with 5683
		// - request A to resource X
		// - request B to resource Y
		
		// check that relations to resource X AND Y have been canceled
		assertTrue(resourceX.getObserverCount() == 0);
		assertTrue(resourceY.getObserverCount() == 0);
	}
		
	private void createServer() {
		NetworkConfig config = new NetworkConfig();
		config.setAckTimeout(2000);
		config.setAckRandomFactor(1.0f); 
		config.setAckTimeoutScale(1); // retransmit constantly all 2 secs
		
		Endpoint endpoint = new Endpoint(new EndpointAddress(null, 0), config);
		
		server = new Server();
		server.addEndpoint(endpoint);
		resourceX = new MyResource(TARGET_X);
		resourceY = new MyResource(TARGET_Y);
		server.add(resourceX);
		server.add(resourceY);
		server.start();
		
		serverPort = endpoint.getAddress().getPort();
		uriX = "localhost:"+serverPort+"/"+TARGET_X;
		uriY = "localhost:"+serverPort+"/"+TARGET_Y;
	}
	
	private class ClientMessageInterceptor implements MessageIntercepter {

		private int counter = 0; // counts the incoming responses
		
		@Override
		public void receiveResponse(Response response) {
			counter++;
			// frist responses for request A and B
			if (counter == 1) ; // resp 1 ok
			if (counter == 2) ; // resp 2 ok
			
			// notifications:
			if (counter == 3) lose(response); // lose transm. 0 of X's first notification
			if (counter == 4) lose(response); // lose transm. 1 of X's first notification
			if (counter == 5) {
				lose(response); // lose transm. 2 of X's first notification
				resourceX.changed(); // change to "resX sais hi for the 3 time"
			}
			
			/*
			 * Note: The resource has changed and needs to send a second
			 * notification. However, the first notification has not been
			 * acknowledged yet. Therefore, the second notification keeps the
			 * transmission counter of the first notification. There are no
			 * transm. 0 and 1 of X's second notification.
			 */
			
			if (counter == 6) lose(response); // lose transm. 2 of X's second notification
			if (counter == 7) lose(response); // lose transm. 3 of X's second notification
			if (counter == 8) {
				lose(response); // lose transm. 4 of X's second notification

				/*
				 * Note: The server now reaches the retransmission limit and
				 * cancels the response. Since it was an observe notification,
				 * the server now removes all observe relations from the
				 * endpoint 5683 which are request A to resource X and request B
				 * to resource Y.
				 */
				waitforit = false;
			}
			
			if (counter >= 9) // error
				throw new RuntimeException("Should not receive "+counter+" responses");
		}
		
		private void lose(Response response) {
			System.out.println("Lose response "+counter+" with MID "+response.getMID());
			response.cancel();
		}
		
		@Override public void sendRequest(Request request) { }
		@Override public void sendResponse(Response response) { }
		@Override public void sendEmptyMessage(EmptyMessage message) { }
		@Override public void receiveRequest(Request request) { }
		@Override public void receiveEmptyMessage(EmptyMessage message) { }
	}
	
	private static class MyResource extends ResourceBase {
		
		private Type type = Type.CON;
		private int counter = 0;
		private String currentResponse;
		
		public MyResource(String name) {
			super(name);
			setObservable(true);
			changed();
		}
		
		@Override
		public void processGET(Exchange exchange) {
			Response response = new Response(ResponseCode.CONTENT);
			response.setPayload(currentResponse);
			response.setType(type);
			exchange.respond(response);
		}
		
		@Override
		public void changed() {
			currentResponse = "\""+getName()+" sais hi for the "+(++counter)+" time\"";
			System.out.println("Resource "+getName()+" changed to "+currentResponse);
			super.changed();
		}
	}
}
