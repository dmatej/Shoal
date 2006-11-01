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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.*;
import java.util.logging.Formatter;

/**
 * NiceLogFormatter conforms to the logging format defined by the
 * Log Working Group in Java Webservices Org.
 * The specified format is
 * "[#|DATETIME|LOG_LEVEL|PRODUCT_ID|LOGGER NAME|OPTIONAL KEY VALUE PAIRS|
 * MESSAGE|#]\n"
 *
 * @author Shreedhar Ganapathy
 *         Date: Jun 6, 2006
 * @version $Revision$
 */
public class NiceLogFormatter extends Formatter {

    // loggerResourceBundleTable caches references to all the ResourceBundle
    // and can be searched using the LoggerName as the key
    private HashMap<String, ResourceBundle> loggerResourceBundleTable;
    private LogManager logManager;
    // A Dummy Container Date Object is used to format the date
    private Date date = new Date();
    private static boolean LOG_SOURCE_IN_KEY_VALUE = true;

    private static boolean RECORD_NUMBER_IN_KEY_VALUE = true;

    static {
        String logSource = System.getProperty(
                "com.sun.aas.logging.keyvalue.logsource");
        if ((logSource != null)
                && (logSource.equals("true"))) {
            LOG_SOURCE_IN_KEY_VALUE = true;
        }

        String recordCount = System.getProperty(
                "com.sun.aas.logging.keyvalue.recordnumber");
        if ((recordCount != null)
                && (recordCount.equals("true"))) {
            RECORD_NUMBER_IN_KEY_VALUE = true;
        }
    }

    private long recordNumber = 0;

    private static final String LINE_SEPARATOR =
            (String) java.security.AccessController.doPrivileged(
                    new sun.security.action.GetPropertyAction("line.separator"));

    private static final String RECORD_BEGIN_MARKER = "[#|";
    private static final String RECORD_END_MARKER = "|#]" + LINE_SEPARATOR +
            LINE_SEPARATOR;
    private static final char FIELD_SEPARATOR = '|';
    private static final char NVPAIR_SEPARATOR = ';';
    private static final char NV_SEPARATOR = '=';

    private static final String RFC_3339_DATE_FORMAT =
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ";

    private static final SimpleDateFormat dateFormatter =
            new SimpleDateFormat(RFC_3339_DATE_FORMAT);
    private static final String PRODUCT_VERSION = "JxtaMgmt";

    public NiceLogFormatter() {
        super();
        loggerResourceBundleTable = new HashMap<String, ResourceBundle>();
        logManager = LogManager.getLogManager();
    }


    public String format(LogRecord record) {
        return uniformLogFormat(record);
    }

    public String formatMessage(LogRecord record) {
        return uniformLogFormat(record);
    }


    /**
     * Sun One AppServer SE/EE can override to specify their product version
     */
    protected String getProductId() {
        return PRODUCT_VERSION;
    }


    /**
     * Sun One Appserver SE/EE? can override to specify their product specific
     * key value pairs.
     */
    protected void getNameValuePairs(StringBuilder buf, LogRecord record) {

        Object[] parameters = record.getParameters();
        if ((parameters == null) || (parameters.length == 0)) {
            return;
        }

        try {
            for (Object obj : parameters) {
                if (obj == null) {
                    continue;
                }
                if (obj instanceof Map) {
                    Map map = (Map) obj;
                    for (Object key : map.keySet()) {
                        buf.append(key.toString()).append(NV_SEPARATOR).
                                append(map.get(key).toString()).
                                append(NVPAIR_SEPARATOR);
                    }
                } else if (obj instanceof java.util.Collection) {
                    for (Object entry : ((Collection) obj)) {
                        buf.append(entry.toString()).append(NVPAIR_SEPARATOR);
                    }
                } else {
                    buf.append(obj.toString()).append(NVPAIR_SEPARATOR);
                }
            }
        } catch (Exception e) {
            new ErrorManager().error(
                    "Error in extracting Name Value Pairs", e,
                    ErrorManager.FORMAT_FAILURE);
        }
    }

