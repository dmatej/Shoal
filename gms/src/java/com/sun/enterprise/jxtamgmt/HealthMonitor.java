/*
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License).  You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the license at
 * https://shoal.dev.java.net/public/CDDLv1.0.html
 *
 * See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * you own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 */
package com.sun.enterprise.jxtamgmt;

import net.jxta.document.AdvertisementFactory;
import net.jxta.document.MimeMediaType;
import net.jxta.document.StructuredDocumentFactory;
import net.jxta.document.StructuredTextDocument;
import net.jxta.document.XMLDocument;
import net.jxta.endpoint.Message;
import net.jxta.endpoint.MessageElement;
import net.jxta.endpoint.TextDocumentMessageElement;
import net.jxta.id.ID;
import net.jxta.peer.PeerID;
import net.jxta.pipe.InputPipe;
import net.jxta.pipe.OutputPipe;
import net.jxta.pipe.PipeMsgEvent;
import net.jxta.pipe.PipeMsgListener;
import net.jxta.pipe.PipeService;
import net.jxta.protocol.PipeAdvertisement;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HealthMonitor utilizes MasterNode to determine self designation. All nodes
 * cache other node's states, and can act as a master node at any given
 * point in time.  The intention behind the designation is that no node other than
 * the master node should determine collective state and communicate it to
 * group members.
 * <p/>
 * TODO: Convert the InDoubt Peer Determination and Failure Verification into
 * Callable FutureTask using java.util.concurrent
 */
public class HealthMonitor implements PipeMsgListener, Runnable {
    private static final Logger LOG = JxtaUtil.getLogger(HealthMonitor.class.getName());
    // Default health reporting period
    private long timeout = 10 * 1000L;
    private long verifyTimeout = 10 * 1000L;
    private int maxMissedBeats = 3;
    private final String indoubtListLock = new String("IndoubtListLock");
    private final String threadLock = new String("threadLock");
    private final Hashtable<PeerID, HealthMessage.Entry> cache = new Hashtable<PeerID, HealthMessage.Entry>();
    private MasterNode masterNode = null;
    private ClusterManager manager = null;
    private final PeerID localPeerID;
    InputPipe inputPipe = null;
    private OutputPipe outputPipe = null;
    private PipeAdvertisement pipeAdv = null;
    private final PipeService pipeService;
    private boolean started = false;
    private boolean stop = false;

    private Thread thread = null;
    private Thread fdThread = null;

    private InDoubtPeerDetector inDoubtPeerDetector;
    private final String[] states = {"starting",
            "started",
            "alive",
            "clusterstopping",
            "peerstopping",
            "stopped",
            "dead",
            "indoubt",
            "unknown"};
    private static final short ALIVE = 2;
    private static final short CLUSTERSTOPPING = 3;
    private static final short PEERSTOPPING = 4;
    private static final short STOPPED = 5;
    private static final short DEAD = 6;
    private static final short INDOUBT = 7;
    private static final short UNKNOWN = 8;
    private static final String HEALTHM = "HM";
    private static final String NAMESPACE = "HEALTH";
    private static final String cacheLock = "cacheLock";
    private static final String verifierLock = "verifierLock";

    private Message aliveMsg = null;
    private transient Map<ID, OutputPipe> pipeCache = new Hashtable<ID, OutputPipe>();

    //private ShutdownHook shutdownHook;

