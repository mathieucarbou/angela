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

import org.apache.commons.io.FileUtils;
import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.ToolConfigurationContext;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

  public ToolExecutionResult executeCommand(String... command) {
    IgniteCallable<ToolExecutionResult> callable = () -> Agent.controller.clusterTool(instanceId, command);
    return IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);
  }

  public void configure() {
    Topology topology = tsa.getTsaConfigurationContext().getTopology();
    TerracottaServer terracottaServer = topology.getConfigurationManager().getServers().get(0);
    logger.info("Configuring cluster from {}", terracottaServer.getHostname());
    String clusterName = tsa.getTsaConfigurationContext().getClusterName();
    if (clusterName == null) {
      clusterName = instanceId.toString();
    }
    String licensePath = tsa.licensePath(terracottaServer);
    File tmpConfigDir = new File(FileUtils.getTempDirectory(), "tmp-tc-configs");

    if (!tmpConfigDir.mkdir() && !tmpConfigDir.isDirectory()) {
      throw new RuntimeException("Error creating temporary cluster tool TC config folder : " + tmpConfigDir);
    }
    ConfigurationManager configurationProvider = topology.getConfigurationManager();
    TcConfigManager tcConfigProvider = (TcConfigManager) configurationProvider;
    List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
    List<TcConfig> modifiedConfigs = new ArrayList<>();
    for (TcConfig tcConfig : tcConfigs) {
      TcConfig modifiedConfig = TcConfig.copy(tcConfig);
      if (topology.isNetDisruptionEnabled()) {
        modifiedConfig.updateServerTsaPort(tsa.updateToProxiedPorts());
      }
      modifiedConfig.writeTcConfigFile(tmpConfigDir);
      modifiedConfigs.add(modifiedConfig);
    }

    List<String> command = new ArrayList<>(Arrays.asList("configure", "-n", clusterName, "-l", licensePath));
    for (TcConfig tcConfig : modifiedConfigs) {
      command.add(tcConfig.getPath());
    }
    executeCommand(command.toArray(new String[0]));
  }

  public void install() {
    Distribution distribution = configContext.getDistribution();
    License license = configContext.getLicense();
    TerracottaCommandLineEnvironment tcEnv = configContext.getCommandLineEnv();
    SecurityRootDirectory securityRootDirectory = configContext.getSecurityRootDirectory();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());

    IgniteCallable<Boolean> callable = () -> Agent.controller.installClusterTool(instanceId, configContext.getHostName(),
        distribution, license, localKitManager.getKitInstallationName(), securityRootDirectory, tcEnv);
    boolean isRemoteInstallationSuccessful = kitInstallationPath == null && IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, callable);

    if (!isRemoteInstallationSuccessful) {
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
    IgniteRunnable uninstaller = () -> Agent.controller.uninstallClusterTool(instanceId, configContext.getDistribution(), configContext.getHostName(), localKitManager.getKitInstallationName());
    IgniteClientHelper.executeRemotely(ignite, configContext.getHostName(), ignitePort, uninstaller);
  }

  @Override
  public void close() {
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstall();
    }
  }
}