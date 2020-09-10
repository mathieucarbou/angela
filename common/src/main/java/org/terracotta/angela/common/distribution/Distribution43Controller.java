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

import org.apache.commons.io.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.ClusterToolExecutionResult;
import org.terracotta.angela.common.ConfigToolExecutionResult;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess;
import org.terracotta.angela.common.TerracottaServerInstance.TerracottaServerInstanceProcess;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.provider.ConfigurationManager;
import org.terracotta.angela.common.provider.TcConfigManager;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TcConfig;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.topology.Version;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.HostPort;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.TriggeringOutputStream;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.io.File.separator;
import static java.util.regex.Pattern.compile;
import static org.terracotta.angela.common.AngelaProperties.TSA_FULL_LOGGING;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_ACTIVE;
import static org.terracotta.angela.common.TerracottaServerState.STARTED_AS_PASSIVE;
import static org.terracotta.angela.common.TerracottaServerState.STOPPED;
import static org.terracotta.angela.common.topology.PackageType.KIT;
import static org.terracotta.angela.common.topology.PackageType.SAG_INSTALLER;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidHost;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv4;
import static org.terracotta.angela.common.util.HostAndIpValidator.isValidIPv6;

/**
 * @author Aurelien Broszniowski
 */
public class Distribution43Controller extends DistributionController {
  private final static Logger logger = LoggerFactory.getLogger(Distribution43Controller.class);

  private final boolean tsaFullLogging = TSA_FULL_LOGGING.getBooleanValue();

  Distribution43Controller(Distribution distribution) {
    super(distribution);
    Version version = distribution.getVersion();
    if (version.getMajor() != 4) {
      throw new IllegalStateException(getClass().getSimpleName() + " cannot work with distribution version " + version);
    }
  }

