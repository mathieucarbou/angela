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
import org.apache.ignite.IgniteException;
import org.apache.ignite.IgniteInterruptedException;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteFutureTimeoutException;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.kit.LocalKitManager;
import org.terracotta.angela.client.filesystem.RemoteFolder;
import org.terracotta.angela.client.util.IgniteClientHelper;
import org.terracotta.angela.common.TerracottaCommandLineEnvironment;
import org.terracotta.angela.common.clientconfig.ClientId;
import org.terracotta.angela.common.cluster.Cluster;
import org.terracotta.angela.common.topology.InstanceId;

import java.io.Closeable;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.terracotta.angela.common.AngelaProperties.SKIP_UNINSTALL;

/**
 * @author Ludovic Orban
 */
public class Client implements Closeable {

  private final static Logger logger = LoggerFactory.getLogger(Client.class);

  private final int ignitePort;
  private final InstanceId instanceId;
  private final ClientId clientId;
  private final Ignite ignite;
  private final int subClientPid;
  private boolean stopped = false;
  private boolean closed = false;


  Client(Ignite ignite, int ignitePort, InstanceId instanceId, ClientId clientId, TerracottaCommandLineEnvironment tcEnv, LocalKitManager localKitManager) {
    this.ignitePort = ignitePort;
    this.instanceId = instanceId;
    this.clientId = clientId;
    this.ignite = ignite;
    this.subClientPid = spawnSubClient(
        Objects.requireNonNull(tcEnv),
        Objects.requireNonNull(localKitManager)
    );
  }

  public ClientId getClientId() {
    return clientId;
  }

  int getPid() {
    return subClientPid;
  }

  private int spawnSubClient(TerracottaCommandLineEnvironment tcEnv, LocalKitManager localKitManager) {
    logger.info("Spawning client '{}' on {}", instanceId, clientId);

    try {
      IgniteClientHelper.uploadClientJars(ignite, getHostname(), ignitePort, instanceId, listClasspathFiles(localKitManager));

      IgniteCallable<Integer> igniteCallable = () -> Agent.controller.spawnClient(instanceId, tcEnv);
      int pid = IgniteClientHelper.executeRemotely(ignite, getHostname(), ignitePort, igniteCallable);
      logger.info("client '{}' on {} started with PID {}", instanceId, clientId, pid);

      return pid;
    } catch (Exception e) {
      logger.error("Cannot create client on {}: {}", clientId, e.getMessage(), e);
      throw new RuntimeException(e);
    }
  }

  private List<File> listClasspathFiles(LocalKitManager localKitManager) {
    List<File> files = new ArrayList<>();

    File javaHome = new File(System.getProperty("java.home"));
    String[] classpathJarNames = System.getProperty("java.class.path").split(File.pathSeparator);
    boolean substituteClientJars = localKitManager.getDistribution() != null;
    List<File> jars = new ArrayList<>();
    for (String classpathJarName : classpathJarNames) {
      if (classpathJarName.startsWith(javaHome.getPath()) || classpathJarName.startsWith(javaHome.getParentFile().getPath())) {
        logger.debug("Skipping {} as it is part of the JVM", classpathJarName);
        continue; // part of the JVM, skip it
      }
      File classpathFile = new File(classpathJarName);

      File equivalentClientJar = localKitManager.equivalentClientJar(classpathFile);
      if (substituteClientJars && equivalentClientJar != null) {
        logger.debug("Skipping upload of classpath file as kit contains equivalent jar in client libs : {}", classpathFile.getName());
        jars.add(equivalentClientJar);
        continue;
      }

      logger.debug("Uploading classpath file : {}", classpathFile.getName());
      files.add(classpathFile);
    }

    if (substituteClientJars) {
      logger.info("Enhancing client classpath with client jars of {}", localKitManager.getDistribution());
      files.addAll(jars);
      logger.debug("Adding clients jars : {}", jars);
    }

    return files;
  }

