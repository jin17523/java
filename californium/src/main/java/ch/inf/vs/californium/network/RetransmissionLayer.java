package ch.inf.vs.californium.network;

import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import ch.inf.vs.californium.coap.CoAP.Type;
import ch.inf.vs.californium.coap.EmptyMessage;
import ch.inf.vs.californium.coap.Message;
import ch.inf.vs.californium.coap.Request;
import ch.inf.vs.californium.coap.Response;

public class RetransmissionLayer extends AbstractLayer {
	
//	public static final int ACK_TIMEOUT = 2000;
//	public static final float ACK_RANDOM_FACTOR = 1.5f;
//	public static final int MAX_RETRANSMIT = 4;
	
	private final static Logger LOGGER = Logger.getLogger(RetransmissionLayer.class.getName());
	
	private Random rand = new Random();
	
	private StackConfiguration config;
	
	public RetransmissionLayer(StackConfiguration config) {
		this.config = config;
	}
	
	@Override
	public void sendRequest(Exchange exchange, Request request) {
		assert(exchange != null);
		exchange.setTransmissionCount(0);
		sendRequest0(exchange, request);
	}
	
	private void sendRequest0(final Exchange exchange, final Request request) {
		assert(exchange != null);
		LOGGER.info("send request (transmission "+exchange.getTransmissionCount()+"), "+request);
		prepareRetransmission(exchange, new RetransmissionTask(exchange, request) {
			public void retransmitt() {
				sendRequest0(exchange, request);
			}
		});
		super.sendRequest(exchange, request);
	}

	@Override
	public void sendResponse(Exchange exchange, Response response) {
		exchange.setTransmissionCount(0);
		sendResponse0(exchange, response);
	}
	
	private void sendResponse0(final Exchange exchange, final Response response) {
		assert(exchange != null);
		LOGGER.info("send response (transmission "+exchange.getTransmissionCount()+"), "+response);
		if (response.getType() == Type.CON) {
			prepareRetransmission(exchange, new RetransmissionTask(exchange, response) {
				public void retransmitt() {
					sendResponse(exchange, response);
				}
			});
		}
		super.sendResponse(exchange, response);
	}
	
	
	private void prepareRetransmission(Exchange exchange, RetransmissionTask task) {
		/*
		 * For a new Confirmable message, the initial timeout is set to a
		 * random number between ACK_TIMEOUT and (ACK_TIMEOUT *
		 * ACK_RANDOM_FACTOR)
		 */
		int timeout;
		if (exchange.getTransmissionCount() == 0) {
			int ack_timeout = config.getAckTimeout();
			float ack_random_factor = config.getAckRandomFactor();
			timeout = getRandomTimeout(ack_timeout, (int) (ack_timeout*ack_random_factor));
		} else {
			timeout = 2 * exchange.getCurrentTimeout();
		}
		exchange.setCurrentTimeout(timeout);
		LOGGER.info("set retransmission timeout to "+timeout);
		
		exchange.setTransmissionCount(exchange.getTransmissionCount() + 1);
		ScheduledFuture<?> f = executor.schedule(task , timeout, TimeUnit.MILLISECONDS);
		exchange.setRetransmissionHandle(f);
	}
	
	@Override
	public void sendEmptyMessage(Exchange exchange, EmptyMessage message) {
		assert(exchange != null && message != null);
		super.sendEmptyMessage(exchange, message);
	}

	@Override
	public void receiveRequest(Exchange exchange, Request request) {
		assert(exchange != null && request != null);
		
		if (request.isDuplicate()) {
			// Request is a duplicate, so resend ACK, RST or response
			if (exchange.getCurrentResponse() != null) {
				sendResponse(exchange, exchange.getCurrentResponse());
				
			} else if (exchange.getCurrentRequest().isAcknowledged()) {
				EmptyMessage ack = EmptyMessage.newACK(request);
				sendEmptyMessage(exchange, ack);
			
			} else if (exchange.getCurrentRequest().isRejected()) { 
				EmptyMessage rst = EmptyMessage.newRST(request);
				sendEmptyMessage(exchange, rst);
			} else {
				// server has not yet decided, whether to ack or reject request
				ignore(request);
			}

		} else {
			// Request is not a duplicate
			super.receiveRequest(exchange, request);
		}
	}

	@Override
	public void receiveResponse(Exchange exchange, Response response) {
		assert(exchange != null && response != null);
		
		if (response.isDuplicate()) {
			EmptyMessage ack = EmptyMessage.newACK(response);
			sendEmptyMessage(exchange, ack);
			ignore(response);
		} else {
			super.receiveResponse(exchange, response);
		}
	}

	@Override
	public void receiveEmptyMessage(Exchange exchange, EmptyMessage message) {
		assert(exchange != null && message != null);
		if (message.getType() == Type.ACK) {
			if (exchange.isFromLocal())
				exchange.getCurrentRequest().setAcknowledged(true);
			else
				exchange.getCurrentResponse().setAcknowledged(true);
		} else if (message.getType() == Type.RST) {
			if (exchange.isFromLocal())
				exchange.getCurrentRequest().setRejected(true);
			else
				exchange.getCurrentResponse().setRejected(true);
		} else {
			LOGGER.warning("Empty messgae was not ACK nor RST: "+message);
		}
		ScheduledFuture<?> retransmissionHandle = exchange.getRetransmissionHandle();
		if (retransmissionHandle != null) {
			LOGGER.info("Cancel retransmission");
			retransmissionHandle.cancel(false);
		}
		super.receiveEmptyMessage(exchange, message);
	}
	
	private int getRandomTimeout(int min, int max) {
		return min + rand.nextInt(max - min);
	}
	
	/*
	 * The main reason to create this class was to enable the methods
	 * sendRequest and sendResponse to use the same code for sending messages
	 * but where the retransmission method calls sendRequest and sendResponse
	 * respectively.
	 */
	private abstract class RetransmissionTask implements Runnable {
		
		private Exchange exchange;
		private Message message;
		
		public RetransmissionTask(Exchange exchange, Message message) {
			this.exchange = exchange;
			this.message = message;
		}
		
		@Override
		public void run() {
			try {
				if (message.isAcknowledged()) {
					LOGGER.info("Timeout: request already acknowledged, cancel retransmission");
					return;
					
				} else if (message.isRejected()) {
					LOGGER.info("Timeout: request already rejected, cancel retransmission");
					return;
				
				} else if (exchange.getTransmissionCount() <= config.getMaxRetransmit()) {
					LOGGER.info("Timeout: retransmitt message");
					retransmitt();

				} else {
					LOGGER.info("Timeout: retransmission limit reached, exchange failed");
					exchange.setTimeouted(true);
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		
		public abstract void retransmitt();
	}
	
}
