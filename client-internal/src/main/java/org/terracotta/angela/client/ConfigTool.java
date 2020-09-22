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

import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.ToolConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolException;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

public class ConfigTool implements AutoCloseable {
  private final static Logger logger = LoggerFactory.getLogger(ConfigTool.class);

  private final int ignitePort;
  private final ToolConfigurationContext configContext;
  private final Ignite ignite;
  private final InstanceId instanceId;
  private final LocalKitManager localKitManager;
  private final Tsa tsa;

  ConfigTool(Ignite ignite, InstanceId instanceId, int ignitePort, ToolConfigurationContext configContext, Tsa tsa) {
    this.ignite = ignite;
    this.instanceId = instanceId;
    this.ignitePort = ignitePort;
    this.configContext = configContext;
    this.localKitManager = new LocalKitManager(configContext.getDistribution());
    this.tsa = tsa;
    install();
  }

  public ToolExecutionResult executeCommand(String... arguments) {
    IgniteCallable<ToolExecutionResult> callable = () -> Agent.controller.configTool(instanceId, arguments);
    return IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
  }

  public void attachStripe(TerracottaServer... newServers) {
    if (newServers == null || newServers.length == 0) {
      throw new IllegalArgumentException("Servers list should be non-null and non-empty");
    }

    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    for (TerracottaServer server : newServers) {
      tsa.install(server, topology);
      tsa.start(server);
    }
    topology.addStripe(newServers);

    if (newServers.length > 1) {
      List<String> command = new ArrayList<>();
      command.add("attach");
      command.add("-t");
      command.add("node");
      command.add("-d");
      command.add(newServers[0].getHostPort());
      for (int i = 1; i < newServers.length; i++) {
        command.add("-s");
        command.add(newServers[i].getHostPort());
      }

      ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
      if (result.getExitStatus() != 0) {
        throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + result);
      }
    }

    List<String> command = new ArrayList<>();
    command.add("attach");
    command.add("-t");
    command.add("stripe");

    List<List<TerracottaServer>> stripes = topology.getStripes();
    TerracottaServer existingServer = stripes.get(0).get(0);
    command.add("-d");
    command.add(existingServer.getHostPort());
    for (TerracottaServer newServer : newServers) {
      command.add("-s");
      command.add(newServer.getHostPort());
    }

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("attach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
  }

  public void detachStripe(int stripeIndex) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    if (stripes.size() == 1) {
      throw new IllegalArgumentException("Cannot delete the only stripe from cluster");
    }

    List<String> command = new ArrayList<>();
    command.add("detach");
    command.add("-t");
    command.add("stripe");

    List<TerracottaServer> toDetachStripe = stripes.remove(stripeIndex);
    TerracottaServer destination = stripes.get(0).get(0);
    command.add("-d");
    command.add(destination.getHostPort());

