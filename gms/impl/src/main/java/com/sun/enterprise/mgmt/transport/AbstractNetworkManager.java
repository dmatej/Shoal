/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2011 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.mgmt.transport;

import com.sun.enterprise.ee.cms.core.GMSConstants;
import com.sun.enterprise.ee.cms.core.ServiceProviderConfigurationKeys;
import com.sun.enterprise.ee.cms.impl.base.PeerID;
import com.sun.enterprise.ee.cms.impl.base.Utility;
import com.sun.enterprise.ee.cms.logging.GMSLogDomain;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.ServiceLoader;
import java.util.Iterator;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.io.IOException;

/**
 * This class implements a common {@link NetworkManager} logic simply in order to help the specific transport layer to be implemented easily
 *
 * Mainly, this manages {@link MessageListener} and dispatches an inbound {@link Message} into the appropriate listener
 *
 * @author Bongjae Chang
 */
public abstract class AbstractNetworkManager implements NetworkManager {

    private static final Logger LOG = GMSLogDomain.getLogger( GMSLogDomain.GMS_LOGGER );

    /**
     * Represents local {@link PeerID}.
     * This value should be assigned in real {@link NetworkManager}'s implementation corresponding to the specific transport layer
     */
    private PeerID<?> localPeerID;

    /**
     * The list of registered {@link MessageListener}
     */
    private final List<MessageListener> messageListeners = new CopyOnWriteArrayList<MessageListener>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() throws IOException {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop() throws IOException {
        messageListeners.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void addMessageListener( final MessageListener messageListener ) {
        if( messageListener != null )
            messageListeners.add( messageListener );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeMessageListener( final MessageListener messageListener ) {
        if( messageListener != null )
            messageListeners.remove( messageListener );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receiveMessage( Message message, Map<?, ?> piggyback ) {
        PeerID<?> sourcePeerID = null;
        PeerID<?> targetPeerID = null;
        if( message != null ) {
            Object element = message.getMessageElement( Message.SOURCE_PEER_ID_TAG );
            if( element instanceof PeerID )
                sourcePeerID = (PeerID<?>)element;
            element = message.getMessageElement( Message.TARGET_PEER_ID_TAG );
            if( element instanceof PeerID )
                targetPeerID = (PeerID<?>)element;
        }
        if( sourcePeerID != null && !localPeerID.getGroupName().equals( sourcePeerID.getGroupName() ) )
            return; // drop the different group's packet
        // this is redundant check
        //if( targetPeerID != null && !localPeerID.getGroupName().equals( targetPeerID.getGroupName() ) )
        //    return; // drop the different group's packet
        MessageEvent messageEvent = new MessageEvent( this, message, sourcePeerID, targetPeerID );
        try {
            beforeDispatchingMessage( messageEvent, piggyback );
        } catch( Throwable t ) {
            LOG.log(Level.WARNING, "absnetmgr.beforefailed", t);
        }
        boolean messageEventNotProcessed = true;
        for( MessageListener listener : messageListeners ) {
            if( message.getType() == listener.getType() ) {
                try {
                    messageEventNotProcessed = false;
                    listener.receiveMessageEvent( messageEvent );
                } catch( Throwable t ) {
                    LOG.log(Level.WARNING, "failed to receive a message: type = ", new Object[]{ message.getType()});
                    LOG.log(Level.WARNING, "stack trace", t);
                }
            }
        }
        if (messageEventNotProcessed) {
            if (LOG.isLoggable(Level.FINER)) {
                LOG.log(Level.FINER, "No message listener for messageEvent: {0} "
                        + "Message :{1} MessageFrom: {2} MessageTo:{3}",
                        new Object[]{messageEvent.toString(), message,
                        sourcePeerID, targetPeerID});
            }
        }
        try {
            afterDispatchingMessage( messageEvent, piggyback );
        } catch( Throwable t ) {
            LOG.log(Level.WARNING, "absnetmgr.afterfailed", t);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PeerID<?> getLocalPeerID() {
        return localPeerID;
    }


    protected void setLocalPeerID(PeerID<?> localPeerID) {
        this.localPeerID = localPeerID;
    }


    private static NetworkManager findByServiceLoader(String transport) {
        NetworkManager networkManager = null;
        ServiceLoader<NetworkManager> loader = ServiceLoader.load(NetworkManager.class);
        Iterator<NetworkManager> iter = loader.iterator();

        while (iter.hasNext())  {
            try {
                networkManager = iter.next().getClass().newInstance();
                if (transport.startsWith("grizzly")) {
                    if (networkManager.getClass().getName().contains(transport)) {
                        // found service that matches group communication provider.
                        break;
                    }
                } else if (transport.compareToIgnoreCase(GMSConstants.JXTA_GROUP_COMMUNICATION_PROVIDER) == 0) {
                    if (networkManager.getClass().getName().contains("Jxta")) {
                        // found service that matches group communication provider.
                        break;
                    }
                }
            } catch (Throwable t) {

                // only a SEVERE error if no implementation of service is available.
                // for example in glassfish 3.1.x, there is no grizzly 2.o jars.
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, "error instantiating NetworkManager service", t);
                }
            }
        }
        if (networkManager == null) {
           LOG.log(Level.SEVERE, "fatal error, no NetworkManger implementations found");
        }
        return networkManager;
    }

    private static NetworkManager findByClassLoader(String classname) {
        NetworkManager networkManager = null;
        // for jdk 5.  just use class loader.
        try{
            Class<?> networkManagerClass = Class.forName(classname);
            networkManager = (NetworkManager)networkManagerClass.newInstance();
        } catch (Throwable x) {
            LOG.log(Level.SEVERE, "fatal error instantiating NetworkManager service", x);
        }
        return networkManager;
    }

    public static NetworkManager getInstance(String transport) {
        NetworkManager networkManager = null;
        try {
            networkManager = findByServiceLoader(transport);
        } catch (Throwable t) {
            // jdk 5 will end up here.
        }
        if (networkManager == null) {
            String classname = null;
            final String GRIZZLY_TRANSPORT_BASE_DIR = "com.sun.enterprise.mgmt.transport.grizzly.";
            if (transport.startsWith("grizzly2")) {
                classname = GRIZZLY_TRANSPORT_BASE_DIR + transport + ".GrizzlyNetworkManager2";
            } else if (transport.startsWith("grizzly1_9")) {
                classname = GRIZZLY_TRANSPORT_BASE_DIR + transport + ".GrizzlyNetworkManager1_9";
            } else {
                classname = "com.sun.enterprise.mgmt.transport.jxta.JxtaNetworkManager";
            }
            networkManager = findByClassLoader(classname);
        }
        return networkManager;
    }

    /**
     * Before executing {@link MessageListener#receiveMessageEvent(MessageEvent)}} callback, this method will be called
     *
     * @param messageEvent a received {@link MessageEvent}
     * @param piggyback piggyback
     */
    protected abstract void beforeDispatchingMessage( MessageEvent messageEvent, Map<?, ?> piggyback );

    /**
     * After executing {@link MessageListener#receiveMessageEvent(MessageEvent)}} callback, this method will be called
     *
     * @param messageEvent a received {@link MessageEvent}
     * @param piggyback piggyback
     */
    protected abstract void afterDispatchingMessage( MessageEvent messageEvent, Map<?, ?> piggyback );

    static public Logger getLogger() {
        return LOG;
    }

    @Override
    public synchronized void initialize( final String groupName,
            final String instanceName, final Properties properties )
            throws IOException {
        int maxMsgLength =  Utility.getIntProperty( ServiceProviderConfigurationKeys.MAX_MESSAGE_LENGTH.toString(),
                                                    MessageImpl.DEFAULT_MAX_TOTAL_MESSAGE_LENGTH,
                                                    properties );
        MessageImpl.setMaxMessageLength(maxMsgLength);
        if (LOG.isLoggable(Level.CONFIG))  {
            LOG.log(Level.CONFIG, "GMS MAX_MESSAGE_LENGTH={0}", maxMsgLength);
        }
    }
}
