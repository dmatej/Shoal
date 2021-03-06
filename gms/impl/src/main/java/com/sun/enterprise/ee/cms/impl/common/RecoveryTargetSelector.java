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

package com.sun.enterprise.ee.cms.impl.common;

import com.sun.enterprise.ee.cms.core.*;
import com.sun.enterprise.ee.cms.logging.GMSLogDomain;
import com.sun.enterprise.ee.cms.core.GMSMember;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Uses a specified algorithm to determine a member that will be selected
 * to handle recovery operations on failure of a member. The algorithms are
 * specified through a RecoverySelectorMode object that specifies typ safe enums
 * such as SIMPLESELECT indicating a simple ordered selection algorithm and
 * HOSTSELECT indicating a selection algorithm that ensures that the recovery
 * target is always on a different host from a single host.
 *
 * @author Shreedhar Ganapathy
 * Date: Jan 20, 2004
 * @version $Revision$
 */
public class RecoveryTargetSelector {
    static final String CORETYPE = GroupManagementService.MemberType.CORE.toString();
    private static final Logger logger = GMSLogDomain.getLogger( GMSLogDomain.GMS_LOGGER);

    private RecoveryTargetSelector () {
    }

    public static enum RecoverySelectorMode {SIMPLESELECT, HOSTSELECT, FIRSTLIVESELECT}

    final static RecoverySelectorMode DEFAULT_MODE = RecoverySelectorMode.SIMPLESELECT;

    /**
     * Uses a resolution algorithm to determine whether the member this process
     * represents is indeed responsible for performing recovery operations.
     * @param mode - as specified by the RecoverySelectorMode
     * @param members - a vector of members from a view that existed prior to
     * the failure. Ideally, this view contains the failedMemberToken.
     * @param failedMemberToken - failed member's identity token
     * @param ctx - The GMSContext pertaining to the failed member;s group.
     * @return boolean
     */
    public static boolean resolveRecoveryTarget(RecoverySelectorMode mode,
                                         final List<GMSMember> members,
                                         final String failedMemberToken,
                                         final GMSContext ctx)
    {
        boolean recoveryServer = false;
        final String groupName = ctx.getGroupName();

        // existing code passes in null to indicate to use a default algorithm.
        if (mode == null) {
            // default mode
            mode = DEFAULT_MODE;
        }
        switch (mode) {
            case SIMPLESELECT:
                recoveryServer = resolveWithSimpleSelectionAlgorithm(members, failedMemberToken, groupName);
                break;
            case FIRSTLIVESELECT:
                recoveryServer = resolveWithEasySelectionAlgorithm(members, failedMemberToken, groupName);
                break;
            case HOSTSELECT:
                recoveryServer = resolveWithHostSelectionAlgorithm(members, failedMemberToken,groupName);
                break;
            default:
                logger.log(Level.WARNING, "recovery.selector.invalid.mode",
                        new Object[]{mode.toString() ,failedMemberToken, groupName});
                break;
        }
        return recoveryServer;
    }

    /**
     * Ensures that the selected recovery server is located on a machine
     * that is different from the one on which the failed member process was
     * running. This algorithm takes care of some of the risks such as
     * resources risk, cascading failure risk, etc associated with
     * selecting recovery targets on the same machine as the one on which failed
     * process was located.
     * If there are no members on other hosts (ex. Group of only two members
     * running only on one host), then the selection algorithm switches to the
     * simple algorithm mode.
     * @param members
     * @param failedMember
     * @return boolean
     */
    private static boolean resolveWithHostSelectionAlgorithm(
            final List<GMSMember> members,
            final String failedMember,
            final String groupName)
    {
        return false;
    }

