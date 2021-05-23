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

package org.apache.james.transport.mailets;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.internet.MimeMessage;

import org.apache.mailet.Mail;
import org.apache.mailet.base.GenericMailet;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;

import com.github.fge.lambdas.consumers.ThrowingConsumer;
import com.github.fge.lambdas.functions.intfunctions.ThrowingIntFunction;

import java.io.IOException;
import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * This mailet adds the de-facto standard QMail Delivered-To header.
 * <p>
 * Upon processing by LocalDelivery, a Delivered-To header matching the recipient mail address will be added before storage.
 *
 * <pre><code>
 * &lt;mailet match=&quot;All&quot; class=&quot;&lt;AddDeliveredToHeader&gt;&quot;/&gt;
 * </code></pre>
 */
public class SanitizeHtmlBodyParts extends GenericMailet {

    public static final String ATTRIBUTES_PROPERTY_INVALID = "Attributes not specified correctly for SanitizeHtmlBodyParts mailet";
    public static final String ENFORCED_ATTRIBUTES_PROPERTY_INVALID = "Enforced attributes not specified correctly for SanitizeHtmlBodyParts mailet";
    public static final String PROTOCOLS_PROPERTY_INVALID = "Protocols not specified correctly for SanitizeHtmlBodyParts mailet";

    public static final String REMOVE_PROTOCOLS_PROPERTY_INVALID = "Remove protocols not specified correctly for SanitizeHtmlBodyParts mailet";
    public static final String REMOVE_ENFORCED_ATTRIBUTES_PROPERTY_INVALID = "Remove enforced attributes not specified correctly for SanitizeHtmlBodyParts mailet";

    enum SanitizerLevel {

        BASIC(Whitelist.basic()),
        BASICWITHIMAGES(Whitelist.basicWithImages()),
        RELAXED(Whitelist.relaxed()),
        NONE(Whitelist.none()),
        SIMPLETEXT(Whitelist.simpleText());

        private Whitelist whitelist;

        SanitizerLevel(Whitelist whitelist) {
            this.whitelist = whitelist;
        }

        public Whitelist getWhitelist() {
            return whitelist;
        }
    }

    private Whitelist whitelist;

    @Override
    public void init() throws MessagingException {

        this.whitelist = getInitParameterAsOptional("level")
            .map(String::toUpperCase)
            .map(SanitizerLevel::valueOf)
            .map(SanitizerLevel::getWhitelist)
            .orElse(Whitelist.none());

        addRestrictions();
        removeRestrictions();

    }

    private void addRestrictions() {
        getValues("tags").forEach(whitelist::addTags);
        getValues("attributes")
            .map(attributes -> attributes.split(":"))
            .forEach(this::addAttributesToWhitelist);
        getValues("enforcedAttributes")
            .map(enforcedAttribute -> enforcedAttribute.split(":"))
            .forEach(this::addEnforcedttributesToWhitelist);
        getValues("protocols")
            .map(protocols -> protocols.split(":"))
            .forEach(this::addProtocolsToWhitelist);
    }

    private Whitelist addProtocolsToWhitelist(String[] protocolParams) {
        try {
            return whitelist.addProtocols(protocolParams[0], protocolParams[1],
                Arrays.copyOfRange(protocolParams, 2, protocolParams.length));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException(PROTOCOLS_PROPERTY_INVALID);
        }
    }

    private Whitelist addEnforcedttributesToWhitelist(String[] enforcedAttributeParams) {
        try {
            return whitelist
                .addEnforcedAttribute(enforcedAttributeParams[0], enforcedAttributeParams[1],
                    enforcedAttributeParams[2]);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException(ENFORCED_ATTRIBUTES_PROPERTY_INVALID);
        }

    }

    private Whitelist addAttributesToWhitelist(String[] attributes) {
        try {
            return whitelist
                .addAttributes(attributes[0], Arrays.copyOfRange(attributes, 1, attributes.length));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException(ATTRIBUTES_PROPERTY_INVALID);
        }
    }

    private void removeRestrictions() {
        getValues("removeTags").forEach(whitelist::removeTags);
        getValues("removeAttributes").forEach(whitelist::removeAttributes);
        getValues("removeEnforcedAttributes")
            .map(enforcedAttribute -> enforcedAttribute.split(":"))
            .forEach(this::removeEnforcedAttributesFromWhitelist);
        getValues("removeProtocols")
            .map(protocol -> protocol.split(":"))
            .forEach(this::removeProtocolsFromWhitelist);
    }

    private Whitelist removeProtocolsFromWhitelist(String[] protocolParams) {
        try {
            return whitelist.removeProtocols(protocolParams[0], protocolParams[1],
                Arrays.copyOfRange(protocolParams, 2, protocolParams.length));
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException(REMOVE_PROTOCOLS_PROPERTY_INVALID);
        }

    }

    private Whitelist removeEnforcedAttributesFromWhitelist(String[] enforcedAttributeParams) {
        try {
            return whitelist
                .removeEnforcedAttribute(enforcedAttributeParams[0], enforcedAttributeParams[1]);
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new RuntimeException(REMOVE_ENFORCED_ATTRIBUTES_PROPERTY_INVALID);
        }
    }

    private Stream<String> getValues(String initParamName) {
        return getInitParameterAsOptional(initParamName)
            .map(allValues -> allValues.split(","))
            .stream()
            .flatMap(Stream::of)
            .map(String::strip)
            .filter(Predicate.not(String::isBlank));
    }

    @Override
    public void service(Mail mail) throws MessagingException {
        final MimeMessage mimeMessage = mail.getMessage();

        try {
            if (mimeMessage.isMimeType("multipart/*")) {
                sanitizeMultipart(((Multipart) mimeMessage.getContent()));

            }
            if (mimeMessage.isMimeType("text/html")) {
                final String sanitizeHtml = sanitizeHtml((String) mimeMessage.getContent());
                mimeMessage.setContent(sanitizeHtml, mimeMessage.getContentType());
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    private String sanitizeHtml(String html) {
        return Jsoup.clean(html, this.whitelist);
    }

    private void sanitizeMultipart(Multipart mimeMultipart) throws MessagingException {

        final int numberOfParts = mimeMultipart.getCount();

        IntStream.range(0, numberOfParts)
            .mapToObj((ThrowingIntFunction<BodyPart>) mimeMultipart::getBodyPart)
            .forEach((ThrowingConsumer<BodyPart>) this::sanitizeBodyPart);

    }

    private void sanitizeBodyPart(BodyPart bodyPart) throws MessagingException, IOException {
        if (bodyPart.isMimeType("text/html")) {
            final String sanitizeHtml = sanitizeHtml((String) bodyPart.getContent());
            bodyPart.setContent(sanitizeHtml, bodyPart.getContentType());
        }
        if (bodyPart.isMimeType("multipart/*")) {
            sanitizeMultipart((Multipart) bodyPart.getContent());
        }
    }

}
