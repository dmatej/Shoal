/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 1997-2012 Oracle and/or its affiliates. All rights reserved.
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

package org.shoal.ha.mapper;

import org.glassfish.ha.store.api.HashableKey;
import org.shoal.ha.cache.api.ShoalCacheLoggerConstants;
import org.shoal.ha.group.GroupMemberEventListener;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Mahesh Kannan
 */
public class DefaultKeyMapper
        implements KeyMapper, GroupMemberEventListener {

    Logger _logger = Logger.getLogger(ShoalCacheLoggerConstants.CACHE_KEY_MAPPER);

    private String myName;

    private String groupName;

    private ReentrantReadWriteLock.ReadLock rLock;

    private ReentrantReadWriteLock.WriteLock wLock;

    private volatile String[] members = new String[0];

    private volatile String[] previuousAliveAndReadyMembers = new String[0];

    private volatile String[] replicaChoices = new String[0];

    private static final String _EMPTY_REPLICAS = "";


    public DefaultKeyMapper(String myName, String groupName) {
        this.myName = myName;
        this.groupName = groupName;
        ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
        
        rLock = rwLock.readLock();
        wLock = rwLock.writeLock();

        _logger.log(Level.FINE, "DefaultKeyMapper created for: myName: " + myName + "; groupName: " + groupName);
    }

    protected ReentrantReadWriteLock.ReadLock getReadLock() {
        return rLock;
    }

    protected ReentrantReadWriteLock.WriteLock getWriteLock() {
        return wLock;
    }

    protected String[] getMembers() {
        return members;
    }

    @Override
    public String getMappedInstance(String groupName, Object key1) {
        int hc = key1.hashCode();
        if (key1 instanceof HashableKey) {
            HashableKey k = (HashableKey) key1;
            hc = k.getHashKey() == null ? hc : k.getHashKey().hashCode();
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, "DefaultKeyMapper.getMappedInstance got a HashableKey "
                        + " key = " + key1 + "; key.hc = " + key1.hashCode()
                        + "; key.getHashKey() = " + ((HashableKey) key1).getHashKey() + "; key.getHashKey().hc = "
                        + (k.getHashKey() == null ? "-" : hc)
                        + "; Final hc = " + hc
                );
            }
        }

        try {
            rLock.lock();
            return members.length == 0
                    ? null
                    : members[Math.abs(hc % members.length)];
        } finally {
            rLock.unlock();
        }
    }

    @Override
    public String getReplicaChoices(String groupName, Object key) {
        int hc = getHashCodeForKey(key);
        try {
            rLock.lock();
            return members.length == 0
                    ? _EMPTY_REPLICAS
                    : replicaChoices[Math.abs(hc % members.length)];
        } finally {
            rLock.unlock();
        }
    }

    @Override
    public String[] getCurrentMembers() {
        return members;
    }
