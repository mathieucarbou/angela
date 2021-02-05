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

package org.terracotta.angela.common;

import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.distribution.DistributionController;
import org.terracotta.angela.common.net.DisruptionProvider;
import org.terracotta.angela.common.net.DisruptionProviderFactory;
import org.terracotta.angela.common.net.Disruptor;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.zeroturnaround.process.Processes;

import java.io.Closeable;
import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Terracotta server instance
 *
 * @author Aurelien Broszniowski
 */
public class TerracottaServerInstance implements Closeable {
  private static final DisruptionProvider DISRUPTION_PROVIDER = DisruptionProviderFactory.getDefault();
  private final Map<ServerSymbolicName, Disruptor> disruptionLinks = new ConcurrentHashMap<>();
  private final Map<ServerSymbolicName, Integer> proxiedPorts = new HashMap<>();
  private final TerracottaServer terracottaServer;
  private final File kitDir;
  private final DistributionController distributionController;
  private final File workingDir;
  private final Distribution distribution;
  private final PortAllocator portAllocator;
  private final File licenseFileLocation;
  private volatile TerracottaServerInstanceProcess terracottaServerInstanceProcess;
  private final boolean netDisruptionEnabled;
  private final Topology topology;

  public TerracottaServerInstance(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                  License license, Distribution distribution, Topology topology,
                                  PortAllocator portAllocator) {
    this.terracottaServer = terracottaServer;
    this.kitDir = kitDir;
    this.distributionController = distribution.createDistributionController();
    this.workingDir = workingDir;
    this.distribution = distribution;
    this.portAllocator = portAllocator;
    this.licenseFileLocation = license == null ? null : new File(workingDir, license.getFilename());
    this.netDisruptionEnabled = topology.isNetDisruptionEnabled();
    this.topology = topology;
    constructLinks();
  }

  private void constructLinks() {
    if (netDisruptionEnabled) {
      topology.getConfigurationManager()
          .createDisruptionLinks(terracottaServer, DISRUPTION_PROVIDER, disruptionLinks, proxiedPorts, portAllocator);
    }
  }

  public Map<ServerSymbolicName, Integer> getProxiedPorts() {
    return proxiedPorts;
  }

  public Distribution getDistribution() {
    return distribution;
  }

  public void create(TerracottaCommandLineEnvironment env, Map<String, String> envOverrides, List<String> startUpArgs) {
    this.terracottaServerInstanceProcess = this.distributionController.createTsa(terracottaServer, kitDir, workingDir, topology, proxiedPorts, env, envOverrides, startUpArgs);
  }

  public void disrupt(Collection<TerracottaServer> targets) {
    if (!netDisruptionEnabled) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    for (TerracottaServer server : targets) {
      disruptionLinks.get(server.getServerSymbolicName()).disrupt();
    }
  }

  public void undisrupt(Collection<TerracottaServer> targets) {
    if (!netDisruptionEnabled) {
      throw new IllegalArgumentException("Topology not enabled for network disruption");
    }
    for (TerracottaServer target : targets) {
      disruptionLinks.get(target.getServerSymbolicName()).undisrupt();
    }
  }

  public void stop() {
    this.distributionController.stopTsa(terracottaServer.getServerSymbolicName(), terracottaServerInstanceProcess);
  }

  @Override
  public void close() {
    removeDisruptionLinks();
  }

  public ToolExecutionResult jcmd(TerracottaCommandLineEnvironment env, String... arguments) {
    return distributionController.invokeJcmd(terracottaServerInstanceProcess, env, arguments);
  }

  public void waitForState(Set<TerracottaServerState> terracottaServerStates) {
    boolean isStateSame = true;
    TerracottaServerState currentState = getTerracottaServerState();
    while (isStateSame) {
      try {
        Thread.sleep(100);

        isStateSame = this.terracottaServerInstanceProcess.isAlive();
        currentState = getTerracottaServerState();
        for (TerracottaServerState terracottaServerState : terracottaServerStates) {
          isStateSame &= (terracottaServerState != currentState);
        }
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!this.terracottaServerInstanceProcess.isAlive()) {
      StringBuilder states = new StringBuilder();
      for (TerracottaServerState terracottaServerState : terracottaServerStates) {
        states.append(terracottaServerState).append(" ");
      }
      throw new RuntimeException("The Terracotta server was in state " + currentState +
                                 " and was expected to reach one of the states: " + states.toString()
                                 + "but died before reaching it.");
    }
  }

  public TerracottaServerState getTerracottaServerState() {
    if (this.terracottaServerInstanceProcess == null) {
      return TerracottaServerState.STOPPED;
    } else {
      return this.terracottaServerInstanceProcess.getState();
    }
  }

  public File getKitDir() {
    return kitDir;
  }

  public File getWorkingDir() {
    return workingDir;
  }

  public File getLicenseFileLocation() {
    return licenseFileLocation;
  }

  public static class TerracottaServerInstanceProcess {
    private final AtomicReference<TerracottaServerState> state;
    private final Number wrapperPid;
    private final Number javaPid;

    public TerracottaServerInstanceProcess(AtomicReference<TerracottaServerState> state, Number wrapperPid, Number javaPid) {
      Objects.requireNonNull(wrapperPid, "wrapperPid cannot be null");
      if (wrapperPid.intValue() < 1 || (javaPid != null && javaPid.intValue() < 1)) {
        throw new IllegalArgumentException("Pid cannot be < 1");
      }
      this.wrapperPid = wrapperPid;
      this.javaPid = javaPid;
      this.state = state;
    }

    public TerracottaServerState getState() {
      return state.get();
    }

    public Set<Number> getPids() {
      Set<Number> pids = new HashSet<>();
      pids.add(wrapperPid);
      if (javaPid != null) {
        pids.add(javaPid);
      }
      return Collections.unmodifiableSet(pids);
    }

    public Number getJavaPid() {
      return javaPid;
    }

    public boolean isAlive() {
      try {
        // if at least one PID is alive, the process is considered alive
        return (wrapperPid != null && Processes.newPidProcess(wrapperPid.intValue()).isAlive()) ||
               (javaPid != null && Processes.newPidProcess(javaPid.intValue()).isAlive());
      } catch (Exception e) {
        throw new RuntimeException("Error checking liveness of a process instance with PIDs " + wrapperPid + " and " + javaPid, e);
      }
    }
  }

  private void removeDisruptionLinks() {
    if (netDisruptionEnabled) {
      disruptionLinks.values().forEach(DISRUPTION_PROVIDER::removeLink);
    }
  }
}
