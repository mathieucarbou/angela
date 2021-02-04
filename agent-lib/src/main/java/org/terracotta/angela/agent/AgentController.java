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

import org.apache.commons.io.IOUtils;
import org.apache.ignite.Ignite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.client.RemoteClientManager;
import org.terracotta.angela.agent.kit.MonitoringInstance;
import org.terracotta.angela.agent.kit.RemoteKitManager;
import org.terracotta.angela.agent.kit.TerracottaInstall;
import org.terracotta.angela.agent.kit.TmsInstall;
import org.terracotta.angela.agent.kit.ToolInstall;
import org.terracotta.angela.agent.kit.VoterInstall;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.TerracottaServerInstance;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.TerracottaToolInstance;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance;
import org.terracotta.angela.common.TerracottaVoterState;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.distribution.DistributionController;
import org.terracotta.angela.common.metrics.HardwareMetric;
import org.terracotta.angela.common.metrics.MonitoringCommand;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.tms.security.config.TmsServerSecurityConfig;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.FileMetadata;
import org.terracotta.angela.common.util.FileUtils;
import org.terracotta.angela.common.util.IgniteCommonHelper;
import org.terracotta.angela.common.util.ProcessUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static java.util.stream.Collectors.toList;
import static org.terracotta.angela.common.util.FileUtils.setCorrectPermissions;

/**
 * @author Aurelien Broszniowski
 */
public class AgentController {
  private final static Logger logger = LoggerFactory.getLogger(AgentController.class);

  private final Map<InstanceId, TerracottaInstall> tsaInstalls = new HashMap<>();
  private final Map<InstanceId, TmsInstall> tmsInstalls = new HashMap<>();
  private final Map<InstanceId, VoterInstall> voterInstalls = new HashMap<>();
  private final Map<InstanceId, ToolInstall> clusterToolInstalls = new HashMap<>();
  private final Map<InstanceId, ToolInstall> configToolInstalls = new HashMap<>();

  private final Ignite ignite;
  private final Collection<String> joinedNodes;
  private final int ignitePort;
  private final PortAllocator portAllocator;
  private volatile MonitoringInstance monitoringInstance;

  AgentController(Ignite ignite, Collection<String> joinedNodes, int ignitePort, PortAllocator portAllocator) {
    this.ignite = ignite;
    this.joinedNodes = Collections.unmodifiableList(new ArrayList<>(joinedNodes));
    this.ignitePort = ignitePort;
    this.portAllocator = portAllocator;
  }