  Future<Void> submit(ClientId clientId, ClientJob clientJob) {
    IgniteFuture<Void> igniteFuture = IgniteClientHelper.executeRemotelyAsync(ignite, instanceId.toString(), ignitePort, (IgniteCallable<Void>)() -> {
      try {
        clientJob.run(new Cluster(ignite, clientId));
        return null;
      } catch (Throwable t) {
        throw new RemoteExecutionException("Remote ClientJob failed", exceptionToString(t));
      }
    });
    return new ClientJobFuture(igniteFuture);
  }

  private static String exceptionToString(Throwable t) {
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    t.printStackTrace(pw);
    pw.close();
    return sw.toString();
  }

  public RemoteFolder browse(String root) {
    return new RemoteFolder(ignite, instanceId.toString(), ignitePort, null, root);
  }

  public InstanceId getInstanceId() {
    return instanceId;
  }

  public String getHostname() {
    return clientId.getHostname();
  }

  public String getSymbolicName() {
    return clientId.getSymbolicName().getSymbolicName();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;

    stop();
    if (!SKIP_UNINSTALL.getBooleanValue()) {
      logger.info("Wiping up client '{}' on {}", instanceId, clientId);
      IgniteClientHelper.executeRemotely(ignite, getHostname(), ignitePort, (IgniteRunnable)() -> Agent.controller.deleteClient(instanceId));
    }
  }

  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;

    logger.info("Killing client '{}' on {}", instanceId, clientId);
    IgniteClientHelper.executeRemotely(ignite, getHostname(), ignitePort, (IgniteRunnable)() -> Agent.controller.stopClient(instanceId, subClientPid));
  }

  static class ClientJobFuture<V> implements Future<V> {
    private final IgniteFuture<V> igniteFuture;

    ClientJobFuture(IgniteFuture<V> igniteFuture) {
      this.igniteFuture = igniteFuture;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
      return igniteFuture.cancel();
    }

    @Override
    public boolean isCancelled() {
      return igniteFuture.isCancelled();
    }

    @Override
    public boolean isDone() {
      return igniteFuture.isDone();
    }

    @Override
    public V get() throws InterruptedException, ExecutionException {
      try {
        return igniteFuture.get();
      } catch (IgniteInterruptedException iie) {
        throw (InterruptedException) new InterruptedException().initCause(iie);
      } catch (IgniteException ie) {
        RemoteExecutionException ree = lookForRemoteExecutionException(ie);
        if (ree != null) {
          throw new ExecutionException("Client job execution failed", ree);
        } else {
          throw new ExecutionException("Client job execution failed", ie);
        }
      }
    }

    @Override
    public V get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
      try {
        return igniteFuture.get(timeout, unit);
      } catch (IgniteInterruptedException iie) {
        throw (InterruptedException) new InterruptedException().initCause(iie);
      } catch (IgniteFutureTimeoutException ifte) {
        throw (TimeoutException) new TimeoutException().initCause(ifte);
      } catch (IgniteException ie) {
        RemoteExecutionException ree = lookForRemoteExecutionException(ie);
        if (ree != null) {
          throw new ExecutionException("Client job execution failed", ree);
        } else {
          throw new ExecutionException("Client job execution failed", ie);
        }
      }
    }

    private static RemoteExecutionException lookForRemoteExecutionException(Throwable t) {
      if (t instanceof RemoteExecutionException) {
        return (RemoteExecutionException) t;
      } else if (t == null) {
        return null;
      } else {
        return lookForRemoteExecutionException(t.getCause());
      }
    }
  }

  public static class RemoteExecutionException extends Exception {
    private final String remoteStackTrace;
    private String tabulation = "\t";

    RemoteExecutionException(String message, String remoteStackTrace) {
      super(message);
      this.remoteStackTrace = remoteStackTrace;
    }

    @Override
    public String getMessage() {
      return super.getMessage() + "; Remote stack trace is:" + System.lineSeparator() + tabulation + "{{{" + System.lineSeparator() + tabulation + remoteStackTrace() + "}}}";
    }

    private String remoteStackTrace() {
      return remoteStackTrace.replaceAll(System.lineSeparator(), System.lineSeparator() + tabulation);
    }

    public void setRemoteStackTraceIndentation(int indentation) {
      StringBuilder sb = new StringBuilder(indentation);
      for (int i = 0; i < indentation; i++) {
        sb.append('\t');
      }
      tabulation = sb.toString();
    }
  }

}
