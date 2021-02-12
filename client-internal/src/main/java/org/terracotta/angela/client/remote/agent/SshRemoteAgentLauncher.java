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

package org.terracotta.angela.client.remote.agent;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.common.StreamCopier;
import net.schmizz.sshj.connection.ConnectionException;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.transport.TransportException;
import net.schmizz.sshj.transport.verification.PromiscuousVerifier;
import net.schmizz.sshj.xfer.InMemoryDestFile;
import net.schmizz.sshj.xfer.scp.SCPRemoteException;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.util.AngelaVersions;
import org.terracotta.angela.common.util.JDK;
import org.terracotta.angela.common.util.JavaLocationResolver;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.terracotta.angela.common.AngelaProperties.DIRECT_JOIN;
import static org.terracotta.angela.common.AngelaProperties.NODE_NAME;
import static org.terracotta.angela.common.AngelaProperties.ROOT_DIR;
import static org.terracotta.angela.common.AngelaProperties.SSH_STRICT_HOST_CHECKING;
import static org.terracotta.angela.common.AngelaProperties.SSH_USERNAME;
import static org.terracotta.angela.common.AngelaProperties.SSH_USERNAME_KEY_PATH;

public class SshRemoteAgentLauncher implements RemoteAgentLauncher {

  private static final Logger LOGGER = LoggerFactory.getLogger(SshRemoteAgentLauncher.class);
  private static final int MAX_LINE_LENGTH = 1024;

  private final Map<String, RemoteAgentHolder> clients = new HashMap<>();
  private final String remoteUserName;
  private final String remoteUserNameKeyPath;
  private final TerracottaCommandLineEnvironment tcEnv;
  private File agentJarFile;
  private boolean agentJarFileShouldBeRemoved;

  static class RemoteAgentHolder {
    RemoteAgentHolder(SSHClient sshClient, Session session, Session.Command command) {
      this.sshClient = sshClient;
      this.session = session;
      this.command = command;
    }

    SSHClient sshClient;
    Session session;
    Session.Command command;
  }


  public SshRemoteAgentLauncher() {
    this(TerracottaCommandLineEnvironment.DEFAULT);
  }

  public SshRemoteAgentLauncher(TerracottaCommandLineEnvironment tcEnv) {
    this.tcEnv = tcEnv;
    this.remoteUserName = SSH_USERNAME.getValue();
    this.remoteUserNameKeyPath = SSH_USERNAME_KEY_PATH.getValue();
  }

  private void initAgentJar() {
    if (agentJarFile != null) {
      return;
    }
    Map.Entry<File, Boolean> agentJar = findAgentJarFile();
    this.agentJarFile = agentJar.getKey();
    this.agentJarFileShouldBeRemoved = agentJar.getValue();
    if (this.agentJarFile == null) {
      throw new RuntimeException("agent JAR file not found, cannot use SSH remote agent launcher");
    }
  }

