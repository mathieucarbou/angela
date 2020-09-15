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

import org.apache.ignite.lang.IgniteCallable;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;

import java.util.Map;

import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;

public abstract class Tool implements AutoCloseable {
  protected final Tsa tsa;

  public Tool(Tsa tsa) {
    this.tsa = tsa;
  }

  @Override
  public void close() {
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }

  public abstract ToolExecutionResult executeCommand(String... arguments);

  protected abstract void install();

  protected abstract void uninstall();

  protected Map<ServerSymbolicName, Integer> updateToProxiedPorts() {
    return tsa.getDisruptionController().updateTsaPortsWithProxy(tsa.getTsaConfigurationContext().getTopology(), tsa.getPortAllocator());
  }

  protected Map<ServerSymbolicName, Integer> getProxyGroupPortsForServer(TerracottaServer terracottaServer) {
    IgniteCallable<Map<ServerSymbolicName, Integer>> callable = () -> Agent.controller.getProxyGroupPortsForServer(tsa.getInstanceId(), terracottaServer);
    return IgniteClientHelper.executeRemotely(tsa.getIgnite(), terracottaServer.getHostname(), tsa.getIgnitePort(), callable);
  }
}
