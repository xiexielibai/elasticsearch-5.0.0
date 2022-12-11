/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.reindex;

import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.index.reindex.remote.RemoteInfo;
import org.elasticsearch.test.ESTestCase;
import org.junit.Before;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.elasticsearch.index.reindex.TransportReindexAction.buildRemoteWhitelist;
import static org.elasticsearch.index.reindex.TransportReindexAction.checkRemoteWhitelist;

/**
 * Tests the reindex-from-remote whitelist of remotes.
 */
public class ReindexFromRemoteWhitelistTests extends ESTestCase {
    private TransportAddress localhost;

    @Before
    public void setupLocalhost() throws UnknownHostException {
        localhost = new InetSocketTransportAddress(InetAddress.getByAddress(new byte[] { 0x7f, 0x00, 0x00, 0x01 }), 9200);
    }

    public void testLocalRequestWithoutWhitelist() {
        checkRemoteWhitelist(buildRemoteWhitelist(emptyList()), null, localhostOrNone());
    }

    public void testLocalRequestWithWhitelist() {
        checkRemoteWhitelist(buildRemoteWhitelist(randomWhitelist()), null, localhostOrNone());
    }

    public void testWhitelistedRemote() {
        List<String> whitelist = randomWhitelist();
        String[] inList = whitelist.iterator().next().split(":");
        String host = inList[0];
        int port = Integer.valueOf(inList[1]);
        checkRemoteWhitelist(buildRemoteWhitelist(whitelist),
                new RemoteInfo(randomAsciiOfLength(5), host, port, new BytesArray("test"), null, null, emptyMap()),
                localhostOrNone());
    }

    public void testWhitelistedByPrefix() {
        checkRemoteWhitelist(buildRemoteWhitelist(singletonList("*.example.com:9200")),
                new RemoteInfo(randomAsciiOfLength(5), "es.example.com", 9200, new BytesArray("test"), null, null, emptyMap()),
                localhostOrNone());
        checkRemoteWhitelist(buildRemoteWhitelist(singletonList("*.example.com:9200")),
                new RemoteInfo(randomAsciiOfLength(5), "6e134134a1.us-east-1.aws.example.com", 9200,
                        new BytesArray("test"), null, null, emptyMap()),
                localhostOrNone());
    }

    public void testWhitelistedBySuffix() {
        checkRemoteWhitelist(buildRemoteWhitelist(singletonList("es.example.com:*")),
                new RemoteInfo(randomAsciiOfLength(5), "es.example.com", 9200, new BytesArray("test"), null, null, emptyMap()),
                localhostOrNone());
    }

    public void testWhitelistedByInfix() {
        checkRemoteWhitelist(buildRemoteWhitelist(singletonList("es*.example.com:9200")),
                new RemoteInfo(randomAsciiOfLength(5), "es1.example.com", 9200, new BytesArray("test"), null, null, emptyMap()),
                localhostOrNone());
    }


    public void testLoopbackInWhitelistRemote() throws UnknownHostException {
        List<String> whitelist = randomWhitelist();
        whitelist.add("127.0.0.1:*");
        TransportAddress publishAddress = new InetSocketTransportAddress(InetAddress.getByAddress(new byte[] {0x7f,0x00,0x00,0x01}), 9200);
        checkRemoteWhitelist(buildRemoteWhitelist(whitelist),
                new RemoteInfo(randomAsciiOfLength(5), "127.0.0.1", 9200, new BytesArray("test"), null, null, emptyMap()),
                publishAddress);
    }

    public void testUnwhitelistedRemote() {
        int port = between(1, Integer.MAX_VALUE);
        RemoteInfo remoteInfo = new RemoteInfo(randomAsciiOfLength(5), "not in list", port, new BytesArray("test"), null, null, emptyMap());
        List<String> whitelist = randomBoolean() ? randomWhitelist() : emptyList();
        Exception e = expectThrows(IllegalArgumentException.class,
                () -> checkRemoteWhitelist(buildRemoteWhitelist(whitelist), remoteInfo, localhostOrNone()));
        assertEquals("[not in list:" + port + "] not whitelisted in reindex.remote.whitelist", e.getMessage());
    }

    public void testRejectMatchAll() {
        assertMatchesTooMuch(singletonList("*"));
        assertMatchesTooMuch(singletonList("**"));
        assertMatchesTooMuch(singletonList("***"));
        assertMatchesTooMuch(Arrays.asList("realstuff", "*"));
        assertMatchesTooMuch(Arrays.asList("*", "realstuff"));
        List<String> random = randomWhitelist();
        random.add("*");
        assertMatchesTooMuch(random);
    }

    private void assertMatchesTooMuch(List<String> whitelist) {
        Exception e = expectThrows(IllegalArgumentException.class, () -> buildRemoteWhitelist(whitelist));
        assertEquals("Refusing to start because whitelist " + whitelist + " accepts all addresses. "
                + "This would allow users to reindex-from-remote any URL they like effectively having Elasticsearch make HTTP GETs "
                + "for them.", e.getMessage());
    }

    private List<String> randomWhitelist() {
        int size = between(1, 100);
        List<String> whitelist = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            whitelist.add(randomAsciiOfLength(5) + ':' + between(1, Integer.MAX_VALUE));
        }
        return whitelist;
    }

    private TransportAddress localhostOrNone() {
        return randomFrom(random(), null, localhost);
    }
}
