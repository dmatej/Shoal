/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2010 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.mgmt;

import com.sun.enterprise.ee.cms.impl.base.PeerID;
import com.sun.enterprise.mgmt.transport.*;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The LWRMulticast class is useful for sending and receiving
 * JXTA multicast messages. A LWRMulticast is a (UDP) DatagramSocket,
 * with additional capabilities for joining "groups" of other multicast hosts
 * on the internet.
 * A multicast group is specified within the context of PeerGroup and a propagate
 * pipe advertisement.
 * One would join a multicast group by first creating a MulticastSocket
 * with the desired peer group and pipe advertisement.
 */
public class LWRMulticast implements MessageListener {
    private final static Logger LOG = Logger.getLogger(LWRMulticast.class.getName());

    /**
     * Ack message element name
     */
    public final static String ACKTAG = "ACK";
    /**
     * seq number message element name
     */
    public final static String SEQTAG = "SEQ";
    /**
     * source node id message element name
     */
    public final static String SRCIDTAG = "SRCID";
    private transient boolean closed = false;
    private transient boolean bound = false;

    private transient long padding = 250;
    private transient long timeout = 5000 + padding;
    private transient AtomicLong sequence = new AtomicLong();
    private final Object ackLock = new Object();
    private transient int threshold = 0;
    private transient Set<PeerID<?>> ackSet = new HashSet<PeerID<?>>();
    private transient Set<PeerID<?>> ackList = new HashSet<PeerID<?>>();
    private ClusterManager manager;
    private PeerID<?> localPeerID;

    /**
     * The application message listener
     */
    protected transient MessageListener msgListener;

    /**
     * Create a multicast channel bind it to a specific pipe within specified
     * peer group
     *
     * @param manager     the ClusterManger
     * @param msgListener the application listener
     * @throws IOException if an io error occurs
     */
    public LWRMulticast(ClusterManager manager, MessageListener msgListener) throws IOException {
        joinGroup(manager, msgListener);
    }

    /**
     * joins MutlicastSocket to specified pipe within the context of group
     *
     * @param manager     the ClusterManger
     * @param msgListener The application message listener
     * @throws IOException if an io error occurs
     */
    public void joinGroup(ClusterManager manager, MessageListener msgListener) throws IOException {
        if (msgListener == null) {
            throw new IllegalArgumentException("msgListener can not be null");
        }
        this.manager = manager;
        this.localPeerID = manager.getPeerID();
        this.msgListener = msgListener;
        LOG.log(Level.FINEST, "Statring LWRMulticast on local peer id :" + localPeerID);
        bound = true;
    }

    /**
     * Returns the binding state of the LWRMulticast.
     *
     * @return true if the LWRMulticast successfully bound to an address
     */
    public boolean isBound() {
        return bound;
    }

    /**
     * Closes this LWRMulticast.
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        bound = false;
        closed = true;
    }

    /**
     * {@inheritDoc}
     */
    public void receiveMessageEvent( MessageEvent event) throws MessageIOException {

        Message message = event.getMessage();
        if (message == null) {
            return;
        }

        Object element;
        PeerID<?> id = getSource(message);
        if (id != null && id.equals(localPeerID)) {
            //loop back
            return;
        }
        element = message.getMessageElement(ACKTAG);

        if (element instanceof Long) {
            processAck(id, (Long)element);
        } else {
            // does the message contain any data
            element = message.getMessageElement(SEQTAG);
            if (element instanceof Long) {
                ackMessage(id, (Long)element);
                try {
                    if (msgListener != null) {
                        if (LOG.isLoggable(Level.FINEST)) {
                            LOG.log(Level.FINEST, "Calling message listener");
                        }
                        msgListener.receiveMessageEvent(event);
                    }
                } catch (Throwable th) {
                    if (LOG.isLoggable(Level.FINEST)) {
                        LOG.log(Level.FINEST, "Exception occurred while calling message listener", th);
                    }
                }
            }
        }
    }

