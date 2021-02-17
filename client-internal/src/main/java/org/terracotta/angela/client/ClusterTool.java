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
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.topology.InstanceId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;

public class ClusterTool implements AutoCloseable {
  private final static Logger logger = LoggerFactory.getLogger(ClusterTool.class);

  private final InstanceId instanceId;
  private final int ignitePort;
  private final Ignite ignite;
  private final ToolConfigurationContext configContext;
  private final LocalKitManager localKitManager;
  private final Tsa tsa;

  ClusterTool(Ignite ignite, InstanceId instanceId, int ignitePort, ToolConfigurationContext configContext, Tsa tsa) {
    this.instanceId = instanceId;
    this.ignitePort = ignitePort;
    this.ignite = ignite;
    this.configContext = configContext;
    this.localKitManager = new LocalKitManager(configContext.getDistribution());
    this.tsa = tsa;
    install();
  }

  public ToolExecutionResult executeCommand(String... arguments) {
    return executeCommand(Collections.emptyMap(), arguments);
  }

  public ToolExecutionResult executeCommand(Map<String, String> env, String... command) {
    IgniteCallable<ToolExecutionResult> callable = () -> Agent.controller.clusterTool(instanceId, env, command);
    return IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
  }

  public ClusterTool configure(Map<String, String> env) {
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();
    License license = tsa.getTsaConfigurationContext().getLicense();
    String clusterName = tsa.getTsaConfigurationContext().getClusterName();
    if (clusterName == null) {
      clusterName = instanceId.toString();
    }
    List<String> command = new ArrayList<>(Arrays.asList("configure", "-n", clusterName));
    IgniteCallable<ToolExecutionResult> callable = () -> Agent.controller.configure(instanceId, tsa.getTsaConfigurationContext().getTopology(), tsa.updateToProxiedPorts(), license, securityRootDirectory, tcEnv, env, command);
    ToolExecutionResult result = IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
    if(result.getExitStatus() != 0) {
      throw new IllegalStateException("Failed to execute cluster-tool configure:\n" + result.toString());
    }
    return this;
  }

  public ClusterTool configure() {
    return configure(Collections.emptyMap());
  }

  public ClusterTool install() {
    Distribution distribution = configContext.getDistribution();
    License license = tsa.getTsaConfigurationContext().getLicense();
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());

    IgniteCallable<Boolean> callable = () -> Agent.controller.installClusterTool(instanceId,
        configContext.getHostName(), distribution, license, localKitManager.getKitInstallationName(), securityRootDirectory, tcEnv, kitInstallationPath);
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
    return this;
  }

  public ClusterTool uninstall() {
    IgniteRunnable uninstaller = () -> Agent.controller.uninstallClusterTool(instanceId, configContext.getDistribution(), configContext
        .getHostName(), localKitManager.getKitInstallationName());
    IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, uninstaller);
    return this;
  }

  public RemoteFolder browse(String root) {
    String path = IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort,
        () -> Agent.controller.getClusterToolInstallPath(instanceId));
    return new RemoteFolder(ignite, configContext.getHostName(), ignitePort, path, root);
  }

  @Override
  public void close() {
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }
}