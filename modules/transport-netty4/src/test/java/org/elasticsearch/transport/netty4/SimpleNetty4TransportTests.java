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

package org.elasticsearch.transport.netty4;

import org.elasticsearch.Version;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.node.Node;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.AbstractSimpleTransportTestCase;
import org.elasticsearch.transport.BindTransportException;
import org.elasticsearch.transport.ConnectTransportException;
import org.elasticsearch.transport.Transport;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.transport.TransportSettings;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collections;

import static java.util.Collections.emptyMap;
import static java.util.Collections.emptySet;
import static org.hamcrest.Matchers.containsString;

public class SimpleNetty4TransportTests extends AbstractSimpleTransportTestCase {

    public static MockTransportService nettyFromThreadPool(
        Settings settings,
        ThreadPool threadPool, final Version version) {
        NamedWriteableRegistry namedWriteableRegistry = new NamedWriteableRegistry(Collections.emptyList());
        Transport transport = new Netty4Transport(settings, threadPool, new NetworkService(settings, Collections.emptyList()),
            BigArrays.NON_RECYCLING_INSTANCE, namedWriteableRegistry, new NoneCircuitBreakerService()) {
            @Override
            protected Version getCurrentVersion() {
                return version;
            }
        };
        return new MockTransportService(Settings.EMPTY, transport, threadPool, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
    }

    @Override
    protected MockTransportService build(Settings settings, Version version) {
        settings = Settings.builder().put(settings).put(TransportSettings.PORT.getKey(), "0").build();
        MockTransportService transportService = nettyFromThreadPool(settings, threadPool, version);
        transportService.start();
        return transportService;
    }

    public void testConnectException() throws UnknownHostException {
        try {
            serviceA.connectToNode(new DiscoveryNode("C", new InetSocketTransportAddress(InetAddress.getByName("localhost"), 9876),
                    emptyMap(), emptySet(),Version.CURRENT));
            fail("Expected ConnectTransportException");
        } catch (ConnectTransportException e) {
            assertThat(e.getMessage(), containsString("connect_timeout"));
            assertThat(e.getMessage(), containsString("[127.0.0.1:9876]"));
        }
    }

    public void testBindUnavailableAddress() {
        // this is on a lower level since it needs access to the TransportService before it's started
        int port = serviceA.boundAddress().publishAddress().getPort();
        Settings settings = Settings.builder()
            .put(Node.NODE_NAME_SETTING.getKey(), "foobar")
            .put(TransportService.TRACE_LOG_INCLUDE_SETTING.getKey(), "")
            .put(TransportService.TRACE_LOG_EXCLUDE_SETTING.getKey(), "NOTHING")
            .put("transport.tcp.port", port)
            .build();
        BindTransportException bindTransportException = expectThrows(BindTransportException.class, () -> {
            MockTransportService transportService = nettyFromThreadPool(settings, threadPool, Version.CURRENT);
            try {
                transportService.start();
            } finally {
                transportService.stop();
                transportService.close();
            }
        });
        assertEquals("Failed to bind to ["+ port + "]", bindTransportException.getMessage());
    }

}
