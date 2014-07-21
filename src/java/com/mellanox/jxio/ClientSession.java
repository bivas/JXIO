/*
 ** Copyright (C) 2013 Mellanox Technologies
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at:
 **
 ** http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 ** either express or implied. See the License for the specific language
 ** governing permissions and  limitations under the License.
 **
 */
package com.mellanox.jxio;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.URI;

import com.mellanox.jxio.impl.Bridge;
import com.mellanox.jxio.impl.Event;
import com.mellanox.jxio.impl.EventMsgError;
import com.mellanox.jxio.impl.EventNewMsg;
import com.mellanox.jxio.impl.EventSession;
import com.mellanox.jxio.impl.EventNameImpl;
import com.mellanox.jxio.exceptions.*;
/**
 * ClientSession is the object that connects to the Server. This object initiates the connection.
 * The application uses it to send requests to the server and receive responses.
 * ClientSession receives several events on his lifetime. On each of them a method of interface
 * Callbacks is invoked. User must implement this interface and pass it in c-tor.
 * The events are:
 * 1. onSessionEstablished
 * 2. onSessionEvent
 * 3. onResponse
 * 4. onMsgError
 * 
 */
public class ClientSession extends EventQueueHandler.Eventable {

	private final Callbacks         callbacks;
	private final EventQueueHandler eventQHandler;
	private static final Log        LOG = LogFactory.getLog(ClientSession.class.getCanonicalName());
	private final String            name;
	private final String            nameForLog;

	/**
	* This interface needs to be implemented and passed to ClientSession in c-tor
	* 
	*/
	public static interface Callbacks {
		/**
		 * Event triggered when response from server is received. Request and response are on the same
		 * {@link com.mellanox.jxio.Msg} object. Once the user is done
		 * with the Msg he needs to call method msg.returnToParentPool()
		 * 
		 * @param msg
		 *            - the response message that was received. Msg object contains both request and Response
		 */
		public void onResponse(Msg msg);

		/**
         * The client initiates a connection to Server in c-tor. When the connection is established,
         * onSessionEstablished event is triggered. It is possible to call method sendRequest before receiving
         * onSessionEstablished, however this will only add the requests to internal queue. They will be sent only after
         * onSessionEstablished. In order to receive onSessionEstablished server must accept the session
         * 
         */
		public void onSessionEstablished();

		/**
		 * There are several types of session events: SESSION_CLOSED(because user called ClientSession.close(),
		 * Server initiated close or because of an internal error),
		 * SESSION_REJECTED (if Server chose to reject the session), SESSION_ERROR (due to internal error)
		 * 
		 * @param event
		 *            - the event that was triggered
		 * @param reason
		 *            - the object containing the reason for triggering session_event
		 */
		public void onSessionEvent(EventName event, EventReason reason);

		/**
		 * This event is triggered if there is an error in Msg send/receive. Once the user is done
		 * with the Msg he needs to call method msg.returnToParentPool()
		 * 
		 * @param msg
		 *            - send/receive of this Msg failed
		 * @param reason
		 *            - reason of the msg error
		 */
		public void onMsgError(Msg msg, EventReason reason);
	}

	/**
	 * Constructor of ClientSession.
	 * 
	 * @param eventQHandler
	 *            - EventQueueHAndler on which the events
	 *            (onResponse, onSessionEstablished etc) of this client will arrive
	 * @param uri
	 *            - URI of the server to which this Client will connect
	 *            of the server
	 * @param callbacks
	 *            - implementation of Interface ClientSession.Callbacks
	 */
	public ClientSession(EventQueueHandler eventQHandler, URI uri, Callbacks callbacks) {
		if (LOG.isDebugEnabled()) {
			LOG.debug("CS CTOR entry");
		}
		this.eventQHandler = eventQHandler;
		this.callbacks = callbacks;
		if (!uri.getScheme().equals("rdma") && !uri.getScheme().equals("tcp")) {
			LOG.fatal("mal formatted URI: " + uri);
		}
		String uriStr = uri.toString();
        long cacheId = eventQHandler.getId();
        if (uri.getPath().compareTo("") == 0) {
        	 uriStr+="/";
        }
        if (uri.getQuery() == null) {
        	uriStr+="?"+WorkerCache.CACHE_TAG+"="+cacheId;
        } else {
        	uriStr+="&"+WorkerCache.CACHE_TAG+"="+cacheId;
        }
		final long id = Bridge.startSessionClient(uriStr, eventQHandler.getId());
		this.name = "jxio.CS[" + Long.toHexString(id) + "]";
		this.nameForLog = this.name + ": ";
		if (id == 0) {
			LOG.error(this.toLogString() + "there was an error creating session");
		}

		if (LOG.isDebugEnabled()) {
			LOG.debug(this.toLogString() + "connecting to " + uriStr);
		}
		this.setId(id);

		this.eventQHandler.addEventable(this);

		if (LOG.isDebugEnabled()) {
			LOG.debug(this.toLogString() + "CS CTOR done");
		}
	}

