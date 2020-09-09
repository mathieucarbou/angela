/*
 * The contents of this file are subject to the Terracotta Public License Version
 * 2.0 (the "License"); You may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://terracotta.org/legal/terracotta-public-license.
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License for
 * the specific language governing rights and limitations under the License.
 *
 * The Covered Software is Angela.
 *
 * The Initial Developer of the Covered Software is
 * Terracotta, Inc., a Software AG company
 */

package org.terracotta.angela.agent;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.Ignition;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.logger.NullLogger;
import org.apache.ignite.logger.slf4j.Slf4jLogger;
import org.apache.ignite.spi.communication.tcp.TcpCommunicationSpi;
import org.apache.ignite.spi.discovery.tcp.TcpDiscoverySpi;
import org.apache.ignite.spi.discovery.tcp.ipfinder.vm.TcpDiscoveryVmIpFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.util.AngelaVersion;
import org.terracotta.angela.common.util.IgniteCommonHelper;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.terracotta.angela.common.AngelaProperties.DIRECT_JOIN;
import static org.terracotta.angela.common.AngelaProperties.IGNITE_LOGGING;
import static org.terracotta.angela.common.AngelaProperties.NODE_NAME;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.util.FileUtils.createAndValidateDir;

/**
 * @author Ludovic Orban
 */
public class Agent {
  public static final String AGENT_IS_READY_MARKER_LOG = "Agent is ready";
  private final static Logger logger;
  private Ignite ignite;

  public static final Path ROOT_DIR;
  public static final Path WORK_DIR;
  public static final Path IGNITE_DIR;
  public static volatile AgentController controller;

  static {
    // the angela-agent jar may end up on the classpath, so its logback config cannot have the default filename
    // this must happen before any Logger instance gets created
    System.setProperty("logback.configurationFile", "angela-logback.xml");
    logger = LoggerFactory.getLogger(Agent.class);

    ROOT_DIR = Paths.get(getEitherOf(AngelaProperties.ROOT_DIR, AngelaProperties.KITS_DIR));
    if (!ROOT_DIR.isAbsolute()) {
      throw new IllegalArgumentException("Expected ROOT_DIR to be an absolute path, got: " + ROOT_DIR);
    }
    WORK_DIR = ROOT_DIR.resolve("work");
    IGNITE_DIR = ROOT_DIR.resolve("ignite");
  }

  public static void main(String[] args) {
    final Agent agent = new Agent();
    int igniteDiscoveryPort = Integer.parseInt(System.getProperty("ignite.discovery.port"));
    int igniteComPort = Integer.parseInt(System.getProperty("ignite.com.port"));
    agent.startCluster(Arrays.asList(DIRECT_JOIN.getValue().split(",")), NODE_NAME.getValue(), igniteDiscoveryPort, igniteComPort);
    Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
  }

  public void startCluster(Collection<String> peers, String nodeName, int igniteDiscoveryPort, int igniteComPort) {
    logger.info("Root directory is: {}", ROOT_DIR);
    logger.info("Nodename: {} added to cluster", nodeName);
    createAndValidateDir(ROOT_DIR);
    createAndValidateDir(WORK_DIR);
    createAndValidateDir(IGNITE_DIR);

    IgniteConfiguration cfg = new IgniteConfiguration();
    Map<String, String> userAttributes = new HashMap<>();
    userAttributes.put("angela.version", AngelaVersion.getAngelaVersion());
    userAttributes.put("nodename", nodeName);
    cfg.setUserAttributes(userAttributes);

    boolean enableLogging = Boolean.getBoolean(IGNITE_LOGGING.getValue());
    cfg.setGridLogger(enableLogging ? new Slf4jLogger() : new NullLogger());
    cfg.setPeerClassLoadingEnabled(true);
    cfg.setMetricsLogFrequency(0);
    cfg.setIgniteInstanceName("ignite-" + igniteDiscoveryPort);
    cfg.setIgniteHome(IGNITE_DIR.resolve(System.getProperty("user.name")).toString());

    logger.info("Connecting to peers (size = {}): {}", peers.size(), peers);

    cfg.setDiscoverySpi(new TcpDiscoverySpi()
        .setLocalPort(igniteDiscoveryPort)
        .setLocalPortRange(0) // we must not use the range otherwise Ignite might bind to a port not reserved
        .setJoinTimeout(10000)
        .setIpFinder(new TcpDiscoveryVmIpFinder(true).setAddresses(peers)));

    cfg.setCommunicationSpi(new TcpCommunicationSpi()
        .setLocalPort(igniteComPort)
        .setLocalPortRange(0)); // we must not use the range otherwise Ignite might bind to a port not reserved

    try {
      logger.info("Starting ignite on {}", nodeName);
      ignite = Ignition.start(cfg);
      IgniteCommonHelper.displayCluster(ignite);

    } catch (IgniteException e) {
      throw new RuntimeException("Error starting node " + nodeName, e);
    }
    controller = new AgentController(ignite, peers, igniteDiscoveryPort, new DefaultPortAllocator());

    // Do not use logger here as the marker is being grep'ed at and we do not want to depend upon the logger config
    System.out.println(AGENT_IS_READY_MARKER_LOG);
    System.out.flush();
  }

  public void close() {
    if (ignite != null) {
      ignite.close();
      ignite = null;
    }
  }

  public Ignite getIgnite() {
    return this.ignite;
  }
}
