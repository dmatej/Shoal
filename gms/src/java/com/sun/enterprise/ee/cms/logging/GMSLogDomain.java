/*
 * Copyright 2004-2005 Sun Microsystems, Inc.  All rights reserved.
 * Use is subject to license terms.
 */
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
package com.sun.enterprise.ee.cms.logging;


import java.util.logging.Logger;

/**
 * GMS logger that abstracts out application specific loggers. One can
 * plug in any logger here - even potentially set custom log handlers through
 * this abstraction.
 *
 * @author Shreedhar Ganapathy
 *         Date: Apr 1, 2004
 * @version $Revision$
 */
public class GMSLogDomain  {
    public static final String GMS_LOGGER = "ShoalLogger";
    public static final String RESOURCE_BUNDLE = "LogStrings";     
    public static final String LOG_STRINGS = "com.sun.enterprise.ee.cms.logging." + RESOURCE_BUNDLE;
    
    private static Logger gmsLogger = null;
    
    public static Logger getLogger(final String loggerName){
        if(gmsLogger == null ){
            gmsLogger =  Logger.getLogger( loggerName,  LOG_STRINGS);
        }
        return gmsLogger;
    }
}