    public int getType() {
        return Message.TYPE_MCAST_MESSAGE;
    }

    /**
     * process an ack message
     *
     * @param id  source peer ID
     * @param seq message sequence number
     */
    private void processAck(PeerID<?> id, long seq) {
        LOG.log(Level.FINEST, "Processing ack for message sequence " + seq);
        if (!ackSet.contains(id)) {
            ackSet.add(id);
            if (ackSet.size() >= threshold) {
                synchronized (ackLock) {
                    //System.out.println("Received an ack in :" + (System.currentTimeMillis() - t0));
                    ackLock.notifyAll();
                }
            }
        }
    }

    /**
     * ack a message
     *
     * @param id  source peer ID
     * @param seq message sequence number
     */
    private void ackMessage(PeerID<?> id, long seq) {
        LOG.log(Level.FINEST, "Ack'ing message Sequence :" + seq);
        Message msg = new MessageImpl( Message.TYPE_MCAST_MESSAGE );
        msg.addMessageElement(SRCIDTAG, localPeerID);
        msg.addMessageElement(ACKTAG, seq);
        try {
            send(id, msg);
        } catch (IOException io) {
            if (LOG.isLoggable(Level.FINEST)) {
                LOG.log(Level.FINEST, "I/O Error occured " + io.toString());
            }
        }
    }

    /**
     * Returns a list of ack's received from nodes identified by  PeerID's
     *
     * @return a List of PeerID's
     */
    public Set<PeerID<?>> getAckList() {
        return ackList;
    }

    /**
     * Gets the Timeout attribute of the LWRMulticast
     *
     * @return The soTimeout value
     */
    public synchronized long getSoTimeout() {
        return timeout;
    }

    /**
     * Sets the Timeout attribute of the LWRMulticast
     * a timeout of 0 blocks forever, by default this channel's
     * timeout is set to 0
     *
     * @param timeout The new soTimeout value
     * @throws IOException if an I/O error occurs
     */
    public synchronized void setSoTimeout(long timeout) throws IOException {
        checkState();
        this.timeout = timeout + padding;
    }

    /**
     * Returns the closed state of the LWRMulticast.
     *
     * @return true if the channel has been closed
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Throws a IOException if closed or not bound
     *
     * @throws IOException if an I/O error occurs
     */
    private void checkState() throws IOException {
        if (isClosed()) {
            throw new IOException("LWRMulticast is closed");
        } else if (!isBound()) {
            throw new IOException("LWRMulticast not bound");
        }
    }

    /**
     * returns the source peer id of a message
     *
     * @param msg message
     * @return The source value
     */
    public static long getSequenceID( Message msg) {
        Object value = msg.getMessageElement(SEQTAG);
        if (value instanceof Long) {
            return (Long)value;
        }
        return -1;
    }

    /**
     * returns the source peer id of a message
     *
     * @param msg message
     * @return The source value
     */
    public static PeerID<?> getSource(Message msg) {
        PeerID<?> id = null;
        Object value = msg.getMessageElement(SRCIDTAG);
        if( value instanceof PeerID ) {
            id = (PeerID<?>)value;
        }
        return id;
    }