    /**
     * Uses the Chronological Successor algorithm to determine whether this
     * process(member) is selected to perform recovery.
     * The members from the ordered view prior to failure is already cached.
     * From this cache, we determine the first live member that stood
     * <bold>immediately after</bold> the failed member, or where the failed
     * member was last in this ordered list, determine the first live member in
     * the ordered list. In both such cases, recovery is determined to be
     * performed by this member.
     * @return boolean true if recovery is to be performed by this process,
     * false if not.
     */
    private static boolean resolveWithSimpleSelectionAlgorithm(
                                            final List<GMSMember> viewCache,
                                            final String failedMember,
                                            final String groupName)
    {
        boolean recover = false;
        String recoverer = null;
        final GMSContext ctx = GMSContextFactory.getGMSContext( groupName );
        final String self = ctx.getServerIdentityToken();
        final List<String> liveCache = getMemberTokens(viewCache,
                                                       ctx.getSuspectList(),
                                                       ctx.getGroupHandle().getAllCurrentMembers());
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "LiveCache = "+liveCache);
        }
        final List<String> vCache = getCoreMembers(viewCache);
        if (logger.isLoggable(Level.FINE)){
            logger.log(Level.FINE, "vCache = "+vCache);
        }

        for( int i=0; i<vCache.size(); i++ ) {
            final String member;
            //if current member in cached view is same as failed member
             if( vCache.get(i).equals(failedMember) ){
                //if this failed member is the last member
                if( i == ( vCache.size() - 1 ) ) {
                    member = vCache.get(0);
                    if (logger.isLoggable(Level.FINEST)){
                        logger.log(Level.FINEST,
                        "Failed Member was last member of the previous view, "+
                        "The first live core member will be selected as recoverer");
                        logger.log(Level.FINEST, "First Core Member is "+member);
                        logger.log(Level.FINEST, "Live members are :"+ liveCache.toString());
                    }
                    //if the first member of the view cache is a live member
                    if(liveCache.contains(member)){
                        recoverer = member;
                    }
                }
                //if this failed member is not the last member
                else {//get the rest of the members
                    final List<String> subset = vCache.subList( i+1,
                                                             vCache.size());
                    for(final String mem : subset){
                        //pick the first live member based on the subset
                        if(liveCache.contains( mem )){
                            recoverer = mem;
                            break;
                        }
                    }
                }

            }
        }
        if (recoverer == null) {
            // failed member might not have been in vCache, revert to selecting first live member in view
            logger.log(Level.WARNING, "recovery.selector.failed", new Object[]{failedMember, getMemberTokens(viewCache)});
            for (String coreMember : vCache) {
                if (liveCache.contains(coreMember)) {
                    recoverer = coreMember;
                    break;
                }
            }
        }
        if(recoverer != null) {
            // if I am (this process is) the recoverer, then I
            // select myself for recovery
            if(recoverer.equals(self)) {
                recover = true;
            }
            //this in effect will be set by every GMS instance
            // regardless of whether they are the recovery server.
            //this redundant action ensures that there is a group-wide
            //record of this selection
            setRecoverySelectionState(recoverer,
                    failedMember,
                    groupName);
        }
        return recover;
    }

    private static boolean resolveWithEasySelectionAlgorithm(final List<GMSMember> oldViewCache,
                                                             final String failedMember,
                                                             final String groupName) {
        boolean recover = false;
        String recoverer = null;
        final GMSContext ctx = GMSContextFactory.getGMSContext(groupName);
        final String self = ctx.getServerIdentityToken();
        final List<String> liveCache = getMemberTokens(oldViewCache, ctx.getSuspectList(), ctx.getGroupHandle().getAllCurrentMembers());
        logger.log(Level.FINE, "LiveCache = " + liveCache);
        final List<String> coreCache = getCoreMembers(oldViewCache);
        logger.log(Level.FINE, "CoreCache = " + coreCache);
        for (String coreMember : coreCache) {
            if (liveCache.contains(coreMember)) {
                recoverer = coreMember;
                break;
            }
        }
        if (recoverer != null) {
            if (recoverer.equals(self)) {
                recover = true;
            }
            setRecoverySelectionState(recoverer, failedMember, groupName);
        }
        return recover;
    }

    private static List<String> getCoreMembers (final List<GMSMember> viewCache)
    {
        final List<String> temp = new ArrayList<String>();
        for(final GMSMember member : viewCache){
            if(member.getMemberType().equals( CORETYPE )){
                temp.add( member.getMemberToken() );
            }
        }
        return temp;
    }

    public static void setRecoverySelectionState (
            final String recovererMemberToken,
            final String failedMemberToken,
            final String groupName)
    {
        logger.log(Level.INFO, "recovery.selector.appointed",
                new Object[]{recovererMemberToken, failedMemberToken, groupName});
        final GMSContext ctx = GMSContextFactory.getGMSContext( groupName );
        if (ctx.isWatchdog()) {
            return;
        }
        final DistributedStateCache dsc = ctx.getDistributedStateCache();
        final Hashtable<String,FailureRecoveryActionFactory> reg =
                        ctx.getRouter().getFailureRecoveryAFRegistrations();

        for(String component : reg.keySet())
        {
            try {
                dsc.addToCache(component,
                                recovererMemberToken,
                                failedMemberToken,
                                setStateAndTime()
                                );
            }
            catch ( GMSException e ) {
                logger.log(Level.WARNING, e.getLocalizedMessage(), e);
            }
        }
    }

    private static String setStateAndTime() {
        return GroupManagementService
                    .RECOVERY_STATE
                    .RECOVERY_SERVER_APPOINTED.toString() + '|' +
                System.currentTimeMillis();

    }

    private static List<String> getMemberTokens(final List<GMSMember> members,
        final List<String> exclusionList, final List<String> currentMembers)
    {
        final List<String> temp = new ArrayList<String>();
        String token;
        for(GMSMember member : members){
            token = member.getMemberToken();
            if(member.getMemberType().equals(CORETYPE) &&
               currentMembers.contains(token) &&
               !exclusionList.contains( token ))//only send in non excluded members
            {
                temp.add( token );
            }
        }
        if (logger.isLoggable(Level.FINEST)) {
            logger.log(Level.FINEST, "SuspectedMembers: "+exclusionList.toString());
            logger.log(Level.FINEST, "LiveMembers: "+temp.toString());
        }
        return temp;
    }

    private static String getMemberTokens(final List<GMSMember> members)
    {
        final StringBuffer temp = new StringBuffer();
        for(GMSMember member : members){
            if(member.getMemberType().equals(CORETYPE)) {
                temp.append(member.getMemberToken() + ":");
            }
        }
        return temp.toString();
    }
}
