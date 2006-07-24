/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/


package org.apache.james.jspf;

import org.apache.james.jspf.core.DNSService;
import org.apache.james.jspf.core.Directive;
import org.apache.james.jspf.core.Modifier;
import org.apache.james.jspf.core.SPF1Constants;
import org.apache.james.jspf.core.SPF1Data;
import org.apache.james.jspf.core.SPF1Record;
import org.apache.james.jspf.exceptions.NoneException;
import org.apache.james.jspf.exceptions.PermErrorException;
import org.apache.james.jspf.exceptions.TempErrorException;
import org.apache.james.jspf.macro.MacroExpand;
import org.apache.james.jspf.parser.SPF1Parser;
import org.apache.log4j.Logger;

import java.util.Iterator;

/**
 * This class is used to generate a SPF-Test and provided all intressting data.
 * 
 */

public class SPF {

    private DNSService dnsProbe = null;

    private SPF1Parser parser;

    private static Logger log = Logger.getLogger(SPF.class);

    /**
     * 
     */
    public SPF() {
        this(new DNSServiceXBillImpl());

    }

    /**
     * @param dnsProbe
     *            the dns provider
     */
    public SPF(DNSService dnsProbe) {
        super();
        this.dnsProbe = dnsProbe;
        this.parser = new SPF1Parser();
    }

    /**
     * Run check for SPF with the given values.
     * 
     * @param ipAddress
     *            The ipAddress the connection is comming from
     * @param mailFrom
     *            The mailFrom which was provided
     * @param hostName
     *            The hostname which was provided as HELO/EHLO
     * @return result. Possible results are: pass, neutral, fail,
     *         softfail, error,temperror, none
     */
    public SPFResult checkSPF(String ipAddress, String mailFrom, String hostName) {
        SPF1Data spfData = null;
        String result = null;
        String resultChar = null;
        String explanation = null;

        try {
            // Setup the data
            spfData = new SPF1Data(mailFrom, hostName, ipAddress, dnsProbe);
            SPFInternalResult res = checkSPF(spfData);
            resultChar = res.getResultChar();
            result = SPF1Utils.resultToName(resultChar);
            explanation = res.getExplanation();
        } catch (PermErrorException e) {
            log.warn(e.getMessage());
            result = SPF1Utils.PERM_ERROR;
        } catch (NoneException e) {
            log.warn(e.getMessage());
            result = SPF1Utils.NONE;
        } catch (TempErrorException e) {
            log.warn(e.getMessage());
            result = SPF1Utils.TEMP_ERROR;
        } catch (IllegalStateException e) {
            // this should never happen at all. But anyway we will set the
            // result to neutral. Safety first ..
            log.error(e.getMessage());
            result = SPF1Constants.NEUTRAL;
        }

        SPFResult ret = new SPFResult(result, resultChar, explanation, spfData);
        
        log.info("[ipAddress=" + ipAddress + "] [mailFrom=" + mailFrom
                + "] [helo=" + hostName + "] => " + ret.getResult());

        return ret;

    }

    /**
     * Run check for SPF with the given values.
     * 
     * @param spfData
     *            The SPF1Data which should be used to run the check
     * @throws PermErrorException
     *             Get thrown if an error was detected
     * @throws NoneException
     *             Get thrown if no Record was found
     * @throws TempErrorException
     *             Get thrown if a DNS problem was detected
     */
    public SPFInternalResult checkSPF(SPF1Data spfData) throws PermErrorException,
            NoneException, TempErrorException {
        String result = SPF1Constants.NEUTRAL;
        String explanation = null;

        /**
         * Check if the connection was made from localhost. Set the result to
         * PASS if its from localhost.
         */
        if (spfData.getIpAddress().trim().startsWith("127.")) {
            // logging
            log.info("Connection was made from localhost => skip checking");

            result = SPF1Constants.PASS;
            return new SPFInternalResult(result, explanation);
        }

        // Get the raw dns txt entry which contains a spf entry
        String spfDnsEntry = dnsProbe.getSpfRecord(spfData.getCurrentDomain(),
                SPF1Constants.SPF_VERSION);

        // logging
        log.debug("Start parsing SPF-Record:" + spfDnsEntry);

        SPF1Record spfRecord = parser.parse(spfDnsEntry);

        // System.out.println(spfRecord);

        String qualifier = null;
        boolean hasCommand = false;

        // get all commands
        Iterator com = spfRecord.getDirectives().iterator();
        while (com.hasNext()) {

            // if we reach maximum calls we must throw a PermErrorException. See
            // SPF-RFC Section 10.1. Processing Limits
            if (spfData.getCurrentDepth() > spfData.getMaxDepth()) {
                throw new PermErrorException(
                        "Maximum mechanism/modifier calls done: "
                                + spfData.getCurrentDepth());
            }

            hasCommand = true;
            Directive d = (Directive) com.next();

            // logging
            log.debug("Processing directive: " + d.getQualifier()
                    + d.getMechanism().toString());

            qualifier = d.run(spfData);

            // logging
            log.debug("Processed directive: " + d.getQualifier()
                    + d.getMechanism().toString() + " returned " + qualifier);

            if (qualifier != null) {
                if (qualifier.equals("")) {
                    result = SPF1Constants.PASS;
                } else {
                    result = qualifier;
                }

                spfData.setCurrentResult(result);
                spfData.setMatch(true);

                // If we have a match we should break the while loop
                break;
            }
        }

        Iterator mod = spfRecord.getModifiers().iterator();
        while (mod.hasNext()) {
            spfData.setCurrentDepth(spfData.getCurrentDepth() + 1);

            // if we reach maximum calls we must throw a PermErrorException. See
            // SPF-RFC Section 10.1. Processing Limits
            if (spfData.getCurrentDepth() > spfData.getMaxDepth()) {
                throw new PermErrorException(
                        "Maximum mechanism/modifiers calls done: "
                                + spfData.getCurrentDepth());
            }

            Modifier m = (Modifier) mod.next();

            log.debug("Processing modifier: " + m.toString());

            String q = m.run(spfData);

            log.debug("Processed modifier: " + m.toString() + " resulted in "
                    + q);

            if (q != null) {
                qualifier = q;
            }

            if (qualifier != null) {
                result = qualifier;

                spfData.setCurrentResult(result);
                spfData.setMatch(true);
            }
        }

        // If no match was found set the result to neutral
        if (!spfData.isMatch() && (hasCommand == true)) {
            result = SPF1Constants.NEUTRAL;
        } 
        
        if (result.equals(SPF1Constants.FAIL)) {  
            if (spfData.getExplanation()==null || spfData.getExplanation().equals("")) {
                try {
                    spfData.setExplanation(new MacroExpand(spfData)
                            .expandExplanation(SPF1Utils.DEFAULT_EXPLANATION));
                } catch (PermErrorException e) {}
            }
            explanation = spfData.getExplanation();
        }
        
        return new SPFInternalResult(result, explanation);
    }

    /**
     * Set the amount of time (in seconds) before an TermError is returned when
     * the dnsserver not answer. Default is 20 seconds.
     * 
     * TempError should be returned
     * 
     * @param timeOut
     *            The timout in seconds
     */
    public synchronized void setTimeOut(int timeOut) {
        log.debug("TimeOut was set to: " + timeOut);
        dnsProbe.setTimeOut(timeOut);
    }
}