    command.add("-s");
    command.add(toDetachStripe.get(0).getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("detach stripe failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }

    topology.removeStripe(stripeIndex);
  }

  public void attachNode(int stripeIndex, TerracottaServer newServer) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    if (newServer == null) {
      throw new IllegalArgumentException("Server should be non-null");
    }

    tsa.install(newServer, topology);
    tsa.start(newServer);
    topology.addServer(stripeIndex, newServer);

    List<String> command = new ArrayList<>();
    command.add("attach");
    command.add("-t");
    command.add("node");

    TerracottaServer existingServer = stripes.get(stripeIndex).get(0);
    command.add("-d");
    command.add(existingServer.getHostPort());

    command.add("-s");
    command.add(newServer.getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("attach node failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
  }

  public void detachNode(int stripeIndex, int serverIndex) {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    List<List<TerracottaServer>> stripes = topology.getStripes();
    if (stripeIndex < -1 || stripeIndex >= stripes.size()) {
      throw new IllegalArgumentException("stripeIndex should be a non-negative integer less than stripe count");
    }

    List<TerracottaServer> servers = stripes.remove(stripeIndex);
    if (serverIndex < -1 || serverIndex >= servers.size()) {
      throw new IllegalArgumentException("serverIndex should be a non-negative integer less than server count");
    }

    TerracottaServer toDetach = servers.remove(serverIndex);
    if (servers.size() == 0 && stripes.size() == 0) {
      throw new IllegalArgumentException("Cannot delete the only server from the cluster");
    }

    TerracottaServer destination;
    if (stripes.size() != 0) {
      destination = stripes.get(0).get(0);
    } else {
      destination = servers.get(0);
    }

    List<String> command = new ArrayList<>();
    command.add("detach");
    command.add("-t");
    command.add("node");
    command.add("-d");
    command.add(destination.getHostPort());
    command.add("-s");
    command.add(toDetach.getHostPort());

    ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("detach node failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }

    topology.removeServer(stripeIndex, serverIndex);
  }

  public void attachAll() {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    if (topology.isNetDisruptionEnabled()) {
      for (TerracottaServer terracottaServer : topology.getServers()) {
        setClientToServerDisruptionLinks(terracottaServer);
      }
    }
    List<List<TerracottaServer>> stripes = topology.getStripes();

    for (List<TerracottaServer> stripe : stripes) {
      if (stripe.size() > 1) {
        // Attach all servers in a stripe to form individual stripes
        for (int i = 1; i < stripe.size(); i++) {
          List<String> command = new ArrayList<>();
          command.add("attach");
          command.add("-t");
          command.add("node");
          command.add("-d");
          command.add(stripe.get(0).getHostPort());
          command.add("-s");
          command.add(stripe.get(i).getHostPort());

          ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
          if (result.getExitStatus() != 0) {
            throw new ToolException("attach failed", String.join(". ", result.getOutput()), result.getExitStatus());
          }
        }
      }
    }

    if (stripes.size() > 1) {
      for (int i = 1; i < stripes.size(); i++) {
        // Attach all stripes together to form the cluster
        List<String> command = new ArrayList<>();
        command.add("attach");
        command.add("-t");
        command.add("stripe");
        command.add("-d");
        command.add(stripes.get(0).get(0).getHostPort());

        List<TerracottaServer> stripe = stripes.get(i);
        command.add("-s");
        command.add(stripe.get(0).getHostPort());

        ToolExecutionResult result = executeCommand(command.toArray(new String[0]));
        if (result.getExitStatus() != 0) {
          throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + result);
        }
      }
    }

    if (topology.isNetDisruptionEnabled()) {
      for (int i = 1; i <= stripes.size(); ++i) {
        if (stripes.get(i - 1).size() > 1) {
          setServerToServerDisruptionLinks(i, stripes.get(i - 1).size());
        }
      }
    }
  }

  public void activate() {
    TerracottaServer terracottaServer = tsa.getTsaConfigurationContext().getTopology().getServers().get(0);
    logger.info("Activating cluster from {}", terracottaServer.getHostname());
    String clusterName = tsa.getTsaConfigurationContext().getClusterName();
    if (clusterName == null) {
      clusterName = instanceId.toString();
    }
    List<String> args = new ArrayList<>(Arrays.asList("activate", "-n", clusterName, "-s", terracottaServer.getHostPort()));
    String licensePath = tsa.licensePath(terracottaServer);
    if (licensePath != null) {
      args.add("-l");
      args.add(licensePath);
    }
    ToolExecutionResult result = executeCommand(args.toArray(new String[0]));
    if (result.getExitStatus() != 0) {
      throw new ToolException("activate failed", String.join(". ", result.getOutput()), result.getExitStatus());
    }
  }

  public void install() {
    Distribution distribution = configContext.getDistribution();
    License license = configContext.getLicense();
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());

    IgniteCallable<Boolean> callable = () -> Agent.controller.installConfigTool(instanceId, configContext.getHostName(),
        distribution, license, localKitManager.getKitInstallationName(), securityRootDirectory, tcEnv);
    boolean isRemoteInstallationSuccessful = IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
    if (!isRemoteInstallationSuccessful && (kitInstallationPath == null || !KIT_COPY.getBooleanValue())) {
      try {
        IgniteClientHelper.uploadKit(ignite, configContext.getHostName(), ignitePort, instanceId, distribution,
            localKitManager.getKitInstallationName(), localKitManager.getKitInstallationPath().toFile());
        IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
      } catch (Exception e) {
        throw new RuntimeException("Cannot upload kit to " + configContext.getHostName(), e);
      }
    }
  }

  public void uninstall() {
    IgniteRunnable uninstaller = () -> Agent.controller.uninstallConfigTool(instanceId, configContext.getDistribution(), configContext.getHostName(), localKitManager.getKitInstallationName());
    IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, uninstaller);
  }

