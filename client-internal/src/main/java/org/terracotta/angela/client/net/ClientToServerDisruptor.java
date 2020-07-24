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

import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.DisruptionProviderFactory;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.DisruptorState;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.HostPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Disruptor to control traffic between client and servers. This needs to
 * be created before initializing client(DatasetManager &amp; CacheManager) and use
 * connection URI obtained from this disruptor {@link #uri()}
 * <br>
 * <br>
 * This disruptor doesn't work with TopoConnectionService since topology configuration overrides proxy url before making
 * actual connection. So this disruption requires a test connection service(similar to DelayedConnectionService found in galvan)
 * to be created to skip topolgoy configuration or delegate to LeasedConnectionServiceImpl.
 */
public class ClientToServerDisruptor implements Disruptor {
  private static final Logger LOGGER = LoggerFactory.getLogger(ClientToServerDisruptor.class);
  private static final DisruptionProvider DISRUPTION_PROVIDER = DisruptionProviderFactory.getDefault();

  private final Map<ServerSymbolicName, Disruptor> links = new HashMap<>();
  private final Map<ServerSymbolicName, InetSocketAddress> endPoints = new HashMap<>();
  private final Consumer<Disruptor> closeHook;
  private volatile DisruptorState state;

  ClientToServerDisruptor(Topology topology, Consumer<Disruptor> closeHook, Map<ServerSymbolicName, Integer> proxiedTsaPorts) {
    this.closeHook = closeHook;
    for (TerracottaServer server : topology.getServers()) {
      final InetSocketAddress clientEndPoint = DISRUPTION_PROVIDER.isProxyBased() ? null : new InetSocketAddress("localhost", -1);
      final InetSocketAddress proxyEndPoint = DISRUPTION_PROVIDER.isProxyBased() ? new InetSocketAddress("localhost", proxiedTsaPorts
          .get(server.getServerSymbolicName())) : null;
      final InetSocketAddress serverEndPoint = new InetSocketAddress(server.getHostname(), server.getTsaPort());

      endPoints.putIfAbsent(server.getServerSymbolicName(), DISRUPTION_PROVIDER.isProxyBased() ? proxyEndPoint : serverEndPoint);
      LOGGER.debug("Server {} endpoint {}", server.getServerSymbolicName()
          .getSymbolicName(), endPoints.get(server.getServerSymbolicName()));
      links.computeIfAbsent(server.getServerSymbolicName(), s -> DISRUPTION_PROVIDER.createLink(DISRUPTION_PROVIDER.isProxyBased() ? proxyEndPoint : clientEndPoint, serverEndPoint));
    }
    state = DisruptorState.UNDISRUPTED;
  }


  public void disrupt(Collection<ServerSymbolicName> servers) {
    if (state != DisruptorState.UNDISRUPTED) {
      throw new IllegalStateException("Illegal state before disrupt: " + state);
    }

    LOGGER.debug("disrupting client to servers");
    for (ServerSymbolicName server : servers) {
      links.get(server).disrupt();
    }
    state = DisruptorState.DISRUPTED;
  }

  @Override
  public void disrupt(){
    disrupt(links.keySet());
  }

  @Override
  public void undisrupt() {
    if (state != DisruptorState.DISRUPTED) {
      throw new IllegalStateException("Illegal state before undisrupt: " + state);
    }

    LOGGER.debug("undisrupting client to servers");
    undisrupt(links.keySet());
    state = DisruptorState.UNDISRUPTED;
  }

  public void undisrupt(Collection<ServerSymbolicName> servers) {
    for (ServerSymbolicName server : servers) {
      links.get(server).undisrupt();
    }
    state = DisruptorState.UNDISRUPTED;
  }
  
  @Override
  public void close() {
    if (state == DisruptorState.DISRUPTED) {
      undisrupt();
    }
    if (state == DisruptorState.UNDISRUPTED) {
      links.values().forEach(DISRUPTION_PROVIDER::removeLink);
      closeHook.accept(this);
      state = DisruptorState.CLOSED;
    }
  }

  public URI uri() {
    return URI.create("terracotta://" + getHostPortList());
  }

  public URI uri(TerracottaServer... servers) {
    return URI.create("terracotta://" + getHostPortList(servers));
  }

  public URI diagnosticURI(TerracottaServer server) {
    return URI.create("diagnostics://" + getHostPortList(server));
  }

  private String getHostPortList() {
    return getHostPortList(endPoints.keySet());
  }

  private String getHostPortList(TerracottaServer... servers) {
    return getHostPortList(Arrays.stream(servers)
        .map(TerracottaServer::getServerSymbolicName)
        .collect(Collectors.toList()));
  }

  private String getHostPortList(Collection<ServerSymbolicName> servers) {
    return servers.stream()
        .map(endPoints::get)
        .map(s -> new HostPort(s.getHostName(), s.getPort()).getHostPort())
        .collect(Collectors.joining(","));
  }

}
