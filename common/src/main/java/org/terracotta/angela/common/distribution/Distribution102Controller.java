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

package org.terracotta.angela.common.distribution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.AngelaProperties;
import org.terracotta.angela.common.ClusterToolException;
import org.terracotta.angela.common.ClusterToolExecutionResult;
import org.terracotta.angela.common.ConfigToolExecutionResult;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess;
import org.terracotta.angela.common.TerracottaManagementServerState;
import org.terracotta.angela.common.TerracottaServerInstance.TerracottaServerInstanceProcess;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.SecureTcConfig;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.PackageType;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.topology.Version;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.FileUtils;
import org.terracotta.angela.common.util.HostPort;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.ProcessUtil;
import org.terracotta.angela.common.util.TriggeringOutputStream;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.slf4j.Slf4jStream;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.lang.Integer.parseInt;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidHost;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv4;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv6;

/**
 * @author Aurelien Broszniowski
 */
public class Distribution102Controller extends DistributionController {
  private final static Logger logger = LoggerFactory.getLogger(Distribution102Controller.class);
  private final boolean tsaFullLogging = AngelaProperties.TSA_FULL_LOGGING.getBooleanValue();
  private final boolean tmsFullLogging = AngelaProperties.TMS_FULL_LOGGING.getBooleanValue();

  Distribution102Controller(Distribution distribution) {
    super(distribution);
    Version version = distribution.getVersion();
    if (version.getMajor() != 3 && version.getMajor() != 10) {
      throw new IllegalStateException(getClass().getSimpleName() + " cannot work with distribution version " + version);
    }
  }