  public void setClientToServerDisruptionLinks(TerracottaServer terracottaServer) {
    // Disabling client redirection from passive to current active.
    List<String> arguments = new ArrayList<>();
    String property = "stripe.1.node.1.tc-properties." + "l2.l1redirect.enabled=false";
    arguments.add("set");
    arguments.add("-s");
    arguments.add(terracottaServer.getHostPort());
    arguments.add("-c");
    arguments.add(property);
    ToolExecutionResult executionResult = executeCommand(arguments.toArray(new String[0]));
    if (executionResult.getExitStatus() != 0) {
      throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
    }

    // Creating disruption links for client to server disruption
    Map<ServerSymbolicName, Integer> proxyMap = tsa.updateToProxiedPorts();
    int proxyPort = proxyMap.get(terracottaServer.getServerSymbolicName());
    String publicHostName = "stripe.1.node.1.public-hostname=" + terracottaServer.getHostName();
    String publicPort = "stripe.1.node.1.public-port=" + proxyPort;

    List<String> args = new ArrayList<>();
    args.add("set");
    args.add("-s");
    args.add(terracottaServer.getHostPort());
    args.add("-c");
    args.add(publicHostName);
    args.add("-c");
    args.add(publicPort);

    executionResult = executeCommand(args.toArray(new String[0]));
    if (executionResult.getExitStatus() != 0) {
      throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
    }
  }

  public void setServerToServerDisruptionLinks(int stripeId, int size) {
    List<TerracottaServer> stripeServerList = tsa.getTsaConfigurationContext().getTopology().getStripes().get(stripeId - 1);
    for (int j = 0; j < size; ++j) {
      TerracottaServer server = stripeServerList.get(j);
      Map<ServerSymbolicName, Integer> proxyGroupPortMapping = tsa.getProxyGroupPortsForServer(server);
      int nodeId = j + 1;
      StringBuilder propertyBuilder = new StringBuilder();
      propertyBuilder.append("stripe.").append(stripeId).append(".node.").append(nodeId).append(".tc-properties.test-proxy-group-port=");
      propertyBuilder.append("\"");
      for (Map.Entry<ServerSymbolicName, Integer> entry : proxyGroupPortMapping.entrySet()) {
        propertyBuilder.append(entry.getKey().getSymbolicName());
        propertyBuilder.append("->");
        propertyBuilder.append(entry.getValue());
        propertyBuilder.append("#");
      }
      propertyBuilder.deleteCharAt(propertyBuilder.lastIndexOf("#"));
      propertyBuilder.append("\"");

      ToolExecutionResult executionResult = executeCommand("set", "-s", server.getHostPort(), "-c", propertyBuilder.toString());
      if (executionResult.getExitStatus() != 0) {
        throw new RuntimeException("ConfigTool::executeCommand with command parameters failed with: " + executionResult);
      }
    }
  }

  public RemoteFolder browse(String root) {
    String path = IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort,
        () -> Agent.controller.getConfigToolInstallPath(instanceId));
    return new RemoteFolder(ignite, configContext.getHostName(), ignitePort, path, root);
  }

  @Override
  public void close() {
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }
}