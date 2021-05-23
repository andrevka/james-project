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

import static org.junit.jupiter.api.Assertions.assertThrows;

import javax.mail.MessagingException;

import org.apache.mailet.base.test.FakeMailetConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SanitizeHtmlBodyPartsTest {

    SanitizeHtmlBodyParts sanitizeHtmlBodyParts;

    @BeforeEach
    public void setUp() throws Exception {
        sanitizeHtmlBodyParts = new SanitizeHtmlBodyParts();
    }

    @Test
    public void shouldInitWithNoProperties() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .build();

        sanitizeHtmlBodyParts.init(test);
    }

    @Test
    public void shouldInitWithLevelPropertySetToRelaxed() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("level", "relaxed")
            .build();

        sanitizeHtmlBodyParts.init(test);
    }

    @Test
    public void shouldThrowErrorWhenUnknownLevelValueUsed() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("level", "abcderfg")
            .build();

        assertThrows(
            IllegalArgumentException.class,
            () -> {
                sanitizeHtmlBodyParts.init(test);
            },
            "No enum constant org.apache.james.transport.mailets.SanitizeHtmlBodyParts.SanitizerLevel.ABCDERFG"
        );

    }

    @Test
    public void shouldInitWhenTagsSpecified() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("tags", "p,a,h1,h2")
            .build();

        sanitizeHtmlBodyParts.init(test);
    }

    @Test
    public void shouldInitWhenAttributesSpecified() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("attributes", "a:href,img:alternative")
            .build();

        sanitizeHtmlBodyParts.init(test);
    }

    @Test
    public void shouldThrowErrorWhenTooFewArgumentsForAttributesProperty()
        throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("attributes", "a:href,img")
            .build();

        assertThrows(
            RuntimeException.class,
            () -> sanitizeHtmlBodyParts.init(test),
            SanitizeHtmlBodyParts.ATTRIBUTES_PROPERTY_INVALID
        );
    }

    @Test
    public void shouldInitWithCorrectEnforcedAttributes() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("enforcedAttributes",
                "a:href:https://www.example.com,l:href:https://www.example.com,")
            .build();

        sanitizeHtmlBodyParts.init(test);

    }

    @Test
    public void shouldThrowErrorWhenTooFewArgumentsForEnforcedAttributesProperty() {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("enforcedAttributes", "a:href")
            .build();

        assertThrows(
            RuntimeException.class,
            () -> sanitizeHtmlBodyParts.init(test),
            SanitizeHtmlBodyParts.ENFORCED_ATTRIBUTES_PROPERTY_INVALID
        );
    }

    @Test
    public void shouldInitWithCorrectProtocols() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("protocols",
                "a:href:https:http")
            .build();

        sanitizeHtmlBodyParts.init(test);

    }

    @Test
    public void shouldThrowErrorWhenTooFewArgumentsForProtocols() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("protocols",
                "a:href:https,a")
            .build();

        assertThrows(
            RuntimeException.class,
            () -> sanitizeHtmlBodyParts.init(test),
            SanitizeHtmlBodyParts.PROTOCOLS_PROPERTY_INVALID
        );

    }

    @Test
    public void shouldInitWhenCorrectRemoveProtocols_addProtocolFirst_thenRemoveWorks() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("protocols",
                "a:href:https")
            .setProperty("removeProtocols",
                "a:href:https")
            .build();

        sanitizeHtmlBodyParts.init(test);

    }

    @Test
    public void shouldThrowErrorWhenTooFewArgumentsForRemoveProtocols() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("removeProtocols",
                "a:href:https,a")
            .build();

        assertThrows(
            RuntimeException.class,
            () -> sanitizeHtmlBodyParts.init(test),
            SanitizeHtmlBodyParts.PROTOCOLS_PROPERTY_INVALID
        );

    }

    @Test
    public void shouldInitWhenCorrectRemoveEnforcedAttributes_firstAdd_thenRemovingWorks() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("enforcedAttributes",
                "a:href:https://example.com")
            .setProperty("removeEnforcedAttributes",
                "a:href")
            .build();

        sanitizeHtmlBodyParts.init(test);

    }

    @Test
    public void shouldThrowErrorWhenTooFewArgumentsForEnforcedAttributes() throws MessagingException {
        final FakeMailetConfig test = FakeMailetConfig.builder()
            .mailetName("test")
            .setProperty("removeEnforcedAttributes",
                "a")
            .build();

        assertThrows(
            RuntimeException.class,
            () -> sanitizeHtmlBodyParts.init(test),
            SanitizeHtmlBodyParts.REMOVE_ENFORCED_ATTRIBUTES_PROPERTY_INVALID
        );

    }

}
