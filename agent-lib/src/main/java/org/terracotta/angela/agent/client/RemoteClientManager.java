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

package org.terracotta.angela.agent.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.ToolExecutionResult;
import org.terracotta.angela.common.net.PortAllocator;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.ExternalLoggers;
import org.terracotta.angela.common.util.LogOutputStream;
import org.terracotta.angela.common.util.OS;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.process.PidUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.terracotta.angela.common.AngelaProperties.DIRECT_JOIN;
import static org.terracotta.angela.common.AngelaProperties.NODE_NAME;
import static org.terracotta.angela.common.AngelaProperties.ROOT_DIR;

/**
 * @author Aurelien Broszniowski
 */

public class RemoteClientManager {

  private final static Logger logger = LoggerFactory.getLogger(RemoteClientManager.class);

  private static final String CLASSPATH_SUBDIR_NAME = "lib";
  private final File kitInstallationPath;

  public RemoteClientManager(InstanceId instanceId) {
    this.kitInstallationPath = Agent.WORK_DIR.resolve(instanceId.toString()).toFile();
  }

  public File getClientInstallationPath() {
    return kitInstallationPath;
  }

  public File getClientClasspathRoot() {
    return new File(kitInstallationPath, CLASSPATH_SUBDIR_NAME);
  }

  public ToolExecutionResult jcmd(int javaPid, TerracottaCommandLineEnvironment tcEnv, String... arguments) {
    String javaHome = tcEnv.getJavaHome();

    List<String> cmdLine = new ArrayList<>();
    if (OS.INSTANCE.isWindows()) {
      cmdLine.add(javaHome + "\\bin\\jcmd.exe");
    } else {
      cmdLine.add(javaHome + "/bin/jcmd");
    }
    cmdLine.add(Integer.toString(javaPid));
    cmdLine.addAll(Arrays.asList(arguments));

    try {
      ProcessResult processResult = new ProcessExecutor(cmdLine)
          .readOutput(true)
          .redirectErrorStream(true)
          .execute();
      return new ToolExecutionResult(processResult.getExitValue(), processResult.getOutput().getLines());
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public int spawnClient(InstanceId instanceId, TerracottaCommandLineEnvironment tcEnv, Collection<String> joinedNodes, int ignitePort, PortAllocator portAllocator) {
    try {
      String javaHome = tcEnv.getJavaHome();

      final AtomicBoolean started = new AtomicBoolean(false);
      List<String> cmdLine = new ArrayList<>();
      if (OS.INSTANCE.isWindows()) {
        cmdLine.add(javaHome + "\\bin\\java.exe");
      } else {
        cmdLine.add(javaHome + "/bin/java");
      }
      if (!tcEnv.getJavaOpts().isEmpty()) {
        cmdLine.addAll(tcEnv.getJavaOpts());
      }
      cmdLine.add("-classpath");
      cmdLine.add(buildClasspath());

      PortAllocator.PortReservation reservation = portAllocator.reserve(2);
      cmdLine.add("-Dignite.discovery.port=" + reservation.next());
      cmdLine.add("-Dignite.com.port=" + reservation.next());
      cmdLine.add("-D" + DIRECT_JOIN.getPropertyName() + "=" + String.join(",", joinedNodes));
      cmdLine.add("-D" + NODE_NAME.getPropertyName() + "=" + instanceId + ":" + ignitePort);
      cmdLine.add("-D" + ROOT_DIR.getPropertyName() + "=" + Agent.ROOT_DIR);
      cmdLine.add(Agent.class.getName());

      logger.info("Spawning client with: {}", cmdLine);
      ProcessExecutor processExecutor = new ProcessExecutor()
          .command(cmdLine)
          .redirectOutput(new LogOutputStream() {
            @Override
            protected void processLine(String line) {
              ExternalLoggers.clientLogger.info("[{}] {}", instanceId, line);
              if (line.equals(Agent.AGENT_IS_READY_MARKER_LOG)) {
                started.set(true);
              }
            }
          })
          .redirectErrorStream(true)
          .directory(getClientInstallationPath());
      StartedProcess startedProcess = processExecutor.start();

      while (startedProcess.getProcess().isAlive() && !started.get()) {
        logger.debug("Waiting for spawned agent to be ready having PID: {}", PidUtil.getPid(startedProcess.getProcess()));
        Thread.sleep(100);
      }
      if (!startedProcess.getProcess().isAlive()) {
        throw new RuntimeException("Client process died in infancy");
      }

      int pid = PidUtil.getPid(startedProcess.getProcess());
      logger.info("Spawned client with PID {}", pid);
      return pid;
    } catch (Exception e) {
      throw new RuntimeException("Error spawning client " + instanceId, e);
    }
  }

  private String buildClasspath() {
    String[] cpEntries = getClientClasspathRoot().list();
    if (cpEntries == null) {
      throw new RuntimeException("Cannot build client classpath before the classpath root is uploaded");
    }

    StringBuilder sb = new StringBuilder();
    for (String cpentry : cpEntries) {
      sb.append(CLASSPATH_SUBDIR_NAME).append(File.separator).append(cpentry).append(File.pathSeparator);
    }

    // if
    //   file:/Users/lorban/.m2/repository/org/slf4j/slf4j-api/1.7.22/slf4j-api-1.7.22.jar!/org/slf4j/Logger.class
    // else
    //   /work/terracotta/irepo/lorban/angela/agent/target/classes/org/terracotta/angela/agent/Agent.class

    String agentClassName = Agent.class.getName().replace('.', '/');
    String agentClassPath = Agent.class.getResource("/" + agentClassName + ".class").getPath();

    if (agentClassPath.startsWith("file:")) {
      sb.append(agentClassPath, "file:".length(), agentClassPath.lastIndexOf('!'));
    } else {
      sb.append(agentClassPath, 0, agentClassPath.lastIndexOf(agentClassName));
    }

    return sb.toString();
  }

}