	/**
	 * This method sends the request to server.
	 * <p>
	 * The send is asynchronous, therefore even if the function returns, this does not mean that the msg reached the server or even was sent to the
	 * server. The size send to Server is the current position of the OUT ByteBuffer
	 * 
	 * @param msg
	 *            - Msg to be sent to Server
	 * @throws JxioSessionClosedException if session already closed. In case exception is thrown, msg needs to be returned to pool
	 * @throws JxioGeneralException if send failed for any other reason	 * 
	 */
	public void sendRequest(Msg msg) throws JxioGeneralException, JxioSessionClosedException {
		if (this.getIsClosing()) {
			LOG.warn(this.toLogString() + "Trying to send message while session is closing");
			throw new JxioSessionClosedException("sendRequest");
		}
		int ret = Bridge.clientSendReq(this.getId(), msg.getId(), msg.getOut().position(), msg.getIsMirror());
		if (ret>0){
			if (ret != EventReason.SESSION_DISCONNECTED.getIndex()) {
				LOG.debug(this.toLogString() + "there was an error sending the message because of reason " + ret);
				LOG.debug(this.toLogString() + "unhandled exception. reason is " + ret);
				throw new JxioGeneralException(ret, "sendResponse");
			}else{
				LOG.debug(this.toLogString() + "message send failed because the session is already closed!");
				throw new JxioSessionClosedException("sendResponse");
			}
		}
		msg.setClientSession(this);
		// only if the send was successful the msg needs to be added to the "pending response" list
		eventQHandler.addMsgInUse(msg);
	}

	/**
	 * This method closes the ClientSession.
	 * <p>
	 * The method is asynchronous: the ClientSession will be closed only when it receives event SESSION_CLOSED
	 * 
	 * @return true if there was a successful call to close of Client object on C side and false otherwise
	 */
	public boolean close() {
		if (this.getIsClosing()) {
			LOG.warn(this.toLogString() + "attempting to close client that is already closed or being closed");
			return false;
		}
		if (getId() == 0) {
			LOG.error(this.toLogString() + "closing Session with empty id");
			return false;
		}
		setIsClosing(true);

		Bridge.closeSessionClient(getId());

		if (LOG.isDebugEnabled()) {
			LOG.debug(this.toLogString() + "at the end of SessionClient:close()");
		}
		return true;
	}

	boolean onEvent(Event ev) {
		boolean userNotified = false;
		switch (ev.getEventType()) {

			case 0: // session error event
				if (LOG.isDebugEnabled()) {
					LOG.debug(this.toLogString() + "received session event");
				}
				if (ev instanceof EventSession) {

					int errorType = ((EventSession) ev).getErrorType();
					int reason = ((EventSession) ev).getReason();
					EventNameImpl eventName = EventNameImpl.getEventByIndex(errorType);
					switch (eventName) {
						case SESSION_CLOSED:
							Bridge.deleteClient(this.getId());
							this.setIsClosing(true);
							break;
						case SESSION_REJECT:
							// SESSION_CLOSED will arrive after SESSION_REJECT and then ClientSeesion will be deleted
							// from EQH
							this.setIsClosing(true);
							break;
						// Internal event
						case SESSION_TEARDOWN:
							// now we are officially done with this session and it can be deleted from the EQH
							if (LOG.isDebugEnabled()) {
								LOG.debug(this.toLogString() + "received SESSION_TEARDOWN - internal event");
							}
							eventQHandler.removeEventable(this);
							//if eqh is in state of closing that means we are waiting for the teardown event to close the eqh,
							//so we need to count it in the runeventloop.
							//if not closing than no need to count the event since it's not going up to the user
							return eventQHandler.isClosing;
						default:
							break;
					}
					EventName eventNameForApp = EventName.getEventByIndex(eventName.getIndexPublished());
					EventReason eventReason = EventReason.getEventByXioIndex(reason);
					try {
						userNotified = true;
						callbacks.onSessionEvent(eventNameForApp, eventReason);
					} catch (Exception e) {
						eventQHandler.setCaughtException(e);
						LOG.debug(this.toLogString() + "[onSessionEvent] Callback exception occurred. Event was " + eventName.toString());
					}
				}
				break;

			case 2: // msg error
				if (LOG.isDebugEnabled()) {
					LOG.debug(this.toLogString() + "received msg error event");
				}
				EventMsgError evMsgErr;
				if (ev instanceof EventMsgError) {
					evMsgErr = (EventMsgError) ev;
					Msg msg = evMsgErr.getMsg();
					int reason = evMsgErr.getReason();
					EventReason eventReason = EventReason.getEventByXioIndex(reason);
					try {
						userNotified = true;
						callbacks.onMsgError(msg, eventReason);
					} catch (Exception e) {
						eventQHandler.setCaughtException(e);
						LOG.debug(this.toLogString() + "[onMsgError] Callback exception occurred. Msg was " + msg.toString());
					}
				} else {
					LOG.error(this.toLogString() + "Event is not an instance of EventMsgError");
				}
				break;

			case 3: // session established
				if (LOG.isDebugEnabled()) {
					LOG.debug(this.toLogString() + "received session established event");
				}
				try {
					userNotified = true;
					callbacks.onSessionEstablished();
				} catch (Exception e) {
					eventQHandler.setCaughtException(e);
					LOG.debug(this.toLogString() + "[onSessionEstablished] Callback exception occurred.");
				}
				break;

			case 5: // on response
				if (LOG.isTraceEnabled()) {
					LOG.trace(this.toLogString() + "received msg event");
				}
				EventNewMsg evNewMsg;
				if (ev instanceof EventNewMsg) {
					evNewMsg = (EventNewMsg) ev;
					Msg msg = evNewMsg.getMsg();
					try {
						userNotified = true;
						callbacks.onResponse(msg);
					} catch (Exception e) {
						eventQHandler.setCaughtException(e);
						LOG.debug(this.toLogString() + "[onResponse] Callback exception occurred. Msg was " + msg.toString());
					}
				} else {
					LOG.error(this.toLogString() + "Event is not an instance of EventNewMsg");
				}

				break;

			default:
				LOG.error(this.toLogString() + "received an unknown event " + ev.getEventType());
		}
		return userNotified;
	}

	boolean canClose() {
		return true;
	}

	public String toString() {
		return this.name;
	}

	private String toLogString() {
		return this.nameForLog;
	}
}
