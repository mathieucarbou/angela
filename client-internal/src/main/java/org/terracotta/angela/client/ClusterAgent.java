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

package org.terracotta.angela.client;

import java.io.IOException;
import java.util.Collections;
import org.apache.ignite.Ignite;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.net.DefaultPortAllocator;
import org.terracotta.angela.common.net.PortAllocator;

/**
 *
 */
public class ClusterAgent implements AutoCloseable {
  private final Agent localAgent;
  private final int igniteDiscoveryPort;
  private final int igniteComPort;
  private final PortAllocator portAllocator;

  public ClusterAgent(boolean localOnly) {
    this.portAllocator = new DefaultPortAllocator();
    this.localAgent = new Agent();
    if (localOnly) {
      this.igniteDiscoveryPort = 0;
      this.igniteComPort = 0;
      this.localAgent.startLocalCluster();
    } else {
      PortAllocator.PortReservation reservation = portAllocator.reserve(2);
      this.igniteDiscoveryPort = reservation.next();
      this.igniteComPort = reservation.next();
      this.localAgent.startCluster(Collections.singleton("localhost:" + igniteDiscoveryPort), "localhost:" + igniteDiscoveryPort, igniteDiscoveryPort, igniteComPort);
    }
  }

  public int getIgniteDiscoveryPort() {
    return igniteDiscoveryPort;
  }

  public int getIgniteComPort() {
    return igniteComPort;
  }

  public Ignite getIgnite() {
    return localAgent.getIgnite();
  }

  public PortAllocator getPortAllocator() {
    return portAllocator;
  }

  @Override
  public void close() throws IOException {
    try {
      localAgent.close();
    } finally {
      portAllocator.close();
    }
  }
}
