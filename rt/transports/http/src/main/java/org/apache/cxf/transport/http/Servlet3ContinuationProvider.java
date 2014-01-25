/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.transport.http;

import java.io.IOException;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.cxf.continuations.Continuation;
import org.apache.cxf.continuations.ContinuationCallback;
import org.apache.cxf.continuations.ContinuationProvider;
import org.apache.cxf.message.Message;

/**
 * 
 */
public class Servlet3ContinuationProvider implements ContinuationProvider {
    HttpServletRequest req;
    HttpServletResponse resp; 
    Message inMessage;
    Servlet3Continuation continuation;
    
    public Servlet3ContinuationProvider(HttpServletRequest req,
                                        HttpServletResponse resp, 
                                        Message inMessage) {
        this.inMessage = inMessage;
        this.req = req;
        this.resp = resp;
    }
    
    public void complete() {
        if (continuation != null) {
            continuation.reset();
            continuation = null;
        }
    }
    

    /** {@inheritDoc}*/
    public Continuation getContinuation() {
        if (inMessage.getExchange().isOneWay()) {
            return null;
        }

        if (continuation == null) {
            continuation = new Servlet3Continuation();
        } else {
            continuation.startAsyncAgain();
        }
        return continuation;
    }
    
    public class Servlet3Continuation implements Continuation, AsyncListener {
        AsyncContext context;
        volatile boolean isNew = true;
        volatile boolean isResumed;
        volatile boolean isPending;
        volatile Object obj;
        private ContinuationCallback callback;
        public Servlet3Continuation() {
            req.setAttribute(AbstractHTTPDestination.CXF_CONTINUATION_MESSAGE,
                             inMessage.getExchange().getInMessage());
            callback = inMessage.getExchange().get(ContinuationCallback.class);
            context = req.startAsync(req, resp);
            context.addListener(this);
        }

        void startAsyncAgain() {
            
            AsyncContext old = context;
            try {
                context = req.startAsync();
            } catch (IllegalStateException ex) { 
                context = old;
            }
            context.addListener(this);
        }
        
        public boolean suspend(long timeout) {
            if (isPending && timeout != 0) {
                long currentTimeout = context.getTimeout();
                timeout = currentTimeout + timeout;
            } else {
                isPending = true;
            }
            isNew = false;
            
            context.setTimeout(timeout);
            inMessage.getExchange().getInMessage().getInterceptorChain().suspend();
            
            return true;
        }
        public void redispatch() {
            context.dispatch();
        }
        public void resume() {
            isResumed = true;
            isPending = false;
            redispatch();
        }

        public void reset() {
            context.complete();
            obj = null;
            if (callback != null) {
                final Exception ex = inMessage.getExchange().get(Exception.class);
                Throwable cause = isCausedByIO(ex);
                
                if (cause != null && isClientDisconnected(cause)) {
                    callback.onDisconnect();    
                }
            }
        }

        public boolean isNew() {
            return isNew;
        }

        public boolean isPending() {
            return isPending;
        }

        public boolean isResumed() {
            return isResumed;
        }

        public Object getObject() {
            return obj;
        }

        public void setObject(Object o) {
            obj = o;
        }

        public void onComplete(AsyncEvent event) throws IOException {
            inMessage.getExchange().getInMessage()
                .remove(AbstractHTTPDestination.CXF_CONTINUATION_MESSAGE);
            isPending = false;
            //REVISIT: isResumed = false;
            if (callback != null) {
                callback.onComplete();
            }
        }
        public void onError(AsyncEvent event) throws IOException {
            if (callback != null) {
                callback.onError(event.getThrowable());
            }
        }
        public void onStartAsync(AsyncEvent event) throws IOException {
        }
        public void onTimeout(AsyncEvent event) throws IOException {
            isPending = false;
            //REVISIT: isResumed = true;
            redispatch();
        }
        
        private Throwable isCausedByIO(final Exception ex) {
            Throwable cause = ex;
            
            while (cause != null && !(cause instanceof IOException)) {
                cause = cause.getCause();
            }
            
            return cause;
        }
        
        private boolean isClientDisconnected(Throwable ex) {
            String exName = (String)inMessage.getContextualProperty("disconnected.client.exception.class");
            if (exName != null) {
                return exName.equals(IOException.class.getName()) || exName.equals(ex.getClass().getName());
            } else {
                return false;
            }
        }
        
    }
}
