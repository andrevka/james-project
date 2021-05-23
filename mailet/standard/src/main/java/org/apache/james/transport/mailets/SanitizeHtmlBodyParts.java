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
        getValues("attributes").forEach(whitelist::addAttributes);
        getValues("enforcedAttributes")
            .map(enforcedAttribute -> enforcedAttribute.split(":"))
            .forEach(enforcedAttributeParams ->
                whitelist
                    .addEnforcedAttribute(enforcedAttributeParams[0], enforcedAttributeParams[1],
                        enforcedAttributeParams[2])
            );
        getValues("protocols")
            .map(protocols -> protocols.split(":"))
            .forEach(protocolParams ->
                whitelist.addProtocols(protocolParams[0], protocolParams[1],
                    Arrays.copyOfRange(protocolParams, 2, protocolParams.length))
            );
    }

    private void removeRestrictions() {
        getValues("removeTags").forEach(whitelist::removeTags);
        getValues("removeAttributes").forEach(whitelist::removeAttributes);
        getValues("removeEnforcedAttributes")
            .map(enforcedAttribute -> enforcedAttribute.split(":"))
            .forEach(enforcedAttributeParams ->
                whitelist
                    .removeEnforcedAttribute(enforcedAttributeParams[0], enforcedAttributeParams[1])
            );
        getValues("removeProtocols")
            .map(protocol -> protocol.split(":"))
            .forEach(protocolParams ->
                whitelist.removeProtocols(protocolParams[0], protocolParams[1],
                    Arrays.copyOfRange(protocolParams, 2, protocolParams.length))
            );
    }

    private Stream<String> getValues(String initParamName) {
        return getInitParameterAsOptional(initParamName)
            .map(allValues -> allValues.split(","))
            .stream()
            .flatMap(Stream::of)
            .map(String::strip);
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
