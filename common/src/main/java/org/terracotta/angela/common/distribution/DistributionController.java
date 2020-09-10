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

import org.terracotta.angela.common.ClusterToolExecutionResult;
import org.terracotta.angela.common.ConfigToolExecutionResult;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.TerracottaVoterInstance.TerracottaVoterInstanceProcess;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.tcconfig.SecurityRootDirectory;
import org.terracotta.angela.common.tcconfig.ServerSymbolicName;
import org.terracotta.angela.common.tcconfig.TerracottaServer;
import org.terracotta.angela.common.topology.Topology;
import org.terracotta.angela.common.util.JavaLocationResolver;
import org.terracotta.angela.common.util.OS;
import org.terracotta.angela.common.util.ProcessUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.common.TerracottaVoter;
import org.terracotta.angela.common.TerracottaVoterInstance;
import org.terracotta.angela.common.TerracottaManagementServerInstance;
import org.terracotta.angela.common.TerracottaServerInstance;
import org.terracotta.angela.common.TerracottaServerState;
import org.terracotta.angela.common.util.RetryUtils;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author Aurelien Broszniowski
 */
public abstract class DistributionController {

  private final static Logger LOGGER = LoggerFactory.getLogger(DistributionController.class);

  protected final Distribution distribution;

  protected final JavaLocationResolver javaLocationResolver = new JavaLocationResolver();


  DistributionController(Distribution distribution) {
    this.distribution = distribution;
  }

  protected Map<String, String> buildEnv(TerracottaCommandLineEnvironment tcEnv) {
    Map<String, String> env = new HashMap<>();
    String javaHome = tcEnv.getJavaHome().orElseGet(()->javaLocationResolver.resolveJavaLocation(tcEnv).getHome());
    env.put("JAVA_HOME", javaHome);
    LOGGER.info(" JAVA_HOME = {}", javaHome);

    Set<String> javaOpts = tcEnv.getJavaOpts();
    if (!javaOpts.isEmpty()) {
      String joinedJavaOpts = String.join(" ", javaOpts);
      env.put("JAVA_OPTS", joinedJavaOpts);
      LOGGER.info(" JAVA_OPTS = {}", joinedJavaOpts);
    }
    return env;
  }

  public ToolExecutionResult invokeJcmd(TerracottaServerInstance.TerracottaServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    Number javaPid = terracottaServerInstanceProcess.getJavaPid();
    if (javaPid == null) {
      return new ToolExecutionResult(-1, Collections.singletonList("PID of java process could not be figured out"));
    }

    String javaHome = tcEnv.getJavaHome().orElseGet(()->javaLocationResolver.resolveJavaLocation(tcEnv).getHome());

    List<String> cmdLine = new ArrayList<>();
    if (OS.INSTANCE.isWindows()) {
      cmdLine.add(javaHome + "\\bin\\jcmd.exe");
    } else {
      cmdLine.add(javaHome + "/bin/jcmd");
    }
    cmdLine.add(javaPid.toString());
    cmdLine.addAll(Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(cmdLine)
          .redirectErrorStream(true)
          .readOutput(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public abstract TerracottaServerInstance.TerracottaServerInstanceProcess createTsa(TerracottaServer terracottaServer, File kitDir, File workingDir, Topology topology, Map<ServerSymbolicName, Integer> proxiedPorts, TerracottaCommandLineEnvironment tcEnv, List<String> startUpArgs);

  public abstract TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess startTms(File kitDir, File workingDir, TerracottaCommandLineEnvironment env);

  public abstract void stopTms(File installLocation, TerracottaManagementServerInstance.TerracottaManagementServerInstanceProcess terracottaServerInstanceProcess, TerracottaCommandLineEnvironment tcEnv);

  public void stopTsa(ServerSymbolicName serverSymbolicName, TerracottaServerInstance.TerracottaServerInstanceProcess terracottaServerInstanceProcess) {
    LOGGER.debug("Destroying TC server process for {}", serverSymbolicName);
    for (Number pid : terracottaServerInstanceProcess.getPids()) {
      try {
        ProcessUtil.destroyGracefullyOrForcefullyAndWait(pid.intValue());
      } catch (Exception e) {
        throw new RuntimeException("Could not destroy TC server process with PID " + pid, e);
      }
    }

    final int maxWaitTimeMillis = 30000;
    if (!RetryUtils.waitFor(() -> terracottaServerInstanceProcess.getState() == TerracottaServerState.STOPPED, maxWaitTimeMillis)) {
      throw new RuntimeException(
          String.format(
              "Tried for %dms, but server %s did not get the state %s [remained at state %s]",
              maxWaitTimeMillis,
              serverSymbolicName.getSymbolicName(),
              TerracottaServerState.STOPPED,
              terracottaServerInstanceProcess.getState()
          )
      );
    }
  }

  public abstract TerracottaVoterInstanceProcess startVoter(TerracottaVoter terracottaVoter, File kitDir, File workingDir,
                                                            SecurityRootDirectory securityDir, TerracottaCommandLineEnvironment tcEnv);

  public abstract void stopVoter(TerracottaVoterInstanceProcess terracottaVoterInstanceProcess);
  
  public abstract void configure(String clusterName, File kitDir, File workingDir, String licensePath, Topology topology, Map<ServerSymbolicName, Integer> proxyTsaPorts, SecurityRootDirectory securityRootDirectory, TerracottaCommandLineEnvironment env, boolean verbose);

  public abstract ClusterToolExecutionResult invokeClusterTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment env, Path securityDir, String... arguments);

  public abstract ConfigToolExecutionResult invokeConfigTool(File kitDir, File workingDir, TerracottaCommandLineEnvironment env, Path securityDir, String... arguments);

  public abstract URI tsaUri(Collection<TerracottaServer> servers, Map<ServerSymbolicName, Integer> proxyTsaPorts);

  public abstract String clientJarsRootFolderName(Distribution distribution);

  public abstract String pluginJarsRootFolderName(Distribution distribution);

  public abstract String terracottaInstallationRoot();
}
