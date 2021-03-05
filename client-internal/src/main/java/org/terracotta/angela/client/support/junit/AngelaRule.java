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
package org.terracotta.angela.client.support.junit;

import org.junit.runner.Description;
import org.junit.runners.model.MultipleFailureException;
import org.terracotta.angela.client.ClientArray;
import org.terracotta.angela.client.ClusterFactory;
import org.terracotta.angela.client.ClusterMonitor;
import org.terracotta.angela.client.ClusterTool;
import org.terracotta.angela.client.ConfigTool;
import org.terracotta.angela.client.Tms;
import org.terracotta.angela.client.Tsa;
import org.terracotta.angela.client.Voter;
import org.terracotta.angela.client.config.ConfigurationContext;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.TerracottaServer;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static java.util.stream.IntStream.rangeClosed;
import org.terracotta.angela.client.ClusterAgent;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_PASSIVE;

/**
 * @author Mathieu Carbou
 */
public class AngelaRule extends ExtendedTestRule {

  private final ClusterAgent localAgent;
  private ConfigurationContext configuration;
  private final boolean autoStart;
  private final boolean autoActivate;

  private ClusterFactory clusterFactory;
  private Supplier<Tsa> tsa;
  private Supplier<Cluster> cluster;
  private Supplier<Tms> tms;
  private Supplier<ClientArray> clientArray;
  private Supplier<ClusterMonitor> clusterMonitor;
  private Supplier<Voter> voter;
  private Supplier<ConfigTool> configTool;
  private Supplier<ClusterTool> clusterTool;

  public AngelaRule(ClusterAgent localAgent, ConfigurationContext configuration) {
    this(localAgent, configuration, false, false);
  }

  public AngelaRule(ClusterAgent localAgent, ConfigurationContext configuration, boolean autoStart, boolean autoActivate) {
    this.localAgent = localAgent;
    this.configuration = configuration;
    this.autoStart = autoStart;
    this.autoActivate = autoActivate;
  }

  public ConfigurationContext configure(ConfigurationContext configuration) {
    ConfigurationContext old = this.configuration;
    this.configuration = configuration;
    return old;
  }

  // =========================================
  // junit rule
  // =========================================

  @Override
  protected void before(Description description) throws Throwable {
    final int nodePortCount = computeNodePortCount();

    PortAllocator.PortReservation nodePortReservation = localAgent.getPortAllocator().reserve(nodePortCount);

    // assign generated ports to nodes
    for (TerracottaServer node : configuration.tsa().getTopology().getServers()) {
      if (node.getTsaPort() <= 0) {
        node.tsaPort(nodePortReservation.next());
      }
      if (node.getTsaGroupPort() <= 0) {
        node.tsaGroupPort(nodePortReservation.next());
      }
    }

    String id = description.getTestClass().getSimpleName();
    if (description.getMethodName() != null) {
      id += "." + description.getMethodName();
    }

    this.clusterFactory = new ClusterFactory(localAgent, id, configuration);

    tsa = memoize(clusterFactory::tsa);
    cluster = memoize(clusterFactory::cluster);
    tms = memoize(clusterFactory::tms);
    clientArray = memoize(clusterFactory::clientArray);
    clusterMonitor = memoize(clusterFactory::monitor);
    voter = memoize(clusterFactory::voter);
    configTool = memoize(clusterFactory::configTool);
    clusterTool = memoize(clusterFactory::clusterTool);

    if (autoStart) {
      startNodes();
      if (autoActivate) {
        configTool().attachAll();
        configTool().activate();
      }
    }
  }

  @Override
  protected void after(Description description) throws Throwable {
    List<Throwable> errs = new ArrayList<>(0);
    try {
      if (clusterFactory != null) {
        clusterFactory.close();
        clusterFactory = null;
      }
    } catch (Throwable e) {
      errs.add(e);
    }
    MultipleFailureException.assertEmpty(errs);
  }

  // =========================================
  // start/stop nodes
  // =========================================

  public void startNodes() {
    List<List<TerracottaServer>> stripes = configuration.tsa().getTopology().getStripes();
    for (int stripeId = 1; stripeId <= stripes.size(); stripeId++) {
      List<TerracottaServer> stripe = stripes.get(stripeId - 1);
      for (int nodeId = 1; nodeId <= stripe.size(); nodeId++) {
        startNode(stripeId, nodeId);
      }
    }
  }

