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

package org.apache.james.mailetcontainer.impl;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import javax.mail.internet.MimeMessage;

import org.apache.commons.configuration2.BaseHierarchicalConfiguration;
import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.core.Username;
import org.apache.james.core.builder.MimeMessageBuilder;
import org.apache.james.dnsservice.api.DNSService;
import org.apache.james.domainlist.api.DomainListException;
import org.apache.james.domainlist.lib.DomainListConfiguration;
import org.apache.james.domainlist.memory.MemoryDomainList;
import org.apache.james.queue.api.MailQueue;
import org.apache.james.queue.api.MailQueueFactory;
import org.apache.james.rrt.api.RecipientRewriteTableException;
import org.apache.james.rrt.memory.MemoryRecipientRewriteTable;
import org.apache.james.server.core.MailImpl;
import org.apache.james.user.api.UsersRepositoryException;
import org.apache.james.user.memory.MemoryUsersRepository;
import org.apache.james.util.MimeMessageUtil;
import org.apache.mailet.Mail;
import org.apache.mailet.base.MailAddressFixture;
import org.apache.mailet.base.test.FakeMail;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.google.common.collect.ImmutableList;

class JamesMailetContextTest {
    private static final Domain DOMAIN_COM = Domain.of("domain.com");
    private static final String USERNAME = "user";
    private static final Username USERMAIL = Username.of(USERNAME + "@" + DOMAIN_COM.name());
    private static final String PASSWORD = "password";
    private static final DNSService DNS_SERVICE = null;
    
    private MemoryDomainList domainList;
    private MemoryUsersRepository usersRepository;
    private JamesMailetContext testee;
    private MailAddress mailAddress;
    private MailQueue spoolMailQueue;
    private MemoryRecipientRewriteTable recipientRewriteTable;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        domainList = spy(new MemoryDomainList(DNS_SERVICE));
        domainList.configure(DomainListConfiguration.DEFAULT);