/*
    @Override
    public String[] getKeyMappingInfo(String groupName, Object key1) {
        int hc = key1.hashCode();
        if (key1 instanceof HashableKey) {
            HashableKey k = (HashableKey) key1;
            hc = k.getHashKey() == null ? hc : k.getHashKey().hashCode();
        }
        hc = Math.abs(hc);

        try {
            rLock.lock();
            return getKeyMappingInfo(members, hc);
        } finally {
            rLock.unlock();
        }
    }

    protected String[] getKeyMappingInfo(String[] instances, int hc) {
        if (members.length == 0) {
            return _EMPTY_TARGETS;
        } else if (members.length == 1) {
            return new String[] {members[0], null};
        } else {
            int index = hc % members.length;
            return new String[] {members[index], members[(index + 1) % members.length]};
        }
    }
    */
    
    @Override
    public String[] findReplicaInstance(String groupName, Object key1, String keyMappingInfo) {
        if (keyMappingInfo != null) {
            return keyMappingInfo.split(":");
        } else {

            int hc = key1.hashCode();
            if (key1 instanceof HashableKey) {
                HashableKey k = (HashableKey) key1;
                hc = k.getHashKey() == null ? hc : k.getHashKey().hashCode();
            }

            try {
                rLock.lock();
                return previuousAliveAndReadyMembers.length == 0
                        ? new String[] {_EMPTY_REPLICAS}
                        : new String[] {previuousAliveAndReadyMembers[Math.abs(hc % previuousAliveAndReadyMembers.length)]};
            } finally {
                rLock.unlock();
            }
        }
    }

    @Override
    public void onViewChange(String memberName,
                             Collection<String> readOnlyCurrentAliveAndReadyMembers,
                             Collection<String> readOnlyPreviousAliveAndReadyMembers,
                             boolean isJoinEvent) {
        try {
            wLock.lock();


            TreeSet<String> currentMemberSet = new TreeSet<String>();
            currentMemberSet.addAll(readOnlyCurrentAliveAndReadyMembers);
            currentMemberSet.remove(myName);
            members = currentMemberSet.toArray(new String[0]);

            int memSz = members.length;
            this.replicaChoices = new String[memSz];
            
            if (memSz == 0) {
                this.replicaChoices = new String[] {_EMPTY_REPLICAS};
            } else {
                for (int i=0; i<memSz; i++) {
                    StringBuilder sb = new StringBuilder();
                    int index = i;
                    String delim = "";
                    int choiceLimit = 1;
                    for (int j=0; j<memSz && choiceLimit-- > 0; j++) {
                        sb.append(delim).append(members[index++ % memSz]);
                        delim = ":";
                    }
                    
                    replicaChoices[i] = sb.toString();
                }
            }

            TreeSet<String> previousView = new TreeSet<String>();
            previousView.addAll(readOnlyPreviousAliveAndReadyMembers);
            if (! isJoinEvent) {
                previousView.remove(memberName);
            }
            previuousAliveAndReadyMembers = previousView.toArray(new String[0]);

            if (_logger.isLoggable(Level.FINE)) {
                printMemberStates("onViewChange (isJoin: " + isJoinEvent + ")");
            }
        } finally {
            wLock.unlock();
        }
    }

    private int getHashCodeForKey(Object key1) {
        int hc = key1.hashCode();
        if (key1 instanceof HashableKey) {
            HashableKey k = (HashableKey) key1;
            hc = k.getHashKey() == null ? hc : k.getHashKey().hashCode();
        }

        return hc;
    }

    private static int getDigestHashCode(String val) {
        int hc = val.hashCode();
        try {
            String hcStr = "_" + val.hashCode() + "_";
            MessageDigest dig = MessageDigest.getInstance("MD5");
            dig.update(hcStr.getBytes(Charset.defaultCharset()));
            dig.update(val.getBytes(Charset.defaultCharset()));
            dig.update(hcStr.getBytes(Charset.defaultCharset()));
            BigInteger bi = new BigInteger(dig.digest());
            hc = bi.intValue();
            return hc;
        } catch (NoSuchAlgorithmException nsaEx) {
            hc = val.hashCode();
        }

        return hc;
    }

    public void printMemberStates(String message) {
        StringBuilder sb = new StringBuilder("DefaultKeyMapper[" + myName + "]." + message + " currentView: ");
        String delim = "";
        for (String st : members) {
            sb.append(delim).append(st);
            delim = " : ";
        }
        sb.append("; previousView ");

        delim = "";
        for (String st : previuousAliveAndReadyMembers) {
            sb.append(delim).append(st);
            delim = " : ";
        }

        sb.append("\n");
        int memSz = members.length;
        for (int i=0; i<memSz; i++) {
            sb.append("\tReplicaChoices[").append(members[i]).append("]: ").append(replicaChoices[i]);
            sb.append("\n");
        }
        _logger.log(Level.FINE, sb.toString());
    }

}