    /**
     * Constructor for the HealthMonitor object
     *
     * @param manager        the ClusterManager
     * @param maxMissedBeats Maximum retries before failure
     * @param verifyTimeout  timeout in milliseconds that the health monitor
     *                       waits before finalizing that the in doubt peer is dead.
     * @param timeout        in milliseconds that the health monitor waits before
     *                       retrying an indoubt peer's availability.
     */
    public HealthMonitor(final ClusterManager manager, final long timeout,
                         final int maxMissedBeats, final long verifyTimeout) {
        this.timeout = timeout;
        this.maxMissedBeats = maxMissedBeats;
        this.verifyTimeout = verifyTimeout;
        this.manager = manager;
        this.masterNode = manager.getMasterNode();
        this.localPeerID = manager.getNetPeerGroup().getPeerID();
        this.pipeService = manager.getNetPeerGroup().getPipeService();
        //this.shutdownHook = new ShutdownHook();
        //Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Creates a Message containing this node's state
     *
     * @param state member state
     * @return a Message containing this node's state
     */
    private Message createHealthMessage(final short state) {
        Message msg = createMessage(state, HEALTHM, manager.getSystemAdvertisement());
        masterNode.addRoute(msg);
        return msg;
    }

    private Message createMessage(final short state, final String tag,
                                  final SystemAdvertisement adv) {
        final Message msg = new Message();
        final HealthMessage hm = new HealthMessage();
        hm.setSrcID(localPeerID);
        final HealthMessage.Entry entry = new HealthMessage.Entry(adv, states[state]);
        hm.add(entry);
        msg.addMessageElement(NAMESPACE, new TextDocumentMessageElement(tag,
                (XMLDocument) hm.getDocument(MimeMediaType.XMLUTF8), null));
        return msg;
    }

    private Message getAliveMessage() {
        if (aliveMsg == null) {
            aliveMsg = createHealthMessage(ALIVE);
        }
        return aliveMsg;
    }

    /**
     * Given a pipeid it returns a HealthMonitor pipe advertisement of propagate type
     *
     * @return a HealthMonitor pipe advertisement of propagate type
     */
    private PipeAdvertisement createPipeAdv() {
        final PipeAdvertisement pipeAdv;
        // create the pipe advertisement, to be used in creating the pipe
        pipeAdv = (PipeAdvertisement)
                AdvertisementFactory.newAdvertisement(
                        PipeAdvertisement.getAdvertisementType());
        pipeAdv.setPipeID(manager.getNetworkManager().getHealthPipeID());
        pipeAdv.setType(PipeService.PropagateType);
        return pipeAdv;
    }

    /**
     * {@inheritDoc}
     */
    public void pipeMsgEvent(final PipeMsgEvent event) {
        //if this peer is stopping, then stop processing incoming health messages.
        if (manager.isStopping()) {
            return;
        }
        if (started) {
            final Message msg;
            MessageElement msgElement;
            try {
                // grab the message from the event
                msg = event.getMessage();
                if (msg != null) {
                    final Message.ElementIterator iter = msg.getMessageElements();
                    while (iter.hasNext()) {
                        msgElement = iter.next();
                        if (msgElement != null && msgElement.getElementName().equals(HEALTHM)) {
                            HealthMessage hm = getHealthMessage(msgElement);
                            if (!hm.getSrcID().equals(localPeerID)) {
                                masterNode.processRoute(msg);
                            }
                            process(hm);
                        }
                    }
                }
            } catch (IOException ex) {
                ex.printStackTrace();
                LOG.log(Level.WARNING, "HealthMonitor:Caught IOException : " + ex.getLocalizedMessage());
            } catch (Throwable e) {
                e.printStackTrace();
                LOG.log(Level.WARNING, e.getLocalizedMessage());
            }
        }
    }

    /**
     * process a health message received on the wire into cache
     *
     * @param hm Health message to process
     */
    private void process(final HealthMessage hm) {
        //LOG.log(Level.FINEST, "Processing Health Message..");
        //discard loopback messages
        if (!hm.getSrcID().equals(localPeerID)) {
            for (HealthMessage.Entry entry : hm.getEntries()) {
                cache.put(entry.id, entry);
                if (!manager.getClusterViewManager().containsKey(entry.id)) {
                    try {
                        masterNode.probeNode(entry);
                    } catch (IOException e) {
                        //ignored
                    }
                }
                if (entry.state.equals(states[PEERSTOPPING]) || entry.state.equals(states[CLUSTERSTOPPING])) {
                    handleStopEvent(entry);
                }
                if (entry.state.equals(states[INDOUBT]) || entry.state.equals(states[DEAD])) {
                    if (entry.id.equals(localPeerID)) {
                        reportMyState(ALIVE, hm.getSrcID());
                    } else {
                        if (entry.state.equals(states[INDOUBT])) {
                            LOG.log(Level.FINE, "Peer " + entry.id.toString() + " is suspected failed. Its state is " + entry.state);
                            notifyLocalListeners(entry.state, entry.adv);
                        }
                        if (entry.state.equals(states[DEAD])) {
                            LOG.log(Level.FINE, "Peer " + entry.id.toString() + " has failed. Its state is " + entry.state);
                        }
                    }
                } else {
                    //TODO: send an Add Event here (or a NoLongerInDoubt event) as clients need to know that
                    //TODO: the peer is not suspected anymore
                }
            }
        }
    }

    private void handleStopEvent(final HealthMessage.Entry entry) {
        LOG.log(Level.FINEST, MessageFormat.format("Handling Stop Event for peer :{0}", entry.adv.getName()));
        short stateByte = PEERSTOPPING;
        if (entry.state.equals(states[CLUSTERSTOPPING])) {
            stateByte = CLUSTERSTOPPING;
        }
        if (entry.adv.getID().equals(masterNode.getMasterNodeID())) {
            //if masternode is resigning, remove master node from view and start discovery
            LOG.log(Level.FINER, MessageFormat.format("Removing master node {0} from view as it has stopped.", entry.adv.getName()));
            removeMasterAdv(entry, stateByte);
            masterNode.resetMaster();
            masterNode.appointMasterNode();
        } else if (masterNode.isMaster() && masterNode.isMasterAssigned()) {
            removeMasterAdv(entry, stateByte);
            LOG.log(Level.FINE, "Announcing Peer Stop Event of " + entry.adv.getName() + " to group ...");
            final ClusterViewEvent cvEvent;
            if (entry.state.equals(states[CLUSTERSTOPPING])) {
                cvEvent = new ClusterViewEvent(ClusterViewEvents.CLUSTER_STOP_EVENT, entry.adv);
            } else {
                cvEvent = new ClusterViewEvent(ClusterViewEvents.PEER_STOP_EVENT, entry.adv);
            }
            masterNode.viewChanged(cvEvent);
        }
    }

    private Map<PeerID, HealthMessage.Entry> getCacheCopy() {
        return (Map<PeerID, HealthMessage.Entry>) cache.clone();
    }

    /**
     * Reports on the wire the specified state
     *
     * @param state specified state can be
     *              ALIVE|SLEEP|HIBERNATING|SHUTDOWN|DEAD
     * @param id    destination node ID, if null broadcast to group
     */
    private void reportMyState(final short state, final PeerID id) {
        if (state == ALIVE) {
            send(id, getAliveMessage());
        } else {
            send(id, createHealthMessage(state));
        }
    }

    private void reportOtherPeerState(final short state, final SystemAdvertisement adv) {
        final Message msg = createMessage(state, HEALTHM, adv);
        LOG.log(Level.FINEST, MessageFormat.format("Reporting {0} health state as {1}", adv.getName(), states[state]));
        send(null, msg);
    }

    /**
     * Main processing method for the HealthMonitor object
     */
    public void run() {
        long actualto = timeout;
        try {
            reportMyState(ALIVE, null);
            //System.out.println("Running HealthMonitor Thread at interval :"+actualto);
            while (!stop) {
                synchronized (threadLock) {
                    threadLock.wait(actualto);
                }
                if (actualto < timeout) {
                    //reset timeout back in case we lose master designation
                    actualto = timeout;
                    //System.out.println("Resetting actualto :"+actualto+"  to timeout :"+timeout);
                }
                reportMyState(ALIVE, null);
            }
        } catch (InterruptedException e) {
            //ignore as this happens on shutdown.
            //TODO: handle shutdown more gracefully
        } catch (Throwable all) {
            LOG.log(Level.WARNING, "Uncaught Throwable in thread " + Thread.currentThread().getName() + ":" + all);
        } finally {
            thread = null;
        }
    }

    /**
     * Send a message to a specific node. In case the peerId is null the
     * message is multicast to the group
     *
     * @param peerid Peer ID to send massage to
     * @param msg    the message to send
     */
    private void send(final PeerID peerid, final Message msg) {
        try {
            if (peerid != null) {
                // Unicast datagram
                // create a op pipe to the destination peer
                LOG.log(Level.FINE, "Unicasting Message to :" + peerid.toString());
                OutputPipe output;
                if (!pipeCache.containsKey(peerid)) {
                    // Unicast datagram
                    // create a op pipe to the destination peer
                    output = pipeService.createOutputPipe(pipeAdv, Collections.singleton(peerid), 1);
                    pipeCache.put(peerid, output);
                } else {
                    output = pipeCache.get(peerid);
                    if (output.isClosed()) {
                        output = pipeService.createOutputPipe(pipeAdv, Collections.singleton(peerid), 1);
                        pipeCache.put(peerid, output);
                    }
                }
                output.send(msg);
            } else {
                outputPipe.send(msg);
            }
        } catch (IOException io) {
            LOG.log(Level.WARNING, "Failed to send message", io);
        }
    }

    /**
     * Creates both input/output pipes, and starts monitoring service
     */
    void start() {

        if (!started) {
            LOG.log(Level.FINE, "Starting HealthMonitor");
            try {
                // create the pipe advertisement, to be used in creating the pipe
                pipeAdv = createPipeAdv();
                // create input
                inputPipe = pipeService.createInputPipe(pipeAdv, this);
                // create output
                outputPipe = pipeService.createOutputPipe(pipeAdv, 1);

                this.thread = new Thread(this, "HealthMonitor");
                thread.start();
                inDoubtPeerDetector = new InDoubtPeerDetector();
                inDoubtPeerDetector.start();
                started = true;
            } catch (IOException ioe) {
                LOG.log(Level.WARNING, "Failed to create health monitoring pipe advertisement :" + ioe);
            }
        }
    }

    /**
     * Announces Stop event to all members indicating that this peer
     * will gracefully exit the group.
     * TODO: Make this a synchronous call or a simulated synchronous call so that responses from majority of members can be collected before returning from this method
     *
     * @param isClusterShutdown boolean value indicating whether this
     *                          announcement is in the context of a clusterwide shutdown or a shutdown of
     *                          this peer only.
     */
    void announceStop(final boolean isClusterShutdown) {
        //System.out.println("Announcing  Shutdown");
        LOG.log(Level.FINE, MessageFormat.format("Announcing stop event to group with clusterShutdown set to {0}", isClusterShutdown));
        if (isClusterShutdown) {
            reportMyState(CLUSTERSTOPPING, null);
        } else {
            reportMyState(PEERSTOPPING, null);
        }
    }

    /**
     * Stops this service
     */
    void stop() {
        reportMyState(STOPPED, null);
        LOG.log(Level.FINE, "Stopping HealthMonitor");
        stop = true;
        started = false;
        final Thread tmpThread = thread;
        thread = null;
        if (tmpThread != null) {
            tmpThread.interrupt();
        }
        inDoubtPeerDetector.stop();
        inputPipe.close();
        outputPipe.close();
        pipeCache.clear();
    }

    private static HealthMessage getHealthMessage(final MessageElement msgElement) throws IOException {
        final HealthMessage hm;
        hm = new HealthMessage(getStructuredDocument(msgElement));
        return hm;
    }

    private static StructuredTextDocument getStructuredDocument(
            final MessageElement msgElement) throws IOException {
        return (StructuredTextDocument) StructuredDocumentFactory.newStructuredDocument(MimeMediaType.XMLUTF8,
                msgElement.getStream());
    }


    private void notifyLocalListeners(final String state, final SystemAdvertisement adv) {
        if (state.equals(states[INDOUBT])) {
            manager.getClusterViewManager().setInDoubtPeerState(adv);
        } else if (state.equals(states[ALIVE])) {
            manager.getClusterViewManager().setPeerNoLongerInDoubtState(adv);
        } else if (state.equals(states[CLUSTERSTOPPING])) {
            manager.getClusterViewManager().setClusterStoppingState(adv);
        } else if (state.equals(states[PEERSTOPPING])) {
            manager.getClusterViewManager().setPeerStoppingState(adv);
        }
    }

    public String getState(final ID id) {
        HealthMessage.Entry entry;
        entry = cache.get(id);
        if (entry != null) {
            return entry.state;
        } else {
            return states[DEAD];
        }
    }

    /**
     * Detects suspected failures and then delegates final decision to
     * FailureVerifier
     *
     * @author : Shreedhar Ganapathy
     */
    private class InDoubtPeerDetector implements Runnable {
        private static final long buffer = 500;
        private final long maxTime = timeout + buffer;

        void start() {
            final Thread fdThread = new Thread(this, "InDoubtPeerDetector Thread");
            LOG.log(Level.FINE, "Starting InDoubtPeerDetector Thread");
            fdThread.start();
            FailureVerifier fverifier = new FailureVerifier();
            final Thread fvThread = new Thread(fverifier, "FailureVerifier Thread");
            LOG.log(Level.FINE, "Starting FailureVerifier Thread");
            fvThread.start();
        }

        void stop() {
            final Thread tmpThread = fdThread;
            fdThread = null;
            if (tmpThread != null) {
                tmpThread.interrupt();
            }
            synchronized (verifierLock) {
                verifierLock.notify();
            }
        }

        public void run() {
            while (!stop) {
                synchronized (cacheLock) {
                    try {
                        //System.out.println("InDoubtPeerDetector thread waiting for :"+timeout);
                        //wait for specified timeout or until woken up
                        cacheLock.wait(timeout);
                        //LOG.log(Level.FINEST, "Analyzing cache for health...");
                        //get the copy of the states cache
                        if (!manager.isStopping()) {
                            processCacheUpdate();
                        }
                    } catch (InterruptedException ex) {
                        LOG.log(Level.FINEST, ex.getLocalizedMessage());
                    }
                }
            }
        }

        /**
         * computes the number of heart beats missed based on an entry's timestamp
         *
         * @param entry the Health entry
         * @return the number heart beats missed
         */
        int computeMissedBeat(HealthMessage.Entry entry) {
            return (int) ((System.currentTimeMillis() - entry.timestamp) / timeout);
        }

        private void processCacheUpdate() {
            final Map<PeerID, HealthMessage.Entry> cacheCopy = getCacheCopy();
            //for each peer id
            for (HealthMessage.Entry entry : cacheCopy.values()) {
                if (entry.state.equals(states[ALIVE])) {
                    //if there is a record, then get the number of
                    //retries performed in an earlier iteration
                    try {
                        determineInDoubtPeers(entry);
                    } catch (NumberFormatException nfe) {
                        LOG.log(Level.WARNING, "Exception occurred during time stamp conversion : " + nfe.getLocalizedMessage());
                    }
                }
            }
        }

        private void determineInDoubtPeers(final HealthMessage.Entry entry) {

            //if current time exceeds the last state update
            //timestamp from this peer id, by more than the
            //the specified max timeout
            if (!stop) {
                LOG.log(Level.FINEST, "timeDiff > maxTime");
                if (computeMissedBeat(entry) > maxMissedBeats) {
                    if (canProcessInDoubt(entry)) {
                        LOG.log(Level.FINEST, "Designating InDoubtState");
                        designateInDoubtState(entry);
                        //delegate verification to Failure Verifier
                        LOG.log(Level.FINEST, "Notifying FailureVerifier");
                        synchronized (verifierLock) {
                            verifierLock.notify();
                        }
                    }
                } else {
                    //dont suspect self
                    if (!entry.id.equals(localPeerID)) {
                        if (canProcessInDoubt(entry)) {
                            LOG.log(Level.FINE, MessageFormat.format("For PID = {0}; last recorded heart-beat = {1}ms ago", entry.id, System.currentTimeMillis() - entry.timestamp));
                            LOG.log(Level.FINE, MessageFormat.format("For PID = {0}; heart-beat # {1} out of a max of {2}", entry.id, computeMissedBeat(entry), maxMissedBeats));
                        }
                    }
                }
            }
        }

        private boolean canProcessInDoubt(final HealthMessage.Entry entry) {
            boolean canProcessIndoubt = false;
            if (masterNode.getMasterNodeID().equals(entry.id)) {
                canProcessIndoubt = true;
            } else if (masterNode.isMaster()) {
                canProcessIndoubt = true;
            }
            return canProcessIndoubt;
        }

        private void designateInDoubtState(final HealthMessage.Entry entry) {

            entry.state = states[INDOUBT];
            cache.put(entry.id, entry);
            if (masterNode.isMaster()) {
                //do this only when masternode is not suspect.
                // When masternode is suspect, all members update their states
                // anyways so no need to report
                //Send group message announcing InDoubt State
                LOG.log(Level.FINE, "Sending INDOUBT state message about node ID: " + entry.id + " to the cluster...");
                reportOtherPeerState(INDOUBT, entry.adv);
            }
            notifyLocalListeners(entry.state, entry.adv);
        }
    }

    private class FailureVerifier implements Runnable {
        private final long buffer = 500;

        public void run() {
            while (!stop) {
                try {
                    synchronized (verifierLock) {
                        verifierLock.wait();
                        if (!stop) {
                            verify();
                        }
                    }
                } catch (InterruptedException ex) {
                    LOG.log(Level.FINEST, MessageFormat.format("Thread interrupted: {0}", ex.getLocalizedMessage()));
                }
            }
        }

        //TODO: Add a isPeerLive() public method that the verify method could call and other callers could use to determine liveness of a member actively.
        //TODO: Send direct message to the in doubt peer for verification.
        void verify() throws InterruptedException {
            //wait for the specified timeout for verification
            Thread.sleep(verifyTimeout + buffer);
            HealthMessage.Entry entry;
            synchronized (indoubtListLock) {
                for (HealthMessage.Entry entry1 : getCacheCopy().values()) {
                    entry = entry1;
                    if (entry.state.equals(states[ALIVE])) {
                        //FIXME  Is this really needed? commenting out for now
                        /// reportLiveStateToLocalListeners(entry);
                    } else {
                        assignAndReportFailure(entry);
                    }
                }
            }
        }

        private void reportLiveStateToLocalListeners(final HealthMessage.Entry entry) {
            //TODO: NOt YET IMPLEMENTED
        }
    }

    private void assignAndReportFailure(final HealthMessage.Entry entry) {
        if (entry != null) {
            entry.state = states[DEAD];
            cache.put(entry.id, entry);
            if (masterNode.isMaster()) {
                LOG.log(Level.FINE, MessageFormat.format("Reporting Failed Node {0}", entry.id.toString()));
                reportOtherPeerState(DEAD, entry.adv);
            }
            final boolean masterFailed = (masterNode.getMasterNodeID()).equals(entry.id);
            if (masterNode.isMaster() && masterNode.isMasterAssigned()) {
                LOG.log(Level.FINE, MessageFormat.format("Removing System Advertisement :{0}", entry.id.toString()));
                removeMasterAdv(entry, DEAD);
                LOG.log(Level.FINE, MessageFormat.format("Announcing Failure Event of {0} ...", entry.id));
                final ClusterViewEvent cvEvent = new ClusterViewEvent(ClusterViewEvents.FAILURE_EVENT, entry.adv);
                masterNode.viewChanged(cvEvent);
            } else if (masterFailed) {
                //remove the failed node
                LOG.log(Level.FINE, MessageFormat.format("Master Failed. Removing System Advertisement :{0}", entry.id.toString()));
                removeMasterAdv(entry, DEAD);
                //manager.getClusterViewManager().remove(entry.id);
                masterNode.resetMaster();
                masterNode.appointMasterNode();
            }
        }
    }

    private void removeMasterAdv(HealthMessage.Entry entry, short state) {
        manager.getClusterViewManager().remove(entry.id);
        if (entry.adv != null) {
            switch (state) {
                case DEAD:
                    manager.getClusterViewManager().notifyListeners(
                            new ClusterViewEvent(ClusterViewEvents.FAILURE_EVENT, entry.adv));
                    break;
                case PEERSTOPPING:
                    manager.getClusterViewManager().notifyListeners(
                            new ClusterViewEvent(ClusterViewEvents.PEER_STOP_EVENT, entry.adv));
                    break;
                case CLUSTERSTOPPING:
                    manager.getClusterViewManager().notifyListeners(
                            new ClusterViewEvent(ClusterViewEvents.CLUSTER_STOP_EVENT, entry.adv));
                    break;
                default:
                    LOG.log(Level.FINEST, MessageFormat.format("Invalid State for removing adv from view {0}", state));
            }
        } else {
            LOG.log(Level.WARNING, states[state] + " peer: " + entry.id + " does not exist in local ClusterView");
        }
    }
/*
    private void shutdown() {
    }
    private class ShutdownHook extends Thread {
        public void run() {
            shutdown();
        }
    }
    */
}
