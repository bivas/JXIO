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

import java.util.logging.Level;

import com.mellanox.jxio.impl.Bridge;
import com.mellanox.jxio.impl.Event;
import com.mellanox.jxio.impl.EventNewSession;
import com.mellanox.jxio.impl.EventSession;
import com.mellanox.jxio.impl.Eventable;

public class ServerManager implements Eventable {
	
	private long id = 0;
	private EventQueueHandler eventQHndl = null;
	private int port;
	private String url;
	private String urlPort0;
	static protected int sizeEventQ = 10000;
	boolean isClosing = false; //indicates that this class is in the process of releasing it's resources
	private Callbacks callbacks;
	
	private static Log logger = Log.getLog(ServerManager.class.getCanonicalName());
	
	public static interface Callbacks {
	    public void onSession(long ptrSes, String uri, String srcIP);
	    public void onSessionError(int errorType, String reason);
	}
	
	public ServerManager(EventQueueHandler eventQHandler, String url, Callbacks callbacks) {
		this.url = url;
		this.eventQHndl = eventQHandler;
		this.callbacks = callbacks;
		
		long [] ar = Bridge.startServer(url, eventQHandler.getID());
		this.id = ar[0];
		this.port = (int) ar[1];
		
		if (this.id == 0) {
			logger.log(Level.SEVERE, "there was an error creating SessionManager");
		}
		createUrlForServerSession();
		logger.log(Level.INFO, "urlForServerSession is "+urlPort0);
		
		this.eventQHndl.addEventable (this); 
		this.eventQHndl.runEventLoop(1000, -1 /* Infinite */);
	}
	
	private void createUrlForServerSession() {
	    //parse url so it would replace port number on which the server listens with 0
	    int index = url.lastIndexOf(":"); 
	    this.urlPort0 = url.substring(0, index+1)+"0";
	}
	
	public String getUrlForServer() {return urlPort0;}
	
	public boolean close() {
		this.eventQHndl.removeEventable (this); //TODO: fix this
		if (id == 0){
			logger.log(Level.SEVERE, "closing ServerManager with empty id");
			return false;
		}
		Bridge.stopServer(id);
		this.isClosing = true;
		return true;
	}
	
	public void forward(ServerSession ses, long ptrSes){
	    logger.log(Level.INFO, "****** new url inside forward  is "+ses.url);
	    
		Bridge.forwardSession(ses.url, ptrSes, ses.getId());
	}
	
	public long getId(){ return id;} 
	public boolean isClosing() {return isClosing;}
	
	
	public void onEvent(Event ev) {
		switch (ev.getEventType()) {
		
		case 0: //session error event
			logger.log(Level.INFO, "received session error event");
			if (ev  instanceof EventSession){
				int errorType = ((EventSession) ev).getErrorType();
				String reason = ((EventSession) ev).getReason();
				this.callbacks.onSessionError(errorType, reason);

				if (errorType == 1) {//event = "SESSION_TEARDOWN";
					this.eventQHndl.removeEventable(this); //now we are officially done with this session and it can be deleted from the EQH
				}
			}
			break;
			
		case 4: //on new session
			logger.log(Level.INFO, "received session error event");
			if (ev  instanceof EventNewSession){
				long ptrSes = ((EventNewSession) ev).getPtrSes();
				String uri = ((EventNewSession) ev).getUri();		
				String srcIP = ((EventNewSession) ev).getSrcIP();
				this.callbacks.onSession(ptrSes, uri, srcIP);

			}
			break;
		
		default:
			logger.log(Level.SEVERE, "received an unknown event "+ ev.getEventType());
		}
	}
}
