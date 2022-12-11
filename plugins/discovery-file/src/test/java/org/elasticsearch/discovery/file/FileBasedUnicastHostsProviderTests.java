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

package org.elasticsearch.discovery.file;

import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.network.NetworkService;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.BigArrays;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService;
import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.test.transport.MockTransportService;
import org.elasticsearch.threadpool.TestThreadPool;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.MockTcpTransport;
import org.elasticsearch.transport.TransportService;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.discovery.file.FileBasedUnicastHostsProvider.UNICAST_HOSTS_FILE;
import static org.elasticsearch.discovery.file.FileBasedUnicastHostsProvider.UNICAST_HOST_PREFIX;

/**
 * Tests for {@link FileBasedUnicastHostsProvider}.
 */
public class FileBasedUnicastHostsProviderTests extends ESTestCase {

    private static ThreadPool threadPool;
    private MockTransportService transportService;

    @BeforeClass
    public static void createThreadPool() {
        threadPool = new TestThreadPool(FileBasedUnicastHostsProviderTests.class.getName());
    }

    @AfterClass
    public static void stopThreadPool() throws InterruptedException {
        terminate(threadPool);
    }

    @Before
    public void createTransportSvc() {
        MockTcpTransport transport =
            new MockTcpTransport(Settings.EMPTY,
                                    threadPool,
                                    BigArrays.NON_RECYCLING_INSTANCE,
                                    new NoneCircuitBreakerService(),
                                    new NamedWriteableRegistry(Collections.emptyList()),
                                    new NetworkService(Settings.EMPTY, Collections.emptyList()));
        transportService = new MockTransportService(Settings.EMPTY, transport, threadPool, TransportService.NOOP_TRANSPORT_INTERCEPTOR);
    }

    public void testBuildDynamicNodes() throws Exception {
        final List<String> hostEntries = Arrays.asList("#comment, should be ignored", "192.168.0.1", "192.168.0.2:9305", "255.255.23.15");
        final List<DiscoveryNode> nodes = setupAndRunHostProvider(hostEntries);
        assertEquals(hostEntries.size() - 1, nodes.size()); // minus 1 because we are ignoring the first line that's a comment
        assertEquals("192.168.0.1", nodes.get(0).getAddress().getHost());
        assertEquals(9300, nodes.get(0).getAddress().getPort());
        assertEquals(UNICAST_HOST_PREFIX + "1#", nodes.get(0).getId());
        assertEquals("192.168.0.2", nodes.get(1).getAddress().getHost());
        assertEquals(9305, nodes.get(1).getAddress().getPort());
        assertEquals(UNICAST_HOST_PREFIX + "2#", nodes.get(1).getId());
        assertEquals("255.255.23.15", nodes.get(2).getAddress().getHost());
        assertEquals(9300, nodes.get(2).getAddress().getPort());
        assertEquals(UNICAST_HOST_PREFIX + "3#", nodes.get(2).getId());
    }

    public void testEmptyUnicastHostsFile() throws Exception {
        final List<String> hostEntries = Collections.emptyList();
        final List<DiscoveryNode> nodes = setupAndRunHostProvider(hostEntries);
        assertEquals(0, nodes.size());
    }

    public void testUnicastHostsDoesNotExist() throws Exception {
        final Settings settings = Settings.builder()
                                      .put(Environment.PATH_HOME_SETTING.getKey(), createTempDir())
                                      .build();
        final FileBasedUnicastHostsProvider provider = new FileBasedUnicastHostsProvider(settings, transportService);
        final List<DiscoveryNode> nodes = provider.buildDynamicNodes();
        assertEquals(0, nodes.size());
    }

    public void testInvalidHostEntries() throws Exception {
        List<String> hostEntries = Arrays.asList("192.168.0.1:9300:9300");
        List<DiscoveryNode> nodes = setupAndRunHostProvider(hostEntries);
        assertEquals(0, nodes.size());
    }

    public void testSomeInvalidHostEntries() throws Exception {
        List<String> hostEntries = Arrays.asList("192.168.0.1:9300:9300", "192.168.0.1:9301");
        List<DiscoveryNode> nodes = setupAndRunHostProvider(hostEntries);
        assertEquals(1, nodes.size()); // only one of the two is valid and will be used
        assertEquals("192.168.0.1", nodes.get(0).getAddress().getHost());
        assertEquals(9301, nodes.get(0).getAddress().getPort());
    }

    // sets up the config dir, writes to the unicast hosts file in the config dir,
    // and then runs the file-based unicast host provider to get the list of discovery nodes
    private List<DiscoveryNode> setupAndRunHostProvider(final List<String> hostEntries) throws IOException {
        final Path homeDir = createTempDir();
        final Settings settings = Settings.builder()
                                      .put(Environment.PATH_HOME_SETTING.getKey(), homeDir)
                                      .build();
        final Path configDir = homeDir.resolve("config").resolve("discovery-file");
        Files.createDirectories(configDir);
        final Path unicastHostsPath = configDir.resolve(UNICAST_HOSTS_FILE);
        try (BufferedWriter writer = Files.newBufferedWriter(unicastHostsPath)) {
            writer.write(String.join("\n", hostEntries));
        }

        return new FileBasedUnicastHostsProvider(settings, transportService).buildDynamicNodes();
    }
}
