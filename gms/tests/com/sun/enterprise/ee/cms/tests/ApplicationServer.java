/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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

package com.sun.enterprise.ee.cms.tests;

import com.sun.enterprise.ee.cms.core.CallBack;
import com.sun.enterprise.ee.cms.core.GMSConstants;
import com.sun.enterprise.ee.cms.core.GMSException;
import com.sun.enterprise.ee.cms.core.GMSFactory;
import com.sun.enterprise.ee.cms.core.GroupHandle;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.ee.cms.core.JoinedAndReadyNotificationSignal;
import com.sun.enterprise.ee.cms.core.ServiceProviderConfigurationKeys;
import com.sun.enterprise.ee.cms.core.Signal;
import com.sun.enterprise.ee.cms.impl.client.JoinNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.client.JoinedAndReadyNotificationActionFactoryImpl;
import com.sun.enterprise.ee.cms.impl.common.GroupManagementServiceImpl;
import com.sun.enterprise.ee.cms.logging.GMSLogDomain;
import com.sun.enterprise.ee.cms.spi.MemberStates;
import com.sun.enterprise.jxtamgmt.JxtaUtil;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is an example used to demonstrate the application layer that controls the
 * lifecycle of the GMS module. It also provides an example of the actions taken
 * in response to a recover call from the GMS layer.
 *
 * @author Shreedhar Ganapathy"
 * @version $Revision$
 */
public class ApplicationServer implements Runnable, CallBack {
    private static final Logger logger = GMSLogDomain.getLogger(GMSLogDomain.GMS_LOGGER);

    public GroupManagementService gms = null;
    private GMSClientService gcs1;
    private GMSClientService gcs2;
    private String serverName;
    private String groupName;
    final private GroupManagementService.MemberType memberType;
    private volatile boolean stopped = false;

    public ApplicationServer(final String serverName, final String groupName,
                             final GroupManagementService.MemberType memberType,
                             final Properties props) {
        this.serverName = serverName;
        this.groupName = groupName;
        this.memberType = memberType;
        GMSFactory.setGMSEnabledState(groupName, Boolean.TRUE);
        gms = (GroupManagementService) GMSFactory.startGMSModule(serverName, groupName, memberType, props);
        initClientServices(Boolean.valueOf(System.getProperty("MESSAGING_MODE", "true")));
    }

    private void initClientServices(boolean sendMessages) {
        gcs1 = new GMSClientService("EJBContainer", serverName, sendMessages);
        gcs2 = new GMSClientService("TransactionService", serverName, false);
    }