  @Override
  public TerracottaServerInstanceProcess createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir,
                                                   Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts,
                                                   TerracottaCommandLineEnvironment tcEnv, List<String> startUpArgs) {
    AtomicReference<TerracottaServerState> stateRef = new AtomicReference<>(STOPPED);
    AtomicReference<TerracottaServerState> tempStateRef = new AtomicReference<>(STOPPED);

    TriggeringOutputStream serverLogOutputStream = TriggeringOutputStream
        .triggerOn(
            compile("^.*\\QTerracotta Server instance has started up as ACTIVE\\E.*$"),
            mr -> {
              if (stateRef.get() == STOPPED) {
                tempStateRef.set(STARTED_AS_ACTIVE);
              } else {
                stateRef.set(STARTED_AS_ACTIVE);
              }
            })
        .andTriggerOn(
            compile("^.*\\QMoved to State[ PASSIVE-STANDBY ]\\E.*$"),
            mr -> tempStateRef.set(STARTED_AS_PASSIVE))
        .andTriggerOn(
            compile("^.*\\QManagement server started\\E.*$"),
            mr -> stateRef.set(tempStateRef.get()))
        .andTriggerOn(
            compile("^.*\\QServer exiting\\E.*$"),
            mr -> stateRef.set(STOPPED));
    serverLogOutputStream = tsaFullLogging ?
        serverLogOutputStream.andForward(line -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), line)) :
        serverLogOutputStream.andTriggerOn(compile("^.*(WARN|ERROR).*$"), mr -> ExternalLoggers.tsaLogger.info("[{}] {}", terracottaServer.getServerSymbolicName().getSymbolicName(), mr.group()));

    // add an identifiable ID to the JVM's system properties
    Map<String, String> env = buildEnv(tcEnv);
    env.compute("JAVA_OPTS", (key, value) -> {
      String prop = " -Dangela.processIdentifier=" + terracottaServer.getId();
      return value == null ? prop : value + prop;
    });

    WatchedProcess<TerracottaServerState> watchedProcess = new WatchedProcess<>(
        new ProcessExecutor()
            .command(createTsaCommand(terracottaServer.getServerSymbolicName(), terracottaServer.getId(), topology.getConfigurationManager(), proxiedPorts, kitDir, workingDir, startUpArgs))
            .directory(workingDir)
            .environment(env)
            .redirectErrorStream(true)
            .redirectOutput(serverLogOutputStream),
        stateRef,
        STOPPED);

    int wrapperPid = watchedProcess.getPid();
    Number javaPid = findWithJcmdJavaPidOf(terracottaServer.getId().toString(), tcEnv);
    return new TerracottaServerInstanceProcess(stateRef, wrapperPid, javaPid);
  }

  private Number findWithJcmdJavaPidOf(String serverUuid, TerracottaCommandLineEnvironment tcEnv) {
    String javaHome = tcEnv.getJavaHome().orElseGet(()->javaLocationResolver.resolveJavaLocation(tcEnv).getHome());

    List<String> cmdLine = new ArrayList<>();
    if (OS.INSTANCE.isWindows()) {
      cmdLine.add(javaHome + "\\bin\\jcmd.exe");
    } else {
      cmdLine.add(javaHome + "/bin/jcmd");
    }
    cmdLine.add("com.tc.server.TCServerMain");
    cmdLine.add("VM.system_properties");

    final int retries = 100;
    for (int i = 0; i < retries; i++) {
      ProcessResult processResult;
      try {
        processResult = new ProcessExecutor(cmdLine)
            .redirectErrorStream(true)
            .readOutput(true)
            .execute();
      } catch (Exception e) {
        logger.warn("Unable to get server pid with jcmd", e);
        return null;
      }

      if (processResult.getExitValue() == 0) {
        List<String> lines = processResult.getOutput().getLines();
        Number pid = parseOutputAndFindUuid(lines, serverUuid);
        if (pid != null) return pid;
      }

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      // warn on the last loop
      if (i == (retries - 1)) {
        logger.warn("Unable to get server pid with jcmd (rc={})", processResult.getExitValue());
        logger.warn("{}", processResult.getOutput().getString());
      }
    }

    return null;
  }

  private Number parseOutputAndFindUuid(List<String> lines, String serverUuid) {
    int pid = 0;
    for (String line : lines) {
      if (line.endsWith(":")) {
        try {
          pid = Integer.parseInt(line.substring(0, line.length() - 1));
        } catch (NumberFormatException e) {
          // false positive, skip
          continue;
        }
      }

      if (line.equals("angela.processIdentifier=" + serverUuid)) {
        return pid;
      }
    }
    logger.warn("Unable to parse jcmd output: {} to find serverUuid {}", lines, serverUuid);
    return null;
  }

  @Override
  public void configure(String clusterName, File kitDir, File workingDir, String licensePath, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment tcEnv, boolean verbose) {
    logger.info("There is no licensing step in 4.x");
  }

  @Override
  public ClusterToolExecutionResult invokeClusterTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv, Path securityDir, String... arguments) {
    throw new UnsupportedOperationException("4.x does not have a cluster tool");
  }

  @Override
  public ConfigToolExecutionResult invokeConfigTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment env, Path securityDir, String... arguments) {
    throw new UnsupportedOperationException("4.x does not support config tool");
  }

  /**
   * Construct the Start Command with the Version, Tc Config file and server name
   *
   * @return List of Strings representing the start command and its parameters
   */
  private List<String> createTsaCommand(ServerSymbolicName serverSymbolicName, UUID serverId, ConfigurationManager configurationManager,
                                        Map<ServerSymbolicName, Integer> proxiedPorts, File kitLocation, File installLocation,
                                        List<String> startUpArgs) {
    List<String> options = new ArrayList<>();
    // start command
    options.add(getStartCmd(kitLocation));

    String symbolicName = serverSymbolicName.getSymbolicName();
    if (isValidHost(symbolicName) || isValidIPv4(symbolicName) || isValidIPv6(symbolicName) || symbolicName.isEmpty()) {
      // add -n if applicable
      options.add("-n");
      options.add(symbolicName);
    }

    TcConfigManager tcConfigProvider = (TcConfigManager) configurationManager;
    TcConfig tcConfig = TcConfig.copy(tcConfigProvider.findTcConfig(serverId));
    tcConfigProvider.setUpInstallation(tcConfig, serverSymbolicName, serverId, proxiedPorts, installLocation, null);
    // add -f if applicable
    if (tcConfig.getPath() != null) {
      //workaround to have unique platform restart directory for active & passives
      //TODO this can  be removed when platform persistent has server name in the path
      try {
        String modifiedTcConfigPath = tcConfig.getPath()
            .substring(0, tcConfig.getPath()
                .length() - 4) + "-" + serverSymbolicName.getSymbolicName() + ".xml";
        String modifiedConfig = FileUtils.readFileToString(new File(tcConfig.getPath())).
            replaceAll(Pattern.quote("${restart-data}"), "restart-data/" + serverSymbolicName).
            replaceAll(Pattern.quote("${SERVER_NAME_TEMPLATE}"), serverSymbolicName.getSymbolicName());
        FileUtils.write(new File(modifiedTcConfigPath), modifiedConfig);
        options.add("-f");
        options.add(modifiedTcConfigPath);
      } catch (IOException ioe) {
        throw new RuntimeException("Error when modifying tc config", ioe);
      }
    }

    options.addAll(startUpArgs);
    StringBuilder sb = new StringBuilder();
    for (String option : options) {
      sb.append(option).append(" ");
    }
    logger.debug("TSA create command = {}", sb.toString());

    return options;
  }

  private String getStartCmd(File kitLocation) {
    String execPath = "server" + separator + "bin" + separator + "start-tc-server" + OS.INSTANCE.getShellExtension();
    if (distribution.getPackageType() == KIT) {
      return kitLocation.getAbsolutePath() + separator + execPath;
    } else if (distribution.getPackageType() == SAG_INSTALLER) {
      return kitLocation.getAbsolutePath() + separator + terracottaInstallationRoot() + separator + execPath;
    }
    throw new IllegalStateException("Can not define Terracotta server Start Command for distribution: " + distribution);
  }


  @Override
  public TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment tcEnv) {
    throw new UnsupportedOperationException("NOT YET IMPLEMENTED");
  }

  @Override
  public void stopTms(File installLocation, TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv) {
    throw new UnsupportedOperationException("NOT YET IMPLEMENTED");
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
  
  @Override
  public URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts) {
    return URI.create(servers
        .stream()
        .map(s -> new HostPort(s.getHostname(), proxyTsaPorts.getOrDefault(s.getServerSymbolicName(), s.getTsaPort())).getHostPort())
        .collect(Collectors.joining(",", "", "")));
  }

  @Override
  public String clientJarsRootFolderName(Distribution distribution) {
    if (distribution.getPackageType() == KIT) {
      return "apis";
    } else if (distribution.getPackageType() == SAG_INSTALLER) {
      return "common" + separator + "lib";
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public String pluginJarsRootFolderName(Distribution distribution) {
    throw new UnsupportedOperationException("4.x does not support plugins");
  }

  @Override
  public String terracottaInstallationRoot() {
    return "Terracotta";
  }
}
