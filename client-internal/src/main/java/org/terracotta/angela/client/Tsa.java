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
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.config.TsaConfigurationContext;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.client.net.DisruptionController;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.topology.Topology;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static java.util.EnumSet.of;
import static org.terracotta.angela.client.config.TsaConfigurationContext.TerracottaCommandLineEnvironmentKeys.SERVER_START_PREFIX;
import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_DIR;
import static org.terracotta.angela.common.AngelaProperties.KIT_INSTALLATION_PATH;
import static org.terracotta.angela.common.AngelaProperties.OFFLINE;
import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;
import static org.terracotta.angela.common.AngelaProperties.getEitherOf;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_PASSIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_IN_DIAGNOSTIC_MODE;
import static org.terracotta.angela.common.TerracottaServerState.START_SUSPENDED;
import static org.terracotta.angela.common.TerracottaServerState.STOPPED;

/**
 * @author Aurelien Broszniowski
 */
public class Tsa implements AutoCloseable {

  private final static Logger logger = LoggerFactory.getLogger(Tsa.class);

  private final Ignite ignite;
  private final InstanceId instanceId;
  private final transient DisruptionController disruptionController;
  private final int ignitePort;
  private final TsaConfigurationContext tsaConfigurationContext;
  private final LocalKitManager localKitManager;
  private final PortAllocator portAllocator;
  private boolean closed = false;

  Tsa(Ignite ignite, int ignitePort, PortAllocator portAllocator, InstanceId instanceId, TsaConfigurationContext tsaConfigurationContext) {
    this.portAllocator = portAllocator;
    this.ignitePort = ignitePort;
    this.tsaConfigurationContext = tsaConfigurationContext;
    this.instanceId = instanceId;
    this.ignite = ignite;
    this.disruptionController = new DisruptionController(ignite, instanceId, ignitePort, tsaConfigurationContext.getTopology());
    this.localKitManager = new LocalKitManager(tsaConfigurationContext.getTopology().getDistribution());
    installAll();
  }

  public TsaConfigurationContext getTsaConfigurationContext() {
    return tsaConfigurationContext;
  }

  public DisruptionController getDisruptionController() {
    return disruptionController;
  }

  public PortAllocator getPortAllocator() {
    return portAllocator;
  }

  public Ignite getIgnite() {
    return ignite;
  }

  public InstanceId getInstanceId() {
    return instanceId;
  }

  public int getIgnitePort() {
    return ignitePort;
  }