  public boolean installTsa(InstanceId instanceId,
                            TerracottaServer terracottaServer,
                            License license,
                            String kitInstallationName,
                            Distribution distribution,
                            Topology topology, String kitInstallationPath) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);

    File kitLocation;
    File workingDir;
    if (terracottaInstall == null || !terracottaInstall.installed(distribution)) {
      if (kitInstallationPath == null) {

        RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
        if (!kitManager.isKitAvailable()) {
          return false;
        }

        logger.info("Installing kit for {} from {}", terracottaServer, distribution);
        kitLocation = kitManager.installKit(license, topology.getServersHostnames());
        workingDir = kitManager.getWorkingDir().toFile();
        terracottaInstall = tsaInstalls.computeIfAbsent(instanceId, (iid) -> new TerracottaInstall(workingDir, portAllocator));
      } else {
        // DO NOT ALTER THE KIT CONTENT IF kitInstallationPath IS USED
        kitLocation = new File(kitInstallationPath);
        Path workingPath = Agent.WORK_DIR.resolve(instanceId.toString());
        try {
          Files.createDirectories(workingPath);
        } catch (IOException e) {
          logger.debug("Can not create {}", workingPath, e);
        }
        workingDir = workingPath.toFile();

        terracottaInstall = tsaInstalls.computeIfAbsent(instanceId, (iid) -> new TerracottaInstall(new File(kitInstallationPath), portAllocator));
      }
    } else {
      kitLocation = terracottaInstall.kitLocation(distribution);
      workingDir = terracottaInstall.installLocation(distribution);
      logger.info("Kit for {} already installed", terracottaServer);
    }

    terracottaInstall.addServer(terracottaServer, kitLocation, workingDir, license, distribution, topology);

    return true;
  }

  public String getTsaInstallPath(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    TerracottaServerInstance terracottaServerInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    if (terracottaServerInstance == null) {
      throw new IllegalStateException("Server " + terracottaServer + " has not been installed");
    }
    return terracottaInstall.getInstallLocation(terracottaServer).getPath();
  }

  public String getConfigToolInstallPath(InstanceId instanceId) {
    ToolInstall toolInstall = configToolInstalls.get(instanceId);
    TerracottaToolInstance configToolInstance = toolInstall.getInstance();
    if (configToolInstance == null) {
      throw new IllegalStateException("Config tool has not been installed");
    }
    return toolInstall.getWorkingDir().getPath();
  }

  public String getClusterToolInstallPath(InstanceId instanceId) {
    ToolInstall toolInstall = clusterToolInstalls.get(instanceId);
    TerracottaToolInstance clusterToolInstance = toolInstall.getInstance();
    if (clusterToolInstance == null) {
      throw new IllegalStateException("Cluster tool has not been installed");
    }
    return toolInstall.getWorkingDir().getPath();
  }

  public String getTsaKitLocation(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    TerracottaServerInstance terracottaServerInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    if (terracottaServerInstance == null) {
      throw new IllegalStateException("Server " + terracottaServer + " has not been installed");
    }
    return terracottaInstall.getKitLocation(terracottaServer).getPath();
  }

  public String getTsaLicensePath(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    if (terracottaInstall == null) {
      throw new IllegalStateException("Server has not been installed");
    }
    File licenseFileLocation = terracottaInstall.getLicenseFileLocation(terracottaServer);
    return licenseFileLocation == null ? null : licenseFileLocation.getPath();
  }

  public boolean installTms(InstanceId instanceId, String tmsHostname, Distribution distribution, License license,
                            TmsServerSecurityConfig tmsServerSecurityConfig, String kitInstallationName,
                            TerracottaCommandLineEnvironment tcEnv, String hostName, String kitInstallationPath) {
    TmsInstall tmsInstall = tmsInstalls.get(instanceId);
    if (tmsInstall != null) {
      logger.debug("Kit for " + tmsHostname + " already installed");
      tmsInstall.addTerracottaManagementServer();
      return true;

    } else {

      Optional<Dirs> dirs = Dirs.discover(instanceId, hostName, distribution, license, kitInstallationName, kitInstallationPath);
      if (!dirs.isPresent()) {
        return false;
      }

      // DO NOT ALTER THE KIT CONTENT IF kitInstallationPath IS USED
      if (kitInstallationPath == null) {
        File tmcProperties = new File(dirs.get().kitDir, "/tools/management/conf/tmc.properties");
        if (tmsServerSecurityConfig != null) {
          enableTmsSecurity(tmcProperties, tmsServerSecurityConfig);
        }
      }

      tmsInstalls.put(instanceId, new TmsInstall(distribution, dirs.get().kitDir, dirs.get().workingDir, tcEnv));
      return true;
    }
  }

  public boolean installVoter(InstanceId instanceId, TerracottaVoter terracottaVoter, Distribution distribution,
                              License license, String kitInstallationName, SecurityRootDirectory securityRootDirectory,
                              TerracottaCommandLineEnvironment tcEnv, String kitInstallationPath) {
    VoterInstall voterInstall = voterInstalls.get(instanceId);

    if (voterInstall == null) {

      Optional<Dirs> dirs = Dirs.discover(instanceId, terracottaVoter.getHostName(), distribution, license, kitInstallationName, kitInstallationPath);
      if (!dirs.isPresent()) {
        return false;
      }

      voterInstall = voterInstalls.computeIfAbsent(instanceId, (id) -> new VoterInstall(distribution, dirs.get().kitDir, dirs.get().workingDir, securityRootDirectory, tcEnv));
    }

    voterInstall.addVoter(terracottaVoter);

    return true;
  }

  public boolean installClusterTool(InstanceId instanceId, String hostName, Distribution distribution,
                                    License license, String kitInstallationName, SecurityRootDirectory securityRootDirectory,
                                    TerracottaCommandLineEnvironment tcEnv, String kitInstallationPath) {
    ToolInstall clusterToolInstall = clusterToolInstalls.get(instanceId);
    if (clusterToolInstall == null) {

      Optional<Dirs> dirs = Dirs.discover(instanceId, hostName, distribution, license, kitInstallationName, kitInstallationPath);
      if (!dirs.isPresent()) {
        return false;
      }

      clusterToolInstalls.computeIfAbsent(instanceId, id -> {
        BiFunction<Map<String, String>, String[], ToolExecutionResult> operation = (env, command) -> {
          DistributionController distributionController = distribution.createDistributionController();
          return distributionController.invokeClusterTool(dirs.get().kitDir, dirs.get().workingDir, securityRootDirectory, tcEnv, env, command);
        };
        return new ToolInstall(dirs.get().kitDir, dirs.get().workingDir, distribution, operation);
      });
    }
    return true;
  }

  public boolean installConfigTool(InstanceId instanceId, String hostName, Distribution distribution,
                                   License license, String kitInstallationName, SecurityRootDirectory securityRootDirectory,
                                   TerracottaCommandLineEnvironment tcEnv, String kitInstallationPath) {
    ToolInstall configToolInstall = configToolInstalls.get(instanceId);

    if (configToolInstall == null) {

      Optional<Dirs> dirs = Dirs.discover(instanceId, hostName, distribution, license, kitInstallationName, kitInstallationPath);
      if (!dirs.isPresent()) {
        return false;
      }

      configToolInstalls.computeIfAbsent(instanceId, id -> {
        BiFunction<Map<String, String>, String[], ToolExecutionResult> operation = (env, command) -> {
          DistributionController distributionController = distribution.createDistributionController();
          return distributionController.invokeConfigTool(dirs.get().kitDir, dirs.get().workingDir, securityRootDirectory, tcEnv, env, command);
        };
        return new ToolInstall(dirs.get().kitDir, dirs.get().workingDir, distribution, operation);
      });
    }
    return true;
  }

  private void enableTmsSecurity(File tmcProperties, TmsServerSecurityConfig tmsServerSecurityConfig) {
    Properties properties = new Properties();

    try (InputStream inputStream = new FileInputStream(tmcProperties)) {
      properties.load(inputStream);
    } catch (Exception ex) {
      throw new RuntimeException("Unable to enable security in TMS tmc.properties file", ex);
    }

    tmsServerSecurityConfig.toMap().forEach((key, value) -> {
      if (value == null) {
        properties.remove(key);
      } else {
        properties.put(key, value);
      }
    });

    try (OutputStream outputStream = new FileOutputStream(tmcProperties)) {
      properties.store(outputStream, null);
    } catch (Exception ex) {
      throw new RuntimeException("Unable to enable security in TMS tmc.properties file", ex);
    }
  }

  private void disableTmsSecurity(File tmcProperties, TmsServerSecurityConfig tmsServerSecurityConfig) {
    Properties properties = new Properties();

    try (InputStream inputStream = new FileInputStream(tmcProperties)) {
      properties.load(inputStream);
    } catch (Exception ex) {
      throw new RuntimeException("Unable to disable security in TMS tmc.properties file", ex);
    }

    tmsServerSecurityConfig.toMap().forEach((key, value) -> properties.remove(key));

    try (OutputStream outputStream = new FileOutputStream(tmcProperties)) {
      properties.store(outputStream, null);
    } catch (Exception ex) {
      throw new RuntimeException("Unable to disable security in TMS tmc.properties file", ex);
    }
  }

  public void startTms(InstanceId instanceId, Map<String, String> envOverrides) {
    TerracottaManagementServerInstance serverInstance = tmsInstalls.get(instanceId)
        .getTerracottaManagementServerInstance();
    serverInstance.start(envOverrides);
  }

  public void stopTms(InstanceId instanceId) {
    TerracottaManagementServerInstance serverInstance = tmsInstalls.get(instanceId)
        .getTerracottaManagementServerInstance();
    serverInstance.stop();
  }

  public String getTmsInstallationPath(InstanceId instanceId) {
    TmsInstall serverInstance = tmsInstalls.get(instanceId);
    return serverInstance.getWorkingDir().getPath();
  }

  public TerracottaManagementServerState getTmsState(InstanceId instanceId) {
    TmsInstall terracottaInstall = tmsInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return TerracottaManagementServerState.NOT_INSTALLED;
    }
    TerracottaManagementServerInstance serverInstance = terracottaInstall.getTerracottaManagementServerInstance();
    if (serverInstance == null) {
      return TerracottaManagementServerState.NOT_INSTALLED;
    }
    return serverInstance.getTerracottaManagementServerState();
  }

  public void uninstallTsa(InstanceId instanceId, Topology topology, TerracottaServer terracottaServer, String kitInstallationName, String kitInstallationPath) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    if (terracottaInstall != null) {
      int installationsCount = terracottaInstall.removeServer(terracottaServer);
      if (installationsCount == 0) {
        File installLocation = terracottaInstall.getRootInstallLocation();
        logger.info("Uninstalling kit(s) from {} for TSA", installLocation);
        RemoteKitManager kitManager = new RemoteKitManager(instanceId, topology.getDistribution(), kitInstallationName);
        if (kitInstallationPath == null) {
          kitManager.deleteInstall(installLocation);
        }
        tsaInstalls.remove(instanceId);
      } else {
        logger.info("Kit installation still in use by {} instances. Skipping uninstall", tsaInstalls.size());
      }
    } else {
      logger.info("No installed kit for " + topology);
    }
  }

  public void uninstallTms(InstanceId instanceId, Distribution distribution, TmsServerSecurityConfig tmsServerSecurityConfig,
                           String kitInstallationName, String tmsHostname, String kitInstallationPath) {
    TmsInstall tmsInstall = tmsInstalls.get(instanceId);
    if (tmsInstall != null) {
      // DO NOT ALTER THE KIT CONTENT IF kitInstallationPath IS USED
      if (kitInstallationPath == null) {
        File tmcProperties = new File(tmsInstall.getKitLocation(), "/tools/management/conf/tmc.properties");
        if (tmsServerSecurityConfig != null) {
          disableTmsSecurity(tmcProperties, tmsServerSecurityConfig);
        }
      }
      tmsInstall.removeServer();
      tmsInstalls.remove(instanceId);
      File installLocation = tmsInstall.getWorkingDir();
      logger.info("Uninstalling kit(s) from {} for TMS", installLocation);
      RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
      kitManager.deleteInstall(installLocation);
    } else {
      logger.info("No installed kit for " + tmsHostname);
    }
  }

  public void uninstallVoter(InstanceId instanceId, Distribution distribution, TerracottaVoter terracottaVoter, String kitInstallationName) {
    VoterInstall voterInstall = voterInstalls.get(instanceId);
    if (voterInstall != null) {
      int installationsCount = voterInstall.removeVoter(terracottaVoter);
      if (installationsCount == 0) {
        File installLocation = voterInstall.getWorkingDir();
        logger.info("Uninstalling kit(s) from {} for voter", installLocation);
        RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
        kitManager.deleteInstall(installLocation);
        voterInstalls.remove(instanceId);
      } else {
        logger.info("Kit installation still in use by {} voter instances. Skipping uninstall", voterInstalls.size());
      }
    } else {
      logger.info("No installed kit for " + terracottaVoter.getHostName());
    }
  }

  public void uninstallClusterTool(InstanceId instanceId, Distribution distribution, String hostName, String kitInstallationName) {
    ToolInstall clusterToolInstall = clusterToolInstalls.get(instanceId);
    if (clusterToolInstall != null) {
      clusterToolInstalls.remove(instanceId);
      File installLocation = clusterToolInstall.getWorkingDir();
      logger.info("Uninstalling kit(s) from {} for cluster tool", installLocation);
      RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
      kitManager.deleteInstall(installLocation);
    } else {
      logger.info("No installed kit for " + hostName);
    }
  }

  public void uninstallConfigTool(InstanceId instanceId, Distribution distribution, String hostName, String kitInstallationName) {
    ToolInstall configToolInstall = configToolInstalls.get(instanceId);
    if (configToolInstall != null) {
      configToolInstalls.remove(instanceId);
      File installLocation = configToolInstall.getWorkingDir();
      logger.info("Uninstalling kit(s) from {} for config tool", installLocation);
      RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
      kitManager.deleteInstall(installLocation);
    } else {
      logger.info("No installed kit for " + hostName);
    }
  }

  public void createTsa(InstanceId instanceId, TerracottaServer terracottaServer, TerracottaCommandLineEnvironment tcEnv, Map<String, String> envOverrides, List<String> startUpArgs) {
    TerracottaServerInstance serverInstance = tsaInstalls.get(instanceId).getTerracottaServerInstance(terracottaServer);
    serverInstance.create(tcEnv, envOverrides, startUpArgs);
  }

  public void stopTsa(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return;
    }
    TerracottaServerInstance serverInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    serverInstance.stop();
  }

  public void waitForTsaInState(InstanceId instanceId, TerracottaServer terracottaServer, Set<TerracottaServerState> wanted) {
    TerracottaServerInstance serverInstance = tsaInstalls.get(instanceId).getTerracottaServerInstance(terracottaServer);
    serverInstance.waitForState(wanted);
  }

  public ToolExecutionResult configure(InstanceId instanceId, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, License license, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment tcEnv, Map<String, String> env, List<String> command) {
    ToolInstall clusterToolInstall = clusterToolInstalls.get(instanceId);
    if (clusterToolInstall == null) {
      throw new IllegalStateException("Cluster tool has not been installed");
    }
    DistributionController distributionController = clusterToolInstall.getDistribution().createDistributionController();
    return distributionController.configureCluster(clusterToolInstall.getKitDir(), clusterToolInstall.getWorkingDir(), topology, proxyTsaPorts, license, securityRootDirectory, tcEnv, env, command.toArray(new String[0]));
  }

  public ToolExecutionResult activate(InstanceId instanceId, License license, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment tcEnv, Map<String, String> env, List<String> command) {
    ToolInstall configToolInstall = configToolInstalls.get(instanceId);
    if (configToolInstall == null) {
      throw new IllegalStateException("Config tool has not been installed");
    }
    DistributionController distributionController = configToolInstall.getDistribution().createDistributionController();
    return distributionController.activateCluster(configToolInstall.getKitDir(), configToolInstall.getWorkingDir(), license, securityRootDirectory, tcEnv, env, command.toArray(new String[0]));
  }

  public TerracottaServerState getTsaState(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return TerracottaServerState.NOT_INSTALLED;
    }
    TerracottaServerInstance serverInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    if (serverInstance == null) {
      return TerracottaServerState.NOT_INSTALLED;
    }
    return serverInstance.getTerracottaServerState();
  }

  public Map<ServerSymbolicName, Integer> getProxyGroupPortsForServer(InstanceId instanceId, TerracottaServer terracottaServer) {
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    if (terracottaInstall == null) {
      return Collections.emptyMap();
    }
    TerracottaServerInstance serverInstance = terracottaInstall.getTerracottaServerInstance(terracottaServer);
    if (serverInstance == null) {
      return Collections.emptyMap();
    }
    return serverInstance.getProxiedPorts();
  }

  public void disrupt(InstanceId instanceId, TerracottaServer src, TerracottaServer target) {
    disrupt(instanceId, src, Collections.singleton(target));
  }

  public void disrupt(InstanceId instanceId, TerracottaServer src, Collection<TerracottaServer> targets) {
    TerracottaServerInstance serverInstance = tsaInstalls.get(instanceId).getTerracottaServerInstance(src);
    serverInstance.disrupt(targets);
  }

  public void undisrupt(InstanceId instanceId, TerracottaServer src, TerracottaServer target) {
    undisrupt(instanceId, src, Collections.singleton(target));
  }

  public void undisrupt(InstanceId instanceId, TerracottaServer src, Collection<TerracottaServer> targets) {
    TerracottaServerInstance serverInstance = tsaInstalls.get(instanceId).getTerracottaServerInstance(src);
    serverInstance.undisrupt(targets);
  }

  public TerracottaVoterState getVoterState(InstanceId instanceId, TerracottaVoter terracottaVoter) {
    VoterInstall voterInstall = voterInstalls.get(instanceId);
    if (voterInstall == null) {
      return TerracottaVoterState.NOT_INSTALLED;
    }
    TerracottaVoterInstance terracottaVoterInstance = voterInstall.getTerracottaVoterInstance(terracottaVoter);
    if (terracottaVoterInstance == null) {
      return TerracottaVoterState.NOT_INSTALLED;
    }
    return terracottaVoterInstance.getTerracottaVoterState();
  }

  public void startVoter(InstanceId instanceId, TerracottaVoter terracottaVoter, Map<String, String> envOverrides) {
    TerracottaVoterInstance terracottaVoterInstance = voterInstalls.get(instanceId)
        .getTerracottaVoterInstance(terracottaVoter);
    terracottaVoterInstance.start(envOverrides);
  }

  public void stopVoter(InstanceId instanceId, TerracottaVoter terracottaVoter) {
    TerracottaVoterInstance terracottaVoterInstance = voterInstalls.get(instanceId).getTerracottaVoterInstance(terracottaVoter);
    terracottaVoterInstance.stop();
  }

  public ToolExecutionResult clusterTool(InstanceId instanceId, Map<String, String> env, String... command) {
    ToolInstall clusterToolInstall = clusterToolInstalls.get(instanceId);
    if (clusterToolInstall == null) {
      throw new IllegalStateException("Cluster tool has not been installed");
    }
    return clusterToolInstall.getInstance().execute(env, command);
  }

  public ToolExecutionResult configTool(InstanceId instanceId, Map<String, String> env, String... command) {
    ToolInstall configToolInstall = configToolInstalls.get(instanceId);
    if (configToolInstall == null) {
      throw new IllegalStateException("Config tool has not been installed");
    }
    return configToolInstall.getInstance().execute(env, command);
  }

  public ToolExecutionResult serverJcmd(InstanceId instanceId, TerracottaServer terracottaServer, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    TerracottaServerState tsaState = getTsaState(instanceId, terracottaServer);
    if (!EnumSet.of(TerracottaServerState.STARTED_AS_ACTIVE, TerracottaServerState.STARTED_AS_PASSIVE)
        .contains(tsaState)) {
      throw new IllegalStateException("Cannot control jcmd: server " + terracottaServer.getServerSymbolicName() + " has not started");
    }
    TerracottaInstall terracottaInstall = tsaInstalls.get(instanceId);
    return terracottaInstall.getTerracottaServerInstance(terracottaServer).jcmd(tcEnv, arguments);
  }

  public ToolExecutionResult clientJcmd(InstanceId instanceId, int clientPid, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    RemoteClientManager remoteClientManager = new RemoteClientManager(instanceId);
    return remoteClientManager.jcmd(clientPid, tcEnv, arguments);
  }

  public void startHardwareMonitoring(String workingPath, Map<HardwareMetric, MonitoringCommand> commands) {
    if (monitoringInstance == null) {
      monitoringInstance = new MonitoringInstance(new File(workingPath));
      monitoringInstance.startHardwareMonitoring(commands);
    }
  }

  public boolean isMonitoringRunning(HardwareMetric hardwareMetric) {
    return monitoringInstance.isMonitoringRunning(hardwareMetric);
  }

  public void stopHardwareMonitoring() {
    if (monitoringInstance != null) {
      monitoringInstance.stopHardwareMonitoring();
      monitoringInstance = null;
    }
  }

  public void stopClient(InstanceId instanceId, int pid) {
    try {
      logger.info("killing client '{}' with PID {}", instanceId, pid);
      ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid);
    } catch (Exception e) {
      throw new RuntimeException("Error stopping client " + instanceId, e);
    }
  }

  public void deleteClient(InstanceId instanceId) {
    File subAgentRoot = new RemoteClientManager(instanceId).getClientInstallationPath();
    logger.info("Cleaning up directory structure '{}' of client {}", subAgentRoot, instanceId);
    FileUtils.deleteTree(subAgentRoot.toPath());
  }

  public String instanceWorkDir(InstanceId instanceId) {
    return Agent.WORK_DIR.resolve(instanceId.toString()).toAbsolutePath().toString();
  }

  public int spawnClient(InstanceId instanceId, TerracottaCommandLineEnvironment tcEnv) {
    RemoteClientManager remoteClientManager = new RemoteClientManager(instanceId);
    return remoteClientManager.spawnClient(instanceId, tcEnv, joinedNodes, ignitePort, portAllocator);
  }

  public void downloadFiles(InstanceId instanceId, File installDir) {
    final BlockingQueue<Object> queue = IgniteCommonHelper.fileTransferQueue(ignite, instanceId);
    try {
      logger.info("Downloading files into {}", installDir);
      if (!installDir.exists()) {
        if (!installDir.mkdirs()) {
          throw new RuntimeException("Cannot create directory '" + installDir + "'");
        }
      }

      while (true) {
        Object read = queue.take();
        if (read.equals(Boolean.TRUE)) {
          logger.info("Downloaded files into {}", installDir);
          break;
        }

        FileMetadata fileMetadata = (FileMetadata) read;
        logger.debug("downloading " + fileMetadata);
        if (!fileMetadata.isDirectory()) {
          long readFileLength = 0L;
          File file = new File(installDir + File.separator + fileMetadata.getPathName());
          file.getParentFile().mkdirs();
          try (FileOutputStream fos = new FileOutputStream(file)) {
            while (readFileLength != fileMetadata.getLength()) {
              if (readFileLength > fileMetadata.getLength()) {
                throw new RuntimeException("Error downloading file : " + fileMetadata);
              }

              byte[] buffer = (byte[]) queue.take();
              fos.write(buffer);
              readFileLength += buffer.length;
            }
          }
          logger.debug("downloaded " + fileMetadata);
        }
      }
      setCorrectPermissions(installDir.toPath());
    } catch (Exception e) {
      throw new RuntimeException("Cannot download files to " + installDir.getAbsolutePath(), e);
    }
  }

  public List<String> listFiles(String folder) {
    File[] files = new File(folder).listFiles(pathname -> !pathname.isDirectory());
    if (files == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(files).map(File::getName).collect(toList());
  }

  public List<String> listFolders(String folder) {
    File[] files = new File(folder).listFiles(File::isDirectory);
    if (files == null) {
      return Collections.emptyList();
    }
    return Arrays.stream(files).map(File::getName).collect(toList());
  }

  public byte[] downloadFile(String file) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (FileInputStream fis = new FileInputStream(file)) {
      IOUtils.copy(fis, baos);
    } catch (IOException ioe) {
      throw new RuntimeException("Error downloading file " + file, ioe);
    }
    return baos.toByteArray();
  }

  public void uploadFile(String filename, byte[] data) {
    File file = new File(filename);
    file.getParentFile().mkdirs();
    try (FileOutputStream fos = new FileOutputStream(file)) {
      fos.write(data);
    } catch (IOException ioe) {
      throw new RuntimeException("Error uploading file " + filename, ioe);
    }
  }

  public byte[] downloadFolder(String file) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      File root = new File(file);
      zipFolder(zos, "", root);
    } catch (IOException ioe) {
      throw new RuntimeException("Error downloading folder " + file, ioe);
    }
    return baos.toByteArray();
  }

  private void zipFolder(ZipOutputStream zos, String parent, File folder) throws IOException {
    if (!folder.canRead()) {
      throw new IOException("Folder does not exist or is not readable : " + folder);
    }
    File[] files = folder.listFiles();
    if (files == null) {
      throw new IOException("Error listing folder " + folder);
    }
    for (File file : files) {
      if (file.isDirectory()) {
        zipFolder(zos, parent + file.getName() + "/", file);
      } else {
        ZipEntry zipEntry = new ZipEntry(parent + file.getName());
        zipEntry.setTime(file.lastModified());
        zos.putNextEntry(zipEntry);
        try (FileInputStream fis = new FileInputStream(file)) {
          IOUtils.copy(fis, zos);
        }
        zos.closeEntry();
      }
    }
  }

  public Map<String, ?> getNodeAttributes() {
    return ignite.configuration().getUserAttributes();
  }

  private static class Dirs {
    final File kitDir;
    final File workingDir;
    final RemoteKitManager kitManager;

    public Dirs(File kitDir, File workingDir, RemoteKitManager kitManager) {
      this.kitDir = kitDir;
      this.workingDir = workingDir;
      this.kitManager = kitManager;
    }

    static Optional<Dirs> discover(InstanceId instanceId, String hostName, Distribution distribution, License license, String kitInstallationName, String kitInstallationPath) {
      if (kitInstallationPath == null) {
        RemoteKitManager kitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
        if (!kitManager.isKitAvailable()) {
          return Optional.empty();
        }

        logger.info("Installing kit on host {} from {}", hostName, distribution);
        return Optional.of(new Dirs(
            kitManager.installKit(license, Collections.singletonList(hostName)),
            kitManager.getWorkingDir().toFile(),
            kitManager
        ));

      } else {
        // DO NOT ALTER THE KIT CONTENT IF kitInstallationPath IS USED
        File kitDir = new File(kitInstallationPath);
        Path workingPath = Agent.WORK_DIR.resolve(instanceId.toString());
        try {
          Files.createDirectories(workingPath);
          if (license != null) {
            license.writeToFile(workingPath.toFile());
          }
        } catch (IOException e) {
          logger.debug("Can not create {}", workingPath, e);
        }
        return Optional.of(new Dirs(
            kitDir,
            workingPath.toFile(),
            null
        ));
      }
    }
  }
}