  @Override
  public TerracottaServerInstanceProcess createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                                   Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                                   TerracottaCommandLineEnvironment tcEnv, List<String> startUpArgs) {
    Map<String, String> env = buildEnv(tcEnv);

    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(TerracottaServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_ACTIVE))
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QL2 Exiting\\E.*$"),
            mr -> stateRef.set(TerracottaServerState.STOPPED))
        .andTriggerOn(
            compile("^.*PID is (\\d+).*$"), mr -> {
              javaPid.set(parseInt(mr.group(1)));
              stateRef.compareAndSet(TerracottaServerState.STOPPED, TerracottaServerState.STARTING);
            });
    serverLogOutputStream = tsaFullLogging ?
        serverLogOutputStream.andForward(line -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), line)) :
        serverLogOutputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));

    WatchedProcess<TerracottaServerState> watchedProcess = new WatchedProcess<>(new ProcessExecutor()
        .command(createTsaCommand(terracottaServer.getServerSymbolicName(), terracottaServer.getId(), topology, proxiedPorts, kitDir, workingDir, startUpArgs))
        .directory(workingDir)
        .environment(env)
        .redirectErrorStream(true)
        .redirectOutput(serverLogOutputStream), stateRef, TerracottaServerState.STOPPED);

    while (javaPid.get() == -1 && watchedProcess.isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }

    if (!watchedProcess.isAlive()) {
      throw new RuntimeException("Terracotta server process died in its infancy : " + terracottaServer.getServerSymbolicName());
    }
    return new TerracottaServerInstanceProcess(stateRef, watchedProcess.getPid(), javaPid);
  }

  @Override
  public ClusterToolExecutionResult invokeClusterTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv, Path securityDir, String... arguments) {
    List<String> command = new ArrayList<>();
    command.add(kitDir
        + separator + "tools"
        + separator + "cluster-tool"
        + separator + "bin"
        + separator + "cluster-tool" + OS.INSTANCE.getShellExtension());
    if (securityDir != null) {
      command.add("-srd");
      command.add(securityDir.toString());
    }
    command.addAll(Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(command)
          .directory(workingDir)
          .environment(buildEnv(tcEnv))
          .readOutput(true)
          .redirectErrorStream(true)
          .exitValue(0)
          .execute();
      return new ClusterToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public ConfigToolExecutionResult invokeConfigTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment env, Path securityDir, String... arguments) {
    throw new UnsupportedOperationException("Config Tool is supported only for a dynamically-configured cluster");
  }

  @Override
  public void configure(String clusterName, File kitDir, File workingDir, String licensePath, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment tcEnv, boolean verbose) {
    Map<String, String> env = buildEnv(tcEnv);

    File tmpConfigDir = new File(workingDir, "tmp-tc-configs");
    if (!tmpConfigDir.mkdir() && !tmpConfigDir.isDirectory()) {
      throw new RuntimeException("Error creating temporary cluster tool TC config folder : " + tmpConfigDir);
    }
    ConfigurationManager configurationProvider = topology.getConfigurationManager();
    TcConfigManager tcConfigProvider = (TcConfigManager) configurationProvider;
    List<TcConfig> tcConfigs = tcConfigProvider.getTcConfigs();
    List<TcConfig> modifiedConfigs = new ArrayList<>();
    for (TcConfig tcConfig : tcConfigs) {
      TcConfig modifiedConfig = TcConfig.copy(tcConfig);
      if (!proxyTsaPorts.isEmpty()) {
        modifiedConfig.updateServerTsaPort(proxyTsaPorts);
      }
      modifiedConfig.writeTcConfigFile(tmpConfigDir);
      modifiedConfigs.add(modifiedConfig);
    }

    List<String> commands = configureTsaLicenseCommand(kitDir, workingDir, licensePath, modifiedConfigs, clusterName, securityRootDirectory, verbose);

    logger.debug("Licensing commands: {}", commands);
    logger.debug("Licensing command line environment: {}", tcEnv);

    ProcessExecutor executor = new ProcessExecutor()
        .command(commands)
        .directory(workingDir)
        .environment(env)
        .redirectOutput(Slf4jStream.of(ExternalLoggers.clusterToolLogger).asInfo())
        .redirectErrorStream(true);

    ProcessResult processResult;
    try {
      StartedProcess startedProcess = executor.start();
      processResult = startedProcess.getFuture().get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      FileUtils.deleteTree(tmpConfigDir.toPath());
    }

    if (processResult.getExitValue() != 0) {
      throw new ClusterToolException("Error when installing the cluster license", commands.toString(), processResult.getExitValue());
    }
  }

  private List<String> configureTsaLicenseCommand(File kitDir, File workingDir, String licensePath, List<TcConfig> tcConfigs,
                                                  String clusterName, SecurityRootDirectory securityRootDirectory, boolean verbose) {
    List<String> command = new ArrayList<>();

    command.add(getConfigureTsaExecutable(kitDir));

    if (securityRootDirectory != null) {
      Path securityRootDirectoryPath = workingDir.toPath()
          .resolve("cluster-tool-security")
          .resolve("security-root-directory");
      securityRootDirectory.createSecurityRootDirectory(securityRootDirectoryPath);
      logger.debug("Using SecurityRootDirectory " + securityRootDirectoryPath);
      command.add("-srd");
      command.add(securityRootDirectoryPath.toString());
    }
    if (verbose) {
      command.add("-v");
    }
    command.add("configure");
    command.add("-n");
    command.add(clusterName);
    command.add("-l");
    command.add(licensePath);

    for (TcConfig tcConfig : tcConfigs) {
      command.add(tcConfig.getPath());
    }

    return command;
  }

  private String getConfigureTsaExecutable(File kitLocation) {
    String execPath = "tools" + separator + "cluster-tool" + separator + "bin" + separator + "cluster-tool" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define TSA licensing Command for distribution: " + distribution);
  }

  /**
   * Construct the Start Command with the Version, Tc Config file and server name
   *
   * @return List of String representing the start command and its parameters
   */
  private List<String> createTsaCommand(ServerSymbolicName serverSymbolicName, UUID serverId, Topology topology,
                                        Map<ServerSymbolicName, Integer> proxiedPorts, File kitLocation, File installLocation,
                                        List<String> startUpArgs) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getTsaCreateExecutable(kitLocation));

    String symbolicName = serverSymbolicName.getSymbolicName();
    if (isValidHost(symbolicName) || isValidIPv4(symbolicName) || isValidIPv6(symbolicName) || symbolicName.isEmpty()) {
      // add -n if applicable
      options.add("-n");
      options.add(symbolicName);
    }

    TcConfigManager configurationProvider = (TcConfigManager) topology.getConfigurationManager();
    TcConfig tcConfig = configurationProvider.findTcConfig(serverId);
    SecurityRootDirectory securityRootDirectory = null;
    if (tcConfig instanceof SecureTcConfig) {
      SecureTcConfig secureTcConfig = (SecureTcConfig) tcConfig;
      securityRootDirectory = secureTcConfig.securityRootDirectoryFor(serverSymbolicName);
    }
    TcConfig modifiedConfig = TcConfig.copy(configurationProvider.findTcConfig(serverId));
    configurationProvider.setUpInstallation(modifiedConfig, serverSymbolicName, serverId, proxiedPorts, installLocation, securityRootDirectory);

    // add -f if applicable
    if (modifiedConfig.getPath() != null) {
      options.add("-f");
      options.add(modifiedConfig.getPath());
    }

    options.addAll(startUpArgs);

    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug("Create TSA command = {}", sb.toString());

    return options;
  }

  private String getTsaCreateExecutable(File kitLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server Start Command for distribution: " + distribution);
  }


  @Override
  public TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv) {
    Map<String, String> env = buildEnv(tcEnv);

    AtomicReference<TerracottaManagementServerState> stateRef = new AtomicReference<>(TerracottaManagementServerState.STOPPED);
    AtomicInteger javaPid = new AtomicInteger(-1);

    TriggeringOutputStream outputStream = TriggeringOutputStream.triggerOn(
        compile("^.*\\Qstarted on port\\E.*$"), mr -> stateRef.set(TerracottaManagementServerState.STARTED)
    ).andTriggerOn(
        compile("^.*\\QStarting TmsApplication\\E.*with PID (\\d+).*$"), mr -> javaPid.set(parseInt(mr.group(1)))
    );
    outputStream = tmsFullLogging ?
        outputStream.andForward(ExternalLoggers.tmsLogger::info) :
        outputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tmsLogger.info(mr.group()));

    WatchedProcess<TerracottaManagementServerState> watchedProcess = new WatchedProcess<>(new ProcessExecutor()
        .command(startTmsCommand(kitDir))
        .directory(workingDir)
        .environment(env)
        .redirectErrorStream(true)
        .redirectOutput(outputStream), stateRef, TerracottaManagementServerState.STOPPED);

    while ((javaPid.get() == -1 || stateRef.get() == TerracottaManagementServerState.STOPPED) && watchedProcess.isAlive()) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
    if (!watchedProcess.isAlive()) {
      throw new RuntimeException("TMS process died before reaching STARTED state");
    }
    int wrapperPid = watchedProcess.getPid();
    int javaProcessPid = javaPid.get();
    return new TerracottaManagementServerInstanceProcess(stateRef, wrapperPid, javaProcessPid);
  }

  @Override
  public void stopTms(File installLocation, TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv) {
    logger.debug("Destroying TMS process");
    for (Number pid : terracottaServerInstanceProcess.getPids()) {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid.intValue());
      } catch (Exception e) {
        logger.error("Could not destroy TMS process {}", pid, e);
      }
    }
  }

  @Override
  public TerracottaVoterInstanceProcess startVoter(TerracottaVoter terracottaVoter, File kitDir, File workingDir,
                                                   SecurityRootDirectory securityDir, TerracottaCommandLineEnvironment tcEnv) {
    throw new UnsupportedOperationException("Running voter is supported not in this distribution version");
  }

  @Override
  public void stopVoter(TerracottaVoterInstanceProcess terracottaVoterInstanceProcess) {
    throw new UnsupportedOperationException("Running voter is supported not in this distribution version");
  }

  /**
   * Construct the Start Command with the Version
   *
   * @return String[] representing the start command and its parameters
   */
  private List<String> startTmsCommand(File kitDir) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getStartTmsExecutable(kitDir));

    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug(" Start TMS command = {}", sb.toString());

    return options;
  }

  private String getStartTmsExecutable(File installLocation) {
    String execPath = "tools" + separator + "management" + separator + "bin" + separator + "start" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == PackageType.KIT) {
      return installLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return installLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define TMS Start Command for distribution: " + distribution);
  }

  @Override
  public URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts) {
    return URI.create(servers
        .stream()
        .map(s -> new HostPort(s.getHostname(), proxyTsaPorts.getOrDefault(s.getServerSymbolicName(), s.getTsaPort())).getHostPort())
        .collect(Collectors.joining(",", "terracotta://", "")));
  }

  @Override
  public String clientJarsRootFolderName(Distribution distribution) {
    if (distribution.getPackageType() == PackageType.KIT) {
      return "client";
    } else if (distribution.getPackageType() == PackageType.SAG_INSTALLER) {
      return "common" + separator + "lib";
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public String pluginJarsRootFolderName(Distribution distribution) {
    return "server" + separator + "plugins" + separator + "lib";
  }

  @Override
  public String terracottaInstallationRoot() {
    return "TerracottaDB";
  }
}