    /**
     * Note: This method is not synchronized, we are assuming that the
     * synchronization will happen at the Log Handler.publish( ) method.
     */
    private String uniformLogFormat(LogRecord record) {

        try {

            StringBuilder recordBuffer = new StringBuilder(RECORD_BEGIN_MARKER);
            // The following operations are to format the date and time in a
            // human readable  format.
            // _REVISIT_: Use HiResolution timer to analyze the number of
            // Microseconds spent on formatting date object
            date.setTime(record.getMillis());
            recordBuffer.append(dateFormatter.format(date));
            recordBuffer.append(FIELD_SEPARATOR);

            recordBuffer.append(record.getLevel()).append(FIELD_SEPARATOR);
            recordBuffer.append(getProductId()).append(FIELD_SEPARATOR);
            recordBuffer.append(record.getLoggerName()).append(FIELD_SEPARATOR);

            recordBuffer.append("_ThreadID").append(NV_SEPARATOR);
            recordBuffer.append(record.getThreadID()).append(NVPAIR_SEPARATOR);

            recordBuffer.append("_ThreadName").append(NV_SEPARATOR);
            recordBuffer.append(Thread.currentThread().getName());
            recordBuffer.append(NVPAIR_SEPARATOR);

            // See 6316018. ClassName and MethodName information should be
            // included for FINER and FINEST log levels.
            Level level = record.getLevel();
            if (LOG_SOURCE_IN_KEY_VALUE ||
                    (level.intValue() <= Level.FINE.intValue())) {
                recordBuffer.append("ClassName").append(NV_SEPARATOR);
                recordBuffer.append(record.getSourceClassName());
                recordBuffer.append(NVPAIR_SEPARATOR);
                recordBuffer.append("MethodName").append(NV_SEPARATOR);
                recordBuffer.append(record.getSourceMethodName());
                recordBuffer.append(NVPAIR_SEPARATOR);
            }

            if (RECORD_NUMBER_IN_KEY_VALUE) {
                recordBuffer.append("RecordNumber").append(NV_SEPARATOR);
                recordBuffer.append(recordNumber++).append(NVPAIR_SEPARATOR);
            }
            getNameValuePairs(recordBuffer, record);
            recordBuffer.append(FIELD_SEPARATOR);

            String logMessage = record.getMessage();
            if (logMessage == null) {
                logMessage = "The log message is null.";
            }
            if (logMessage.indexOf("{0}") >= 0) {
                // If we find {0} or {1} etc., in the message, then it's most
                // likely finer level messages for Method Entry, Exit etc.,
                logMessage = java.text.MessageFormat.format(
                        logMessage, record.getParameters());
            } else {
                ResourceBundle rb = getResourceBundle(record.getLoggerName());
                if (rb != null) {
                    try {
                        logMessage = MessageFormat.format(
                                rb.getString(logMessage),
                                record.getParameters());
                    } catch (java.util.MissingResourceException e) {
                        // If we don't find an entry, then we are covered
                        // because the logMessage is intialized already
                    }
                }
            }
            recordBuffer.append(logMessage);

            if (record.getThrown() != null) {
                recordBuffer.append(LINE_SEPARATOR);
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                record.getThrown().printStackTrace(pw);
                pw.close();
                recordBuffer.append(sw.toString());
            }

            recordBuffer.append(RECORD_END_MARKER);
            return recordBuffer.toString();

        } catch (Exception ex) {
            new ErrorManager().error(
                    "Error in formatting Logrecord", ex,
                    ErrorManager.FORMAT_FAILURE);
            // We've already notified the exception, the following
            // return is to keep javac happy
            return new String("");
        }
    }

    private synchronized ResourceBundle getResourceBundle(String loggerName) {
        if (loggerName == null) {
            return null;
        }
        ResourceBundle rb = (ResourceBundle) loggerResourceBundleTable.get(
                loggerName);

        if (rb == null) {
            rb = logManager.getLogger(loggerName).getResourceBundle();
            loggerResourceBundleTable.put(loggerName, rb);
        }
        return rb;
    }
}