    /**
     * Send a message to the predefined set of nodes, and expect a minimum of specified acks.
     * <p/>
     * This method blocks until ack's upto to the specified threshold
     * have been received or the timeout has been reached.
     * A call to getAckList() returns a list of ack source peer ID's
     *
     * @param msg       the message to send
     * @param threshold the minimum of ack expected, 0 indicates none are expected
     * @throws IOException if an i/o error occurs, or SocketTimeoutException
     *                     if the threshold is not met within timeout
     */
    public void send(Message msg, int threshold) throws IOException {
        if (threshold < 0) {
            throw new IllegalArgumentException("Invalid threshold " + threshold + " must be >= 0");
        }
        this.threshold = threshold;
        msg.addMessageElement(SRCIDTAG, localPeerID);
        long seq = sequence.getAndIncrement();
        msg.addMessageElement(SEQTAG, seq);
        synchronized (ackLock) {
            ackList.clear();
            if (LOG.isLoggable(Level.FINEST)) {
                LOG.log(Level.FINEST, "Sending message sequence #: " + seq + " Threshold :" + threshold);
            }
            send((PeerID<?>) null, msg);
            if (threshold == 0) {
                return;
            }
            try {
                ackLock.wait(timeout);
                if (ackSet.size() >= threshold) {
                    ackList = new HashSet<PeerID<?>>(ackSet);
                    ackSet.clear();
                    return;
                }
            } catch (InterruptedException ie) {
                LOG.log(Level.FINEST, "Interrupted " + ie.toString());
            }
            ackList = new HashSet<PeerID<?>>(ackSet);
            ackSet.clear();

            if (ackList.size() < threshold) {
                throw new SocketTimeoutException("Failed to receive minimum acknowledments of " + threshold + " received :" + ackList.size());
            }
        }
    }

    /**
     * Send a message.
     *
     * @param pid destination PeerID
     * @param msg the message to send
     * @return boolean <code>true</code> if the message has been sent otherwise
     * <code>false</code>. <code>false</code>. is commonly returned for
     * non-error related congestion, meaning that you should be able to send
     * the message after waiting some amount of time.
     * @throws IOException if an i/o error occurs
     */
    public boolean send(PeerID<?> pid, Message msg) throws IOException {
        checkState();
        LOG.log(Level.FINEST, "Sending a message");
        if( pid != null ) {
            MessageSender sender = manager.getNetworkManager().getMessageSender( ShoalMessageSender.UDP_TRANSPORT );
            if( sender == null )
                throw new IOException( "message sender is null" );
            return sender.send( pid, msg );
        } else {
            // multicast
            MulticastMessageSender sender = manager.getNetworkManager().getMulticastMessageSender();
            if( sender == null )
                throw new IOException( "multicast sender is null" );
            return sender.broadcast( msg );
            //wait for ack's
        }
    }

    /**
     * Send a message to a set of peers
     *
     * @param ids destination PeerIDs
     * @param msg the message to send
     * @return boolean <code>true</code> if the message has been sent otherwise
     * <code>false</code>. <code>false</code>. is commonly returned for
     * non-error related congestion, meaning that you should be able to send
     * the message after waiting some amount of time.
     * @throws IOException if an i/o error occurs
     */
    public boolean send(Set<PeerID<?>> ids, Message msg) throws IOException {
        boolean sent = true;
        checkState();
        this.threshold = ids.size();
        ackList.clear();
        ackSet.clear();

        LOG.log(Level.FINEST, "Sending a message");
        if (!ids.isEmpty()) {
            // Unicast datagram
            MessageSender sender = manager.getNetworkManager().getMessageSender( ShoalMessageSender.UDP_TRANSPORT );
            if( sender == null )
                throw new IOException( "message sender is null" );
            for( PeerID<?> peerID: ids ) {
                if( !sender.send( peerID, msg ) )
                    sent = false;
            }
            if (!sent) {
                return sent;
            }
            synchronized (ackLock) {
                try {
                    ackLock.wait(timeout);
                    if (ackSet.size() >= threshold) {
                        ackList = new HashSet<PeerID<?>>(ackSet);
                        ackSet.clear();
                        return sent;
                    }
                } catch (InterruptedException ie) {
                    LOG.log(Level.FINEST, "Interrupted " + ie.toString());
                }
                if (ackSet.size() < threshold) {
                    ackList = new HashSet<PeerID<?>>(ackSet);
                    ackSet.clear();
                    throw new SocketTimeoutException("Failed to receive minimum acknowledments of " + threshold + " received :" + ackSet.size());
                }
            }
        }
         return sent;
    }
}