  @Override
  public void remoteStartAgentOn(String hostname, String nodeName, int igniteDiscoveryPort, int igniteComPort, String addressesToDiscover) {
    initAgentJar();
    LOGGER.info("spawning {} agent via SSH", hostname);

    try {
      SSHClient ssh = new SSHClient();
      final String angelaHome = ".angela/" + hostname;

      if (!SSH_STRICT_HOST_CHECKING.getBooleanValue()) {
        ssh.addHostKeyVerifier(new PromiscuousVerifier());
      }
      ssh.loadKnownHosts();
      ssh.connect(hostname);

      // load provided private key file, if available.
      if (remoteUserNameKeyPath == null) {
        ssh.authPublickey(remoteUserName);
      } else {
        ssh.authPublickey(remoteUserName, remoteUserNameKeyPath);
      }

      Path baseDir = Agent.ROOT_DIR.resolve(angelaHome);
      Path jarsDir = baseDir.resolve("jars");
      exec(ssh, "mkdir -p " + baseDir.toString());
      exec(ssh, "chmod a+w " + baseDir.getParent().toString());
      exec(ssh, "chmod a+w " + baseDir.toString());
      exec(ssh, "mkdir -p " + jarsDir.toString());
      exec(ssh, "chmod a+w " + jarsDir.toString());
      if (agentJarFile.getName().endsWith("-SNAPSHOT.jar") || exec(ssh, "[ -e " + jarsDir.resolve(agentJarFile.getName()).toString() + " ]") != 0) {
        // jar file is a snapshot or does not exist, upload it
        LOGGER.info("uploading agent jar {} ...", agentJarFile.getName());
        uploadJar(ssh, agentJarFile, jarsDir);
      }

      LOGGER.info("looking up remote JDK ...");
      String remoteJavaHome = findJavaHomeFromRemoteToolchains(ssh);

      Session session = ssh.startSession();
      session.allocateDefaultPTY();
      LOGGER.info("starting agent");
      Session.Command cmd = session.exec(remoteJavaHome + "/bin/java " +
          "-D" + NODE_NAME.getPropertyName() + "=" + nodeName + " " +
          "-Dignite.discovery.port=" + igniteDiscoveryPort + " " +
          "-Dignite.com.port=" + igniteComPort + " " +
          "-D" + DIRECT_JOIN.getPropertyName() + "=" + addressesToDiscover + " " +
          "-D" + ROOT_DIR.getPropertyName() + "=" + baseDir.toString() + " " +
          "-jar " + jarsDir.resolve(agentJarFile.getName()).toString());

      SshLogOutputStream sshLogOutputStream = new SshLogOutputStream(hostname, cmd);
      new StreamCopier(cmd.getInputStream(), sshLogOutputStream, net.schmizz.sshj.common.LoggerFactory.DEFAULT).bufSize(MAX_LINE_LENGTH)
          .spawnDaemon("stdout");
      new StreamCopier(cmd.getErrorStream(), sshLogOutputStream, net.schmizz.sshj.common.LoggerFactory.DEFAULT).bufSize(MAX_LINE_LENGTH)
          .spawnDaemon("stderr");

      sshLogOutputStream.waitForStartedState();

      LOGGER.info("agent started on {}", hostname);
      clients.put(hostname, new RemoteAgentHolder(ssh, session, cmd));

    } catch (Exception e) {
      throw new RuntimeException("Failed to connect to " + remoteUserName + "@" + hostname + " (using SSH)", e);
    }
  }

  private static Map.Entry<File, Boolean> findAgentJarFile() {
    try {
      if (AngelaVersions.INSTANCE.isSnapshot()) {
        String snapshotLocation =
            System.getProperty("user.home") + "/.m2/repository/org/terracotta/angela-agent/" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            "/angela-agent-" +
            AngelaVersions.INSTANCE.getAngelaVersion() +
            ".jar";

        File snapshot = new File(snapshotLocation);
        if (snapshot.isFile()) {
          LOGGER.info("Found agent jar at " + snapshotLocation);
          return new HashMap.SimpleEntry<>(snapshot, false);
        }

        // are we building angela? if yes, find the built agent jar in the module's target folder
        String mavenBaseDir = System.getProperty("basedir");
        if (mavenBaseDir != null && new File(mavenBaseDir + "/../agent").isDirectory()) {
          snapshotLocation = mavenBaseDir + "/../agent/target" +
              "/angela-agent-" +
              AngelaVersions.INSTANCE.getAngelaVersion() +
              ".jar";
          snapshot = new File(snapshotLocation);
          if (snapshot.isFile()) {
            LOGGER.info("Found agent jar at " + snapshotLocation);
            return new HashMap.SimpleEntry<>(snapshot, false);
          }
        }

        throw new RuntimeException("Agent SNAPSHOT jar file not found at " + snapshotLocation);
      } else {
        File tmpDir = Files.createTempDirectory("angela").toFile();
        File agentFile = new File(tmpDir, "angela-agent-" + AngelaVersions.INSTANCE.getAngelaVersion() + ".jar");
        String releaseUrl = "https://search.maven.org/remotecontent?filepath=org/terracotta/angela-agent/" +
                            AngelaVersions.INSTANCE.getAngelaVersion() +
                            "/angela-agent-" +
                            AngelaVersions.INSTANCE.getAngelaVersion() +
                            ".jar";
        URL jarUrl = new URL(releaseUrl);
        try (InputStream jarIs = jarUrl.openStream(); FileOutputStream fileOutputStream = new FileOutputStream(agentFile)) {
          IOUtils.copy(jarIs, fileOutputStream);
        }
        LOGGER.info("Installed agent jar from Nexus at " + agentFile.getAbsolutePath());
        return new HashMap.SimpleEntry<>(agentFile, true);
      }
    } catch (Exception e) {
      throw new RuntimeException("Could not get angela-agent jar", e);
    }
  }

