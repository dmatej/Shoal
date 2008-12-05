package com.sun.enterprise.ee.cms.tests.checkgroupshutdown;

import com.sun.enterprise.ee.cms.core.CallBack;
import com.sun.enterprise.ee.cms.core.GMSException;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.jxtamgmt.JxtaUtil;

import java.util.logging.Logger;
import java.util.logging.Level;
import com.sun.enterprise.ee.cms.core.*;
 import com.sun.enterprise.ee.cms.impl.client.*;
 import com.sun.enterprise.ee.cms.impl.common.GMSContextFactory;
 import com.sun.enterprise.jxtamgmt.JxtaUtil;

 import java.text.MessageFormat;
 import java.util.logging.Level;
 import java.util.logging.Logger;


/**
 * Created by IntelliJ IDEA.
 * User: sheetal
 * Date: Jan 31, 2008
 * Time: 1:22:36 PM
 * This test is for making sure that the API added to check if the
 * group is shutting down works fine.
 * start the test as follows in 2 terminals :
 * "sh runcheckgroupshutdown.sh DAS" and "sh runcheckgroupshutdown.sh C1"
 * DAS will send out the announceShutdown() message which will be received by C1
 * C1 will print out the value for gms.isGroupBeingShutdown(group) before and after the message is received from DAS
 * This way the above API can be tested. The value returned should be false before DAS announces the GMSMessage
 * for shutdown and the nti should become true before C1 shuts down.
 */
public class CheckIfGroupShuttingDownTest implements CallBack{

    final static Logger logger = Logger.getLogger("CheckIfGroupShuttingDownTest");
    final Object waitLock = new Object();
    final String group = "Group";

    public static void main(String[] args) {
        //JxtaUtil.setLogger(logger);
        //JxtaUtil.setupLogHandler();
        CheckIfGroupShuttingDownTest check = new CheckIfGroupShuttingDownTest();
        String serverName = System.getProperty("TYPE");
        try {
            check.runSimpleSample(serverName);
        } catch (GMSException e) {
            logger.log(Level.SEVERE, "Exception occured while joining group:" + e);
        }
    }

    /**
     * Runs this sample
     * @throws GMSException
     */
    private void runSimpleSample(String serverName) throws GMSException {
        logger.log(Level.INFO, "Starting CheckIfGroupShuttingDownTest....");

        //initialize Group Management Service
        GroupManagementService gms = initializeGMS(serverName, group);

        //register for Group Events
        registerForGroupEvents(gms);
        //join group
        joinGMSGroup(group, gms);
        
        if (serverName.equals("C1"))
              logger.info("SHUTDOWN : Is the group shutting down ? : " + gms.isGroupBeingShutdown(group));

        try {
            waitForShutdown(10000);
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, e.getMessage());
        }


        if (serverName.equals("DAS")) {
            GMSContextFactory.getGMSContext(group).announceGroupShutdown(group, GMSConstants.shutdownState.COMPLETED);
        }

        try {
            waitForShutdown(20000);
        } catch (InterruptedException e) {
            logger.log(Level.WARNING, e.getMessage());
        }
        
        if (serverName.equals("C1"))
            logger.info("SHUTDOWN : Now is the group shutting down ? : " + gms.isGroupBeingShutdown(group));

        leaveGroupAndShutdown(serverName, gms);


        if (serverName.equals("C1"))
            logger.info("After leaveGroupAndShutdown : Now is the group shutting down ? : " + gms.isGroupBeingShutdown(group));


        System.exit(0);
    }

    private GroupManagementService initializeGMS(String serverName, String groupName) {
         logger.log(Level.INFO, "Initializing Shoal for member: "+serverName+" group:"+groupName);
         return (GroupManagementService) GMSFactory.startGMSModule(serverName,
                 groupName, GroupManagementService.MemberType.CORE, null);
     }

     private void registerForGroupEvents(GroupManagementService gms) {
         logger.log(Level.INFO, "Registering for group event notifications");
         gms.addActionFactory(new JoinNotificationActionFactoryImpl(this));
         gms.addActionFactory(new FailureSuspectedActionFactoryImpl(this));
         gms.addActionFactory(new FailureNotificationActionFactoryImpl(this));
         gms.addActionFactory(new PlannedShutdownActionFactoryImpl(this));
         gms.addActionFactory(new JoinedAndReadyNotificationActionFactoryImpl(this));
     }

     private void joinGMSGroup(String groupName, GroupManagementService gms) throws GMSException {
         logger.log(Level.INFO, "Joining Group "+groupName);
         gms.join();
     }

        private void waitForShutdown(int time) throws InterruptedException {
        logger.log(Level.INFO, "waiting for " + time + " ms");
        synchronized(waitLock){
            waitLock.wait(time);
        }
    }

    private void leaveGroupAndShutdown(String serverName, GroupManagementService gms) {
        logger.log(Level.INFO, "Shutting down gms " + gms + "for server " + serverName);
        gms.shutdown(GMSConstants.shutdownType.GROUP_SHUTDOWN);
    }

    public void processNotification(Signal notification) {
        logger.info("calling processNotification()...");
        //To change body of implemented methods use File | Settings | File Templates.
    }
}
