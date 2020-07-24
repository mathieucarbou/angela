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

package org.terracotta.angela.client.net;

import org.apache.ignite.Ignite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.DisruptionProviderFactory;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.DynamicConfigManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class DisruptionController implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(DisruptionController.class);
  private static final DisruptionProvider DISRUPTION_PROVIDER = DisruptionProviderFactory.getDefault();
  private final Ignite ignite;
  private final InstanceId instanceId;
  private final int ignitePort;
  private final Topology topology;
  private final Collection<Disruptor> existingDisruptors = new ArrayList<>();
  private final Map<ServerSymbolicName, Integer> proxyTsaPorts = new HashMap<>();
  private volatile boolean closed;

  public DisruptionController(Ignite ignite, InstanceId instanceId, int ignitePort, Topology topology) {
    this.ignite = ignite;
    this.instanceId = instanceId;
    this.ignitePort = ignitePort;
    this.topology = topology;
  }

  /**
   * Create disruptor to control traffic between all servers specified.
   * (ex: Server1 &lt;-&gt; Server2, Server2 &lt;-&gt; Server3 &amp; Server3 &lt;-&gt; Server1)
   *
   * @param servers to be disrupted
   * @return {@link ServerToServerDisruptor}
   */
  public ServerToServerDisruptor newServerToServerDisruptor(TerracottaServer... servers) {
    if (servers.length < 2) {
      throw new IllegalArgumentException("Two or more split clusters required for server to server disruption");
    }
    SplitCluster[] splitClusters = new SplitCluster[servers.length];
    for (int i = 0; i < servers.length; ++i) {
      splitClusters[i] = new SplitCluster(servers[i]);
    }
    return newServerToServerDisruptor(splitClusters);
  }


  /**
   * Create disruptor to control traffic between set of servers specified
   * (ex: [Server1] &lt;-&gt; [Server2,Server3]. No disruption between Server2 and Server3 in this example)
   *
   * @param splitClusters {@link SplitCluster}
   * @return {@link ServerToServerDisruptor}
   */
  public ServerToServerDisruptor newServerToServerDisruptor(SplitCluster... splitClusters) {
    if (!topology.isNetDisruptionEnabled()) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    if (closed) {
      throw new IllegalStateException("already closed");
    }
    if (splitClusters.length < 2) {
      throw new IllegalArgumentException("Two or more split clusters required for server to server disruption");
    }

    //
    for (SplitCluster splitCluster : splitClusters) {
      if (splitCluster.getServers().isEmpty()) {
        throw new IllegalArgumentException("Empty split cluster " + splitCluster);
      }
    }

    //validate for any duplicate server
    for (int i = 0; i < splitClusters.length; ++i) {
      for (int j = i + 1; j < splitClusters.length; ++j) {
        SplitCluster cluster1 = splitClusters[i];
        SplitCluster cluster2 = splitClusters[j];
        if (!Collections.disjoint(cluster1.getServers(), cluster2.getServers())) {
          throw new IllegalArgumentException("Duplicate servers found in split clusters { " + cluster1 + " } and { " + cluster2 + " }");
        }
      }
    }

    LOGGER.debug("new disruptor for {}", (Object) splitClusters);
    //compute servers to be linked for disruption based on input split clusters
    final Map<ServerSymbolicName, Collection<ServerSymbolicName>> linkedServers = new HashMap<>();
    for (int i = 0; i < splitClusters.length; ++i) {
      for (int j = i + 1; j < splitClusters.length; ++j) {
        SplitCluster cluster1 = splitClusters[i];
        SplitCluster cluster2 = splitClusters[j];
        for (ServerSymbolicName server : cluster1.getServers()) {
          linkedServers.computeIfAbsent(server, key -> new ArrayList<>()).addAll(cluster2.getServers());
        }
        for (ServerSymbolicName server : cluster2.getServers()) {
          linkedServers.computeIfAbsent(server, key -> new ArrayList<>()).addAll(cluster1.getServers());
        }
      }
    }


    //validate if any server to be linked is already linked
    final Set<ServerSymbolicName> alreadyLinkedServers = new HashSet<>();
    for (Map.Entry<ServerSymbolicName, Collection<ServerSymbolicName>> entry : linkedServers.entrySet()) {
      ServerSymbolicName server = entry.getKey();
      Collection<ServerSymbolicName> newConnected = entry.getValue();
      for (Disruptor disruption : existingDisruptors) {
        if (disruption instanceof ServerToServerDisruptor) {
          ServerToServerDisruptor serverToServerDisruptor = (ServerToServerDisruptor) disruption;
          Collection<ServerSymbolicName> alreadyConnected = serverToServerDisruptor.getLinkedServers().get(server);
          if (alreadyConnected != null && !Collections.disjoint(alreadyConnected, newConnected)) {
            alreadyLinkedServers.add(server);
          }
        }

      }
    }
    if (alreadyLinkedServers.size() > 0) {
      throw new IllegalArgumentException("Servers are already linked:" + alreadyLinkedServers);
    }


    ServerToServerDisruptor disruption = new ServerToServerDisruptor(ignite, ignitePort, instanceId, topology, linkedServers, existingDisruptors::remove);
    existingDisruptors.add(disruption);
    LOGGER.debug("created disruptor {}", disruption);
    return disruption;
  }

  /**
   * Create client to server disruptor for controlling traffic between
   * client like DatasetManager &amp; CacheManager and servers. This needs to
   * be created before initializing DatasetManager or CacheManager and use
   * connection URI obtained from this disruptor {@link ClientToServerDisruptor#uri()}
   *
   * @return {@link ClientToServerDisruptor}
   */
  public ClientToServerDisruptor newClientToServerDisruptor() {
    if (!topology.isNetDisruptionEnabled()) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    if (closed) {
      throw new IllegalStateException("already closed");
    }

    LOGGER.debug("creating new client to servers disruption");
    Optional<Disruptor> disruptor = existingDisruptors.stream()
        .filter(d -> d instanceof ClientToServerDisruptor)
        .findAny();
    if (DISRUPTION_PROVIDER.isProxyBased() && disruptor.isPresent()) {
      //make sure single disruptor serves all clients
      return (ClientToServerDisruptor) disruptor.get();
    } else {
      ClientToServerDisruptor newDisruptor = new ClientToServerDisruptor(topology, existingDisruptors::remove, proxyTsaPorts);
      existingDisruptors.add(newDisruptor);
      return newDisruptor;
    }
  }


  @Override
  public void close() throws Exception {
    LOGGER.debug("closing disruption controller");
    ArrayList<Disruptor> copy = new ArrayList<>(existingDisruptors);
    for (Disruptor disruption : copy) {
      disruption.close();
    }
    closed = true;
  }

  public Map<ServerSymbolicName, Integer> updateTsaPortsWithProxy(Topology topology, PortAllocator portAllocator) {
    Map<ServerSymbolicName, Integer> proxyMap = new HashMap<>();
    if (proxyTsaPorts.isEmpty()) {
      if (DISRUPTION_PROVIDER.isProxyBased()) {
        ConfigurationManager configurationProvider = topology.getConfigurationManager();
        if (configurationProvider instanceof TcConfigManager) {
          TcConfigManager tcConfigProvider = (TcConfigManager) configurationProvider;
          List<TcConfig> configs = tcConfigProvider.getTcConfigs();
          for (TcConfig config : configs) {
            TcConfig copy = TcConfig.copy(config);
            proxyTsaPorts.putAll(copy.retrieveTsaPorts(true, portAllocator));
            proxyMap.putAll(proxyTsaPorts);
          }
        } else {
          DynamicConfigManager dynamicConfigManager = (DynamicConfigManager) configurationProvider;
          List<TerracottaServer> servers = dynamicConfigManager.getServers();
          PortAllocator.PortReservation reservation = portAllocator.reserve(servers.size());
          for (TerracottaServer terracottaServer : servers) {
            proxyTsaPorts.put(terracottaServer.getServerSymbolicName(), reservation.next());
          }
          proxyMap.putAll(proxyTsaPorts);
        }
        ClientToServerDisruptor newDisruptor = new ClientToServerDisruptor(topology, existingDisruptors::remove, proxyTsaPorts);
        existingDisruptors.add(newDisruptor);
      }
    } else {
      proxyMap.putAll(proxyTsaPorts);
    }
    return proxyMap;
  }

  public Map<ServerSymbolicName, Integer> getProxyTsaPorts() {
    return proxyTsaPorts;
  }

}