  private Integer exec(SSHClient ssh, String line) throws TransportException, ConnectionException {
    try (Session session = ssh.startSession()) {
      Session.Command cmd = session.exec(line);
      try {
        cmd.join(10, TimeUnit.SECONDS);
      } finally {
        cmd.close();
      }
      return cmd.getExitStatus();
    }
  }

  private void uploadJar(SSHClient ssh, File agentJarFile, Path targetFolder) throws IOException {
    String remotePath = targetFolder.resolve(agentJarFile.getName()).toString();
    ssh.newSCPFileTransfer().upload(agentJarFile.getPath(), remotePath);
  }

  private String findJavaHomeFromRemoteToolchains(SSHClient ssh) throws IOException {
    InMemoryDestFile localFile = new InMemoryDestFile() {
      private final ByteArrayOutputStream baos = new ByteArrayOutputStream();
      @Override
      public OutputStream getOutputStream() {
        return baos;
      }
    };
    try {
      ssh.newSCPFileTransfer().download("$HOME/.m2/toolchains.xml", localFile);
    } catch (SCPRemoteException sre) {
      throw new RuntimeException("Remote does not have $HOME/.m2/toolchains.xml file");
    }
    byte[] bytes = ((ByteArrayOutputStream) localFile.getOutputStream()).toByteArray();
    JavaLocationResolver javaLocationResolver = new JavaLocationResolver(new ByteArrayInputStream(bytes));
    List<JDK> jdks = javaLocationResolver.resolveJavaLocations(tcEnv.getJavaVersion(), tcEnv.getJavaVendors(), false);
    // check JDK validity remotely
    for (JDK jdk : jdks) {
      String remoteHome = jdk.getHome();
      if (exec(ssh, "[ -d \"" + remoteHome + "\" ]") == 0) {
        LOGGER.info("found remote JDK : home='{}' version='{}' vendor='{}'", jdk.getHome(), jdk.getVersion(), jdk.getVendor());
        return remoteHome;
      }
    }
    throw new RuntimeException("No JDK configured in remote toolchains.xml is valid; wanted : " + tcEnv + ", found : " + jdks);
  }

  @Override
  public void close() throws Exception {
    if (agentJarFileShouldBeRemoved) {
      agentJarFile.delete();
    }
    for (Map.Entry<String, RemoteAgentHolder> entry : clients.entrySet()) {
      RemoteAgentHolder holder = entry.getValue();
      LOGGER.info("Cleaning up SSH agent on {}", entry.getKey());

      // 0x03 is the character for CTRL-C -> send it to the remote PTY
      holder.session.getOutputStream().write(0x03);
      safeClose(holder.command);
      safeClose(holder.session);
      safeClose(holder.sshClient);
    }
    clients.clear();
  }

  private static void safeClose(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException e) {
      LOGGER.warn("Error while cleaning up SSH agent", e);
    }
  }
}