        usersRepository = spy(MemoryUsersRepository.withVirtualHosting(domainList));
        recipientRewriteTable = spy(new MemoryRecipientRewriteTable());
        recipientRewriteTable.configure(new BaseHierarchicalConfiguration());
        MailQueueFactory<MailQueue> mailQueueFactory = mock(MailQueueFactory.class);
        spoolMailQueue = mock(MailQueue.class);
        when(mailQueueFactory.createQueue(MailQueueFactory.SPOOL)).thenReturn(spoolMailQueue);
        DNSService dnsService = null;
        LocalResources localResources = new LocalResources(usersRepository, domainList, recipientRewriteTable);
        testee = new JamesMailetContext(dnsService, domainList, localResources, mailQueueFactory);
        testee.configure(new BaseHierarchicalConfiguration());
        mailAddress = new MailAddress(USERMAIL.asString());
    }

    @Test
    void isLocalUserShouldBeFalseOnNullUser() {
        assertThat(testee.isLocalUser(null)).isFalse();
    }

    @Test
    void isLocalServerShouldBeFalseWhenDomainDoNotExist() {
        assertThat(testee.isLocalServer(DOMAIN_COM)).isFalse();
    }

    @Test
    void isLocalServerShouldPropagateDomainExceptions() throws Exception {
        when(domainList.containsDomain(any())).thenThrow(new DomainListException("fail!"));

        assertThatThrownBy(() -> testee.isLocalServer(DOMAIN_COM))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalUserShouldPropagateDomainExceptions() throws Exception {
        when(domainList.getDefaultDomain()).thenThrow(new DomainListException("fail!"));

        assertThatThrownBy(() -> testee.isLocalUser("user"))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalUserShouldPropagateUserExceptions() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(Domain.of("any"))
            .build());
        domainList.addDomain(DOMAIN_COM);

        doThrow(new UsersRepositoryException("fail!")).when(usersRepository).contains(any());

        assertThatThrownBy(() -> testee.isLocalUser(USERNAME))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalUserShouldPropagateRrtExceptions() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(Domain.of("any"))
            .build());
        domainList.addDomain(DOMAIN_COM);

        doThrow(new RecipientRewriteTableException("fail!")).when(recipientRewriteTable).getResolvedMappings(any(), any(), any());

        assertThatThrownBy(() -> testee.isLocalUser(USERNAME))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalServerShouldBeTrueWhenDomainExist() throws Exception {
        domainList.addDomain(DOMAIN_COM);

        assertThat(testee.isLocalServer(DOMAIN_COM)).isTrue();
    }

    @Test
    void isLocalUserShouldBeTrueWhenUsernameExist() throws Exception {
        domainList.addDomain(DOMAIN_COM);
        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.isLocalUser(USERMAIL.asString())).isTrue();
    }

    @Test
    void isLocalUserShouldReturnTrueWhenUsedWithLocalPartAndUserExistOnDefaultDomain() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(DOMAIN_COM)
            .build());

        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.isLocalUser(USERNAME)).isTrue();
    }

    @Test
    void isLocalUserShouldReturnFalseWhenUsedWithLocalPartAndUserDoNotExistOnDefaultDomain() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(Domain.of("any"))
            .build());

        domainList.addDomain(DOMAIN_COM);
        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.isLocalUser(USERNAME)).isFalse();
    }

    @Test
    void isLocalUserShouldBeFalseWhenUsernameDoNotExist() {
        assertThat(testee.isLocalUser(USERMAIL.asString())).isFalse();
    }

    @Test
    void isLocalEmailShouldBeFalseWhenUsernameDoNotExist() {
        assertThat(testee.isLocalEmail(mailAddress)).isFalse();
    }

    @Test
    void isLocalEmailShouldBeFalseWhenUsernameDoNotExistButDomainExists() throws Exception {
        domainList.addDomain(DOMAIN_COM);

        assertThat(testee.isLocalEmail(mailAddress)).isFalse();
    }

    @Test
    void isLocalEmailShouldBeTrueWhenUsernameExists() throws Exception {
        domainList.addDomain(DOMAIN_COM);
        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.isLocalEmail(mailAddress)).isTrue();
    }

    @Test
    void localRecipientsShouldReturnAddressWhenUserExists() throws Exception {
        domainList.addDomain(DOMAIN_COM);
        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.localRecipients(ImmutableList.of(mailAddress))).containsOnly(mailAddress);
    }

    @Test
    void localRecipientsShouldReturnOnlyExistingUsers() throws Exception {
        domainList.addDomain(DOMAIN_COM);
        usersRepository.addUser(USERMAIL, PASSWORD);

        assertThat(testee.localRecipients(
            ImmutableList.of(mailAddress,
                MailAddressFixture.RECIPIENT2)))
            .containsOnly(mailAddress);
    }

    @Test
    void localRecipientsShouldNotReturnAddressWhenUserDoNotExists() throws Exception {
        domainList.addDomain(DOMAIN_COM);

        assertThat(testee.localRecipients(ImmutableList.of(mailAddress))).isEmpty();
    }

    @Test
    void localRecipientsShouldNotReturnAddressWhenDomainDoNotExists() throws Exception {
        assertThat(testee.localRecipients(ImmutableList.of(mailAddress))).isEmpty();
    }

    @Test
    void isLocalEmailShouldBeFalseWhenMailIsNull() {
        assertThat(testee.isLocalEmail(null)).isFalse();
    }

    @Test
    void isLocalEmailShouldPropagateDomainExceptions() throws Exception {
        when(domainList.containsDomain(any())).thenThrow(new DomainListException("fail!"));

        assertThatThrownBy(() -> testee.isLocalEmail(mailAddress))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalEmailShouldPropagateUserExceptions() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(Domain.of("any"))
            .build());
        domainList.addDomain(DOMAIN_COM);

        doThrow(new UsersRepositoryException("fail!")).when(usersRepository).contains(any());

        assertThatThrownBy(() -> testee.isLocalEmail(mailAddress))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void isLocalEmailShouldPropagateRrtExceptions() throws Exception {
        domainList.configure(DomainListConfiguration.builder()
            .defaultDomain(Domain.of("any"))
            .build());
        domainList.addDomain(DOMAIN_COM);

        doThrow(new RecipientRewriteTableException("fail!")).when(recipientRewriteTable).getResolvedMappings(any(), any(), any());

        assertThatThrownBy(() -> testee.isLocalEmail(mailAddress))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void bounceShouldNotFailWhenNonConfiguredPostmaster() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();
        testee.bounce(mail, "message");
    }

    @Test
    void bouncingToNullSenderShouldBeANoop() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(MailAddress.nullSender())
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();

        testee.bounce(mail, "message");

        verifyZeroInteractions(spoolMailQueue);
    }

    @Test
    void bouncingToNoSenderShouldBeANoop() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();

        testee.bounce(mail, "message");

        verifyZeroInteractions(spoolMailQueue);
    }

    @Test
    void bounceShouldEnqueueEmailWithRootState() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();

        testee.bounce(mail, "message");

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
    }

    @Test
    void sendMailShouldEnqueueEmailWithRootState() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();
        testee.sendMail(mail);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
    }

    @Test
    void sendMailShouldEnqueueEmailWithOtherStateWhenSpecified() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();
        String other = "other";
        testee.sendMail(mail, other);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(other);
    }

    @Test
    void sendMailShouldEnqueueEmailWithRootStateAndDelayWhenSpecified() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();
        testee.sendMail(mail, 5, TimeUnit.MINUTES);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        ArgumentCaptor<Long> delayArgumentCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> timeUnitArgumentCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture(), delayArgumentCaptor.capture(), timeUnitArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
            softly.assertThat(delayArgumentCaptor.getValue()).isEqualTo(5L);
            softly.assertThat(timeUnitArgumentCaptor.getValue()).isEqualTo(TimeUnit.MINUTES);
        });
    }

    @Test
    void sendMailShouldEnqueueEmailWithOtherStateAndDelayWhenSpecified() throws Exception {
        MailImpl mail = MailImpl.builder()
            .name("mail1")
            .sender(mailAddress)
            .addRecipient(mailAddress)
            .mimeMessage(MimeMessageUtil.mimeMessageFromBytes("header: value\r\n".getBytes(UTF_8)))
            .build();
        String other = "other";
        testee.sendMail(mail, other, 5, TimeUnit.MINUTES);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        ArgumentCaptor<Long> delayArgumentCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<TimeUnit> timeUnitArgumentCaptor = ArgumentCaptor.forClass(TimeUnit.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture(), delayArgumentCaptor.capture(), timeUnitArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(other);
            softly.assertThat(delayArgumentCaptor.getValue()).isEqualTo(5L);
            softly.assertThat(timeUnitArgumentCaptor.getValue()).isEqualTo(TimeUnit.MINUTES);
        });
    }

    @Test
    void sendMailForMessageShouldEnqueueEmailWithRootState() throws Exception {
        MimeMessage message = MimeMessageBuilder.mimeMessageBuilder()
            .addFrom(mailAddress.asString())
            .addToRecipient(mailAddress.asString())
            .setText("Simple text")
            .build();

        testee.sendMail(message);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
    }

    @Test
    void sendMailForMessageAndEnvelopeShouldEnqueueEmailWithRootState() throws Exception {
        MimeMessage message = MimeMessageBuilder.mimeMessageBuilder()
            .addFrom(mailAddress.asString())
            .addToRecipient(mailAddress.asString())
            .setText("Simple text")
            .build();

        MailAddress sender = mailAddress;
        ImmutableList<MailAddress> recipients = ImmutableList.of(mailAddress);
        testee.sendMail(sender, recipients, message);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
    }

    @Test
    void sendMailForMessageAndEnvelopeShouldEnqueueEmailWithOtherStateWhenSpecified() throws Exception {
        MimeMessage message = MimeMessageBuilder.mimeMessageBuilder()
            .addFrom(mailAddress.asString())
            .addToRecipient(mailAddress.asString())
            .setText("Simple text")
            .build();

        MailAddress sender = mailAddress;
        ImmutableList<MailAddress> recipients = ImmutableList.of(mailAddress);
        String otherState = "other";
        testee.sendMail(sender, recipients, message, otherState);

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(otherState);
    }

    @Test
    void sendMailForMailShouldEnqueueEmailWithOtherStateWhenSpecified() throws Exception {
        MimeMessage message = MimeMessageBuilder.mimeMessageBuilder()
            .addFrom(mailAddress.asString())
            .addToRecipient(mailAddress.asString())
            .setText("Simple text")
            .build();

        String otherState = "other";
        testee.sendMail(FakeMail.builder()
            .name("name")
            .sender(MailAddressFixture.SENDER)
            .recipient(MailAddressFixture.RECIPIENT1)
            .mimeMessage(message)
            .state(otherState)
            .build());

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(otherState);
    }

    @Test
    void sendMailForMailShouldEnqueueEmailWithDefaults() throws Exception {
        MimeMessage message = MimeMessageBuilder.mimeMessageBuilder()
            .addFrom(mailAddress.asString())
            .addToRecipient(mailAddress.asString())
            .setText("Simple text")
            .build();

        testee.sendMail(FakeMail.builder()
            .name("name")
            .sender(MailAddressFixture.SENDER)
            .recipient(MailAddressFixture.RECIPIENT1)
            .mimeMessage(message)
            .build());

        ArgumentCaptor<Mail> mailArgumentCaptor = ArgumentCaptor.forClass(Mail.class);
        verify(spoolMailQueue).enQueue(mailArgumentCaptor.capture());
        verifyNoMoreInteractions(spoolMailQueue);

        assertThat(mailArgumentCaptor.getValue().getState()).isEqualTo(Mail.DEFAULT);
    }
}
