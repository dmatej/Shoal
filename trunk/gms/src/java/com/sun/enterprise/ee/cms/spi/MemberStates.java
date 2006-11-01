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
 package com.sun.enterprise.ee.cms.spi;

/**
 * Represents the set of states that members in the group can be
 * as part of their existence in the group. These are states that are
 * relevant to GMS.
 *
 * @author Shreedhar Ganapathy
 *         Date: Oct 4, 2006
 * @version $Revision$
 */
public enum MemberStates {
    STARTING,
    STARTED,
    ALIVE,
    INDOUBT,
    DEAD,
    CLUSTERSTOPPING,
    PEERSTOPPING,
    STOPPED
}