    /*    private static void setupLogHandler() {
          final ConsoleHandler consoleHandler = new ConsoleHandler();
          try {
              consoleHandler.setLevel(Level.ALL);
              consoleHandler.setFormatter(new NiceLogFormatter());
          } catch( SecurityException e ) {
              new ErrorManager().error(
                   "Exception caught in setting up ConsoleHandler ",
                   e, ErrorManager.GENERIC_FAILURE );
          }
          logger.addHandler(consoleHandler);
          logger.setUseParentHandlers(false);
          final String level = System.getProperty("LOG_LEVEL","FINEST");
          logger.setLevel(Level.parse(level));
      }
    */
    public void run() {
        startGMS();
        addMemberDetails();
        startClientServices();
        logger.log(Level.FINE,"reporting joined and ready state...");
        gms.reportJoinedAndReadyState(groupName);
        try {
            Thread.sleep(Long.parseLong(System.getProperty("LIFEINMILLIS", "15000")));
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage());
        }
        stopClientServices();
        stopGMS();
        System.exit(0);
    }

    private void addMemberDetails() {
        final Map<Object, Object> details = new Hashtable<Object, Object>();
        final ArrayList<ArrayList> ar1 = new ArrayList<ArrayList>();
        final ArrayList<String> ar2 = new ArrayList<String>();
        final int port = 3700;
        final int port1 = 3800;
        try {
            ar2.add(InetAddress.getLocalHost() + ":" + port);
            ar2.add(InetAddress.getLocalHost() + ":" + port1);
        }
        catch (UnknownHostException e) {
            logger.log(Level.WARNING, e.getLocalizedMessage());
        }
        ar1.add(ar2);
        details.put(GMSClientService.IIOP_MEMBER_DETAILS_KEY, ar1);
        try {
            ((GroupManagementServiceImpl) gms).setMemberDetails(serverName, details);
        }
        catch (GMSException e) {
            logger.log(Level.WARNING, e.getLocalizedMessage());
        }
    }

    public void startClientServices() {
        logger.log(Level.FINE, "ApplicationServer: Starting GMSClient");
        gcs1.start();
        gcs2.start();
    }

    public void startGMS() {
        logger.log(Level.FINE, "ApplicationServer: Starting GMS service");
        try{
            gms.addActionFactory(new JoinedAndReadyNotificationActionFactoryImpl(this));
            gms.addActionFactory(new JoinNotificationActionFactoryImpl(this));
            gms.join();
        } catch (GMSException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage());
        }
    }

    public void stopGMS() {
        logger.log(Level.FINE, "ApplicationServer: Stopping GMS service");
        gms.shutdown(GMSConstants.shutdownType.INSTANCE_SHUTDOWN);
    }

    private void stopClientServices() {
        logger.log(Level.FINE, "ApplicationServer: Stopping GMSClient");
        gcs1.stopClient();
        gcs2.stopClient();
        stopped = true;
    }

    public void sendMessage(final String message) {
        final GroupHandle gh = gms.getGroupHandle();
        try {
            gh.sendMessage(null, message.getBytes());
        } catch (GMSException e) {
            logger.log(Level.INFO, e.getLocalizedMessage());
        }
    }

    public void processNotification(Signal notification) {
        logger.fine("received a notification " + notification.getClass().getName());

        logger.info("processing notification " + notification.getClass().getName() + " for group " +
                notification.getGroupName() + " memberName=" + notification.getMemberToken());
        if (notification instanceof JoinedAndReadyNotificationSignal) {
            MemberStates state = gms.getGroupHandle().getMemberState(notification.getMemberToken());
            if (state != MemberStates.READY && state != MemberStates.ALIVEANDREADY) {
                logger.warning("incorrect memberstate inside of JoinedAndReadyNotification signal processing " +
                        " expected: READY or ALIVEANDREADY, actual value: " + state);
            } else {
                logger.info("getMemberState(" + notification.getMemberToken() + ")=" + state);
            }
        }

    }
    
    // simulate CLB polling getMemberState
    public class CLB implements Runnable {
        boolean getMemberState;
        long threshold;
        long timeout;
        
        public CLB(boolean getMemberState, long threshold, long timeout) {
            this.getMemberState = getMemberState;
            this.threshold = threshold;
            this.timeout = timeout;
        }
        
        private void getAllMemberStates() {
            long startTime = System.currentTimeMillis();
            List<String> members = gms.getGroupHandle().getCurrentCoreMembers();
            logger.info("Enter getAllMemberStates currentMembers=" + members.size() + " threshold(ms)=" + threshold +
                          " timeout(ms)=" + timeout);
            for (String member : members) {
                MemberStates state = gms.getGroupHandle().getMemberState(member, threshold, timeout);
                logger.info("getMemberState member=" + member + " state=" + state + 
                        " threshold=" + threshold + " timeout=" + timeout);
            }
            logger.info("exit getAllMemberStates()  elapsed time=" + (System.currentTimeMillis() - startTime) +
                    " ms " + "currentMembers#=" + members.size());
        }
        
        public void run() {
            while (getMemberState && !stopped) {
                getAllMemberStates();
                try { 
                    Thread.sleep(500);
                } catch (InterruptedException ie) {}
            }
        }
    }

    public static void main(final String[] args) {
        CLB clb = null;
        if (args.length > 0 && "--usage".equals(args[1])) {
            logger.log(Level.INFO, new StringBuffer().append("USAGE: java -DMEMBERTYPE <CORE|SPECTATOR|WATCHDOG>")
                    .append(" -DINSTANCEID=<instanceid>")
                    .append(" -DCLUSTERNAME=<clustername")
                    .append(" -DLIFEINMILLIS= <length of time for this demo")
                    .append(" -DMESSAGING_MODE=[true|false] ApplicationServer")
                    .append(" -DGETMEMBERSTATE=[true]")
                    .append(" -DGETMEMBERSTATE_THRESHOLD=[xxxx] ms")
                    .append(" -DGETMEMBERSTATE_TIMEOUT=[xxx] ms")
                    .append(" -DKILLINSTANCE=<anotherinstanceid>")
                    .toString());
        }
        JxtaUtil.setLogger(logger);
        JxtaUtil.setupLogHandler();
        final ApplicationServer applicationServer;
        final String MEMBERTYPE_STRING = System.getProperty("MEMBERTYPE", "CORE").toUpperCase();
        final GroupManagementService.MemberType memberType = GroupManagementService.MemberType.valueOf(MEMBERTYPE_STRING);
        
        Properties configProps = new Properties();
        configProps.put(ServiceProviderConfigurationKeys.MULTICASTADDRESS.toString(),
                                    System.getProperty("MULTICASTADDRESS", "229.9.1.1"));
        configProps.put(ServiceProviderConfigurationKeys.MULTICASTPORT.toString(), 2299);
        logger.fine("Is initial host="+System.getProperty("IS_INITIAL_HOST"));
        configProps.put(ServiceProviderConfigurationKeys.IS_BOOTSTRAPPING_NODE.toString(),
                System.getProperty("IS_INITIAL_HOST", "false"));
        if(System.getProperty("INITIAL_HOST_LIST") != null){
            configProps.put(ServiceProviderConfigurationKeys.VIRTUAL_MULTICAST_URI_LIST.toString(),
                System.getProperty("INITIAL_HOST_LIST"));
        }
        configProps.put(ServiceProviderConfigurationKeys.FAILURE_DETECTION_RETRIES.toString(), "2");
        //Uncomment this to receive loop back messages
        //configProps.put(ServiceProviderConfigurationKeys.LOOPBACK.toString(), "true");
        final String bindInterfaceAddress = System.getProperty("BIND_INTERFACE_ADDRESS");
        if(bindInterfaceAddress != null){
            configProps.put(ServiceProviderConfigurationKeys.BIND_INTERFACE_ADDRESS.toString(),bindInterfaceAddress );
        }

        applicationServer = new ApplicationServer(System.getProperty("INSTANCEID"), System.getProperty("CLUSTERNAME"), memberType, configProps);
        if ("true".equals(System.getProperty("GETMEMBERSTATE"))) {
            boolean getMemberState = true;
            String threshold = System.getProperty("GETMEMBERSTATE_THRESHOLD","3000");
            long getMemberStateThreshold = Long.parseLong(threshold);
            long getMemberStateTimeout = Long.parseLong(System.getProperty("GETMEMBERSTATE_TIMEOUT", "3000"));
            logger.fine("getMemberState=true threshold=" + getMemberStateThreshold + 
                    " timeout=" + getMemberStateTimeout);
            clb = applicationServer.new CLB(getMemberState, getMemberStateThreshold, getMemberStateTimeout);
        }
        final Thread appServThread = new Thread(applicationServer, "ApplicationServer");
        appServThread.start();
        try {
            if (clb != null && ! applicationServer.isWatchdog()){
                final Thread clbThread = new Thread(clb, "CLB");
                clbThread.start();
            }
            // developer level manual WATCHDOG test.
            // Start each of the following items in a different terminal window.
            // Fix permissions for shell scripts: chmod +x rungmsdemo.sh killmember.sh
            // 1. ./rungmsdemo.sh server cluster1 SPECTATOR 600000 FINE &> server.log 
            // 2. ./rungmsdemo.sh instance1 cluster1 CORE 600000 FINE
            // 3. ./rungmsdemo.sh instance10 cluster1 CORE 600000 FINE
            // 4. ./rungmsdemo.sh nodeagent cluster1 WATCHDOG 600000 FINE
            //
            // If WATCHDOG, then test reporting failure to cluster. 
            // kill instance10 15 seconds after starting nodeagent WATCHDOG.  
            // Broadcast failure and check server.log for
            // immediate FAILURE detection, not FAILURE detected by GMS heartbeat.
            // grep server.log for WATCHDOG to see watchdog notification time compared to FAILURE report.
            if (applicationServer.isWatchdog()) {
                try {
                    Thread.sleep(15000);
                    final String TOBEKILLED_MEMBER="instance10";

                    GroupHandle gh = applicationServer.gms.getGroupHandle();
                    Runtime.getRuntime().exec("./killmember.sh " + TOBEKILLED_MEMBER);
                    logger.info("killed member " + TOBEKILLED_MEMBER);
                    gh.announceWatchdogObservedFailure(TOBEKILLED_MEMBER);
                    logger.info("Killed instance10 and WATCHDOG notify group " + gh.toString() + " that instance10 has failed.");
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Unexpected exception while starting server instance for WATCHDOG to kill and report failed",
                            e);
                }
            }
            appServThread.join();
        } catch (InterruptedException e) {
            logger.log(Level.SEVERE, e.getLocalizedMessage());
        }
    }

    boolean isWatchdog() {
        return memberType == GroupManagementService.MemberType.WATCHDOG;
    }
}