  public String licensePath(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == null) {
      throw new IllegalStateException("Cannot get license path: server " + terracottaServer.getServerSymbolicName() + " has not been installed");
    }
    return IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, () -> Agent.controller
        .getTsaLicensePath(instanceId, terracottaServer));
  }

  void installAll() {
    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    for (TerracottaServer terracottaServer : configurationManager.getServers()) {
      install(terracottaServer, topology);
    }
  }

  void install(TerracottaServer terracottaServer, Topology topology) {
    installWithKitManager(terracottaServer, topology, this.localKitManager);
  }

  private void installWithKitManager(TerracottaServer terracottaServer, Topology topology, LocalKitManager localKitManager) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState != TerracottaServerState.NOT_INSTALLED) {
      throw new IllegalStateException("Cannot install: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
    }
    Distribution distribution = localKitManager.getDistribution();

    License license = tsaConfigurationContext.getLicense();

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);
    localKitManager.setupLocalInstall(license, kitInstallationPath, OFFLINE.getBooleanValue());

    boolean isRemoteInstallationSuccessful;
    if (kitInstallationPath == null || KIT_COPY.getBooleanValue()) {
      // "kitInstallationPath" is either not provided (=> kit download)
      // or it is provided but we specifically ask for a kit copy
      logger.info("Attempting to remotely install if distribution already exists on {}", terracottaServer.getHostname());
      isRemoteInstallationSuccessful = IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
          () -> Agent.controller.installTsa(instanceId, terracottaServer,
              license, localKitManager.getKitInstallationName(), distribution, topology, null));

      if (!isRemoteInstallationSuccessful) {
        try {
          logger.info("Uploading {} on {}", distribution, terracottaServer.getHostname());
          IgniteClientHelper.uploadKit(ignite, terracottaServer.getHostname(),
              ignitePort, instanceId, distribution, localKitManager.getKitInstallationName(), localKitManager.getKitInstallationPath().toFile());
          IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
              () -> Agent.controller.installTsa(instanceId, terracottaServer,
                  license, localKitManager.getKitInstallationName(), distribution, topology, null));
        } catch (Exception e) {
          throw new RuntimeException("Cannot upload kit to " + terracottaServer.getHostname(), e);
        }
      }
    } else {
      // We are trying to reuse the "kitInstallationPath" if provided (kitInstallationPath != null)
      // To end up here, KIT_COPY should be false
      IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
          () -> Agent.controller.installTsa(instanceId, terracottaServer,
              license, localKitManager.getKitInstallationName(), distribution, topology, kitInstallationPath));
    }
  }

  public Tsa upgrade(TerracottaServer server, Distribution newDistribution) {
    logger.info("Upgrading server {} to {}", server, newDistribution);
    uninstall(server);
    LocalKitManager localKitManager = new LocalKitManager(newDistribution);
    installWithKitManager(server, tsaConfigurationContext.getTopology(), localKitManager);
    return this;
  }

  private void uninstallAll() {
    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer terracottaServer : topology.getServers()) {
      uninstall(terracottaServer);
    }
  }

  private void uninstall(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == null) {
      return;
    }
    if (terracottaServerState != TerracottaServerState.STOPPED) {
      throw new IllegalStateException("Cannot uninstall: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
    }

    String kitInstallationPath = getEitherOf(KIT_INSTALLATION_DIR, KIT_INSTALLATION_PATH);

    logger.info("Uninstalling TC server from {}", terracottaServer.getHostname());
    IgniteRunnable uninstaller = () -> Agent.controller.uninstallTsa(instanceId, tsaConfigurationContext.getTopology(),
        terracottaServer, localKitManager.getKitInstallationName(), kitInstallationPath);
    IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, uninstaller);
  }

  public Tsa createAll(String... startUpArgs) {
    tsaConfigurationContext.getTopology().getServers().stream()
        .map(server -> CompletableFuture.runAsync(() -> create(server, startUpArgs)))
        .reduce(CompletableFuture::allOf).ifPresent(CompletableFuture::join);
    return this;
  }

  public Jcmd jcmd(TerracottaServer terracottaServer) {
    String whatFor = TsaConfigurationContext.TerracottaCommandLineEnvironmentKeys.JCMD + terracottaServer.getServerSymbolicName()
        .getSymbolicName();
    TerracottaCommandLineEnvironment tcEnv = tsaConfigurationContext.getTerracottaCommandLineEnvironment(whatFor);
    return new Jcmd(ignite, instanceId, terracottaServer, ignitePort, tcEnv);
  }

  public Tsa create(TerracottaServer terracottaServer, String... startUpArgs) {
    return create(terracottaServer, Collections.emptyMap(), startUpArgs);
  }

  public Tsa create(TerracottaServer terracottaServer, Map<String, String> envOverrides, String... startUpArgs) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    switch (terracottaServerState) {
      case STARTING:
      case STARTED_AS_ACTIVE:
      case STARTED_AS_PASSIVE:
      case STARTED_IN_DIAGNOSTIC_MODE:
        return this;
      case STOPPED:
        logger.info("Creating TC server on {}", terracottaServer.getHostname());
        IgniteRunnable tsaCreator = () -> {
          String whatFor = SERVER_START_PREFIX + terracottaServer.getServerSymbolicName().getSymbolicName();
          TerracottaCommandLineEnvironment cliEnv = tsaConfigurationContext.getTerracottaCommandLineEnvironment(whatFor);
          Agent.controller.createTsa(instanceId, terracottaServer, cliEnv, envOverrides, Arrays.asList(startUpArgs));
        };
        IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, tsaCreator);
        return this;
    }
    throw new IllegalStateException("Cannot create: server " + terracottaServer.getServerSymbolicName() + " in state " + terracottaServerState);
  }

  public Tsa startAll(String... startUpArgs) {
    tsaConfigurationContext.getTopology().getServers().stream()
        .map(server -> CompletableFuture.runAsync(() -> start(server, startUpArgs)))
        .reduce(CompletableFuture::allOf).ifPresent(CompletableFuture::join);
    return this;
  }

  public DisruptionController disruptionController() {
    return disruptionController;
  }

  public Tsa start(TerracottaServer terracottaServer, String... startUpArgs) {
    return start(terracottaServer, Collections.emptyMap(), startUpArgs);
  }

  public Tsa start(TerracottaServer terracottaServer, Map<String, String> envOverrides, String... startUpArgs) {
    create(terracottaServer, envOverrides, startUpArgs);
    IgniteRunnable runnable = () -> Agent.controller.waitForTsaInState(instanceId, terracottaServer,
        of(STARTED_AS_ACTIVE, STARTED_AS_PASSIVE, STARTED_IN_DIAGNOSTIC_MODE, START_SUSPENDED));
    IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, runnable);
    return this;
  }

  public Tsa stopAll() {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer terracottaServer : topology.getServers()) {
      try {
        stop(terracottaServer);
      } catch (Exception e) {
        exceptions.add(e);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error stopping all servers");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
    return this;
  }

  public Tsa stop(TerracottaServer terracottaServer) {
    TerracottaServerState terracottaServerState = getState(terracottaServer);
    if (terracottaServerState == STOPPED) {
      return this;
    }
    logger.info("Stopping TC server on {}", terracottaServer.getHostname());
    IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, () -> Agent.controller.stopTsa(instanceId, terracottaServer));
    return this;
  }

  public Map<ServerSymbolicName, Integer> updateToProxiedPorts() {
    return disruptionController.updateTsaPortsWithProxy(tsaConfigurationContext.getTopology(), portAllocator);
  }

  public TerracottaServerState getState(TerracottaServer terracottaServer) {
    return IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
        () -> Agent.controller.getTsaState(instanceId, terracottaServer));
  }

  public Map<ServerSymbolicName, Integer> getProxyGroupPortsForServer(TerracottaServer terracottaServer) {
    return IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort, () -> Agent.controller
        .getProxyGroupPortsForServer(instanceId, terracottaServer));
  }

  public Collection<TerracottaServer> getStarted() {
    Collection<TerracottaServer> allRunningServers = new ArrayList<>();
    allRunningServers.addAll(getActives());
    allRunningServers.addAll(getPassives());
    allRunningServers.addAll(getDiagnosticModeSevers());
    return allRunningServers;
  }

  public Collection<TerracottaServer> getStopped() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STOPPED) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public Collection<TerracottaServer> getPassives() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_AS_PASSIVE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public TerracottaServer getPassive() {
    Collection<TerracottaServer> servers = getPassives();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one Passive Terracotta server, found " + servers.size());
    }
  }

  public Collection<TerracottaServer> getActives() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_AS_ACTIVE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public Collection<TerracottaServer> getServer(ServerSymbolicName symbolicName) {
    return tsaConfigurationContext.getTopology().getServers().stream()
        .filter(server -> server.getServerSymbolicName().equals(symbolicName))
        .collect(Collectors.toList());
  }

  public TerracottaServer getServer(int stripeIndex, int serverIndex) {
    return tsaConfigurationContext.getTopology().getServer(stripeIndex, serverIndex);
  }

  public Collection<Integer> getStripeIdOf(ServerSymbolicName symbolicName) {
    Collection<Integer> stripeIndices = new ArrayList<>();
    List<List<TerracottaServer>> stripes = tsaConfigurationContext.getTopology().getStripes();
    for (int i = 0; i < stripes.size(); i++) {
      List<TerracottaServer> stripe = stripes.get(i);
      if (stripe.stream().anyMatch(server -> server.getServerSymbolicName().equals(symbolicName))) {
        stripeIndices.add(i);
      }
    }
    return stripeIndices;
  }

  public TerracottaServer getActive() {
    Collection<TerracottaServer> servers = getActives();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one Active Terracotta server, found " + servers.size());
    }
  }

  public Collection<TerracottaServer> getDiagnosticModeSevers() {
    Collection<TerracottaServer> result = new ArrayList<>();
    for (TerracottaServer terracottaServer : tsaConfigurationContext.getTopology().getServers()) {
      if (getState(terracottaServer) == STARTED_IN_DIAGNOSTIC_MODE) {
        result.add(terracottaServer);
      }
    }
    return result;
  }

  public TerracottaServer getDiagnosticModeServer() {
    Collection<TerracottaServer> servers = getDiagnosticModeSevers();
    switch (servers.size()) {
      case 0:
        return null;
      case 1:
        return servers.iterator().next();
      default:
        throw new IllegalStateException("There is more than one diagnostic mode server, found " + servers.size());
    }
  }

  public URI uri() {
    if (disruptionController == null) {
      throw new IllegalStateException("uri cannot be built from a client lambda - please call uri() from the test code instead");
    }
    Topology topology = tsaConfigurationContext.getTopology();
    Map<ServerSymbolicName, Integer> proxyTsaPorts = topology.isNetDisruptionEnabled() ?
        disruptionController.getProxyTsaPorts() : Collections.emptyMap();
    return topology.getDistribution().createDistributionController().tsaUri(topology.getServers(), proxyTsaPorts);
  }

  public RemoteFolder browse(TerracottaServer terracottaServer, String root) {
    String path = IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
        () -> Agent.controller.getTsaInstallPath(instanceId, terracottaServer));
    return new RemoteFolder(ignite, terracottaServer.getHostname(), ignitePort, path, root);
  }

  public RemoteFolder browseFromKitLocation(TerracottaServer terracottaServer, String relativePath) {
    String kitLocation = IgniteClientHelper.executeRemotely(ignite, terracottaServer.getHostname(), ignitePort,
                                                     () -> Agent.controller.getTsaKitLocation(instanceId, terracottaServer));
    return new RemoteFolder(ignite, terracottaServer.getHostname(), ignitePort, kitLocation, relativePath);
  }

  public void uploadPlugin(File localPluginFile) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    for (TerracottaServer server : topology.getServers()) {
      try {
        browseFromKitLocation(
            server,
            topology.getDistribution().createDistributionController().pluginJarsRootFolderName(topology.getDistribution())
        ).upload(localPluginFile);
      } catch (IOException ioe) {
        exceptions.add(ioe);
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error uploading TSA plugin");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  public void uploadDataDirectories(File localRootPath) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    if (configurationManager instanceof TcConfigManager) {
      TcConfigManager tcConfigProvider = (TcConfigManager)configurationManager;
      List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
      for (TcConfig tcConfig : tcConfigs) {
        Collection<String> dataDirectories = tcConfig.getDataDirectories().values();
        List<TerracottaServer> servers = tcConfig.getServers();
        for (String directory : dataDirectories) {
          for (TerracottaServer server : servers) {
            try {
              File localFile = new File(localRootPath, server.getServerSymbolicName()
                                                           .getSymbolicName() + "/" + directory);
              browse(server, directory).upload(localFile);
            } catch (IOException ioe) {
              exceptions.add(ioe);
            }
          }
        }
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error uploading TSA data directories");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  public void downloadDataDirectories(File localRootPath) {
    List<Exception> exceptions = new ArrayList<>();

    Topology topology = tsaConfigurationContext.getTopology();
    ConfigurationManager configurationManager = topology.getConfigurationManager();
    if (configurationManager instanceof TcConfigManager) {
      TcConfigManager tcConfigProvider = (TcConfigManager)configurationManager;
      List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
      for (TcConfig tcConfig : tcConfigs) {
        Map<String, String> dataDirectories = tcConfig.getDataDirectories();
        List<TerracottaServer> servers = tcConfig.getServers();
        for (TerracottaServer server : servers) {
          for (Map.Entry<String, String> entry : dataDirectories.entrySet()) {
            String directory = entry.getValue();
            try {
              browse(server, directory).downloadTo(new File(localRootPath + "/" + server.getServerSymbolicName()
                  .getSymbolicName(), directory));
            } catch (IOException ioe) {
              exceptions.add(ioe);
            }
          }
        }
      }
    }

    if (!exceptions.isEmpty()) {
      RuntimeException re = new RuntimeException("Error downloading TSA data directories");
      exceptions.forEach(re::addSuppressed);
      throw re;
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stopAll();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      uninstallAll();
    }

    if (tsaConfigurationContext.getTopology().isNetDisruptionEnabled()) {
      try {
        disruptionController.close();
      } catch (Exception e) {
        logger.error("Error when trying to close traffic controller : {}", e.getMessage());
      }
    }
  }

}