  public void startNode(int stripeId, int nodeId) {
    startNode(getNode(stripeId, nodeId));
  }

  public void startNode(int stripeId, int nodeId, String... cli) {
    startNode(getNode(stripeId, nodeId), cli);
  }

  public void startNode(TerracottaServer node, String... cli) {
    tsa().start(node, cli);
  }

  public void stopNode(int stripeId, int nodeId) {
    tsa().stop(getNode(stripeId, nodeId));
  }

  // =========================================
  // node query
  // =========================================

  public ClusterFactory getClusterFactory() {
    return clusterFactory;
  }

  public ConfigurationContext getConfiguration() {
    return configuration;
  }

  public int getStripeCount() {
    return configuration.tsa().getTopology().getStripes().size();
  }

  public int getNodeCount(int stripeId) {
    return getStripe(stripeId).size();
  }

  public List<TerracottaServer> getStripe(int stripeId) {
    if (stripeId < 1) {
      throw new IllegalArgumentException("Invalid stripe ID: " + stripeId);
    }
    List<List<TerracottaServer>> stripes = configuration.tsa().getTopology().getStripes();
    if (stripeId > stripes.size()) {
      throw new IllegalArgumentException("Invalid stripe ID: " + stripeId + ". There are " + stripes.size() + " stripe(s).");
    }
    return stripes.get(stripeId - 1);
  }

  public TerracottaServer getNode(int stripeId, int nodeId) {
    if (nodeId < 1) {
      throw new IllegalArgumentException("Invalid node ID: " + nodeId);
    }
    List<TerracottaServer> nodes = getStripe(stripeId);
    if (nodeId > nodes.size()) {
      throw new IllegalArgumentException("Invalid node ID: " + nodeId + ". Stripe ID: " + stripeId + " has " + nodes.size() + " nodes.");
    }
    return nodes.get(nodeId - 1);
  }

  public int getNodePort(int stripeId, int nodeId) {
    return getNode(stripeId, nodeId).getTsaPort();
  }

  public int getNodeGroupPort(int stripeId, int nodeId) {
    return getNode(stripeId, nodeId).getTsaGroupPort();
  }

  public OptionalInt findActive(int stripeId) {
    List<TerracottaServer> nodes = getStripe(stripeId);
    return rangeClosed(1, nodes.size())
        .filter(nodeId -> tsa().getState(nodes.get(nodeId - 1)) == STARTED_AS_ACTIVE)
        .findFirst();
  }

  public int[] findPassives(int stripeId) {
    List<TerracottaServer> nodes = getStripe(stripeId);
    return rangeClosed(1, nodes.size())
        .filter(nodeId -> tsa().getState(nodes.get(nodeId - 1)) == STARTED_AS_PASSIVE)
        .toArray();
  }

  // =========================================
  // delegates
  // =========================================

  public Tsa tsa() {
    return tsa.get();
  }

  public ConfigTool configTool() {
    return configTool.get();
  }

  public ClusterTool clusterTool() {
    return clusterTool.get();
  }

  public Cluster cluster() {
    return cluster.get();
  }

  public Tms tms() {
    return tms.get();
  }

  public ClientArray clientArray() {
    return clientArray.get();
  }

  public ClusterMonitor monitor() {
    return clusterMonitor.get();
  }

  public Voter voter() {
    return voter.get();
  }

  // =========================================
  // utils
  // =========================================

  protected int computeNodePortCount() {
    // compute the number of port to reserve for the nodes
    // not having any assigned port and group port
    List<TerracottaServer> nodes = configuration.tsa().getTopology().getServers();
    return (int) IntStream.concat(
        nodes.stream().mapToInt(TerracottaServer::getTsaPort),
        nodes.stream().mapToInt(TerracottaServer::getTsaGroupPort)
    ).filter(port -> port <= 0).count();
  }

  private static <T> Supplier<T> memoize(Supplier<T> supplier) {
    return new Supplier<T>() {
      T t;

      @Override
      public T get() {
        if (t == null) {
          t = supplier.get();
        }
        return t;
      }
    };
  }
}
