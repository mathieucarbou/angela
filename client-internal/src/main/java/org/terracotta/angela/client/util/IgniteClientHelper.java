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

package org.terracotta.angela.client.util;

import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteException;
import org.apache.ignite.cluster.ClusterGroup;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.lang.IgniteFuture;
import org.apache.ignite.lang.IgniteRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.agent.client.RemoteClientManager;
import org.terracotta.angela.agent.kit.RemoteKitManager;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.AngelaVersion;
import org.terracotta.angela.common.util.FileMetadata;
import org.terracotta.angela.common.util.IgniteCommonHelper;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class IgniteClientHelper {

  private final static Logger logger = LoggerFactory.getLogger(IgniteClientHelper.class);

  public static void executeRemotely(Ignite ignite, String hostname, int ignitePort, IgniteRunnable job) {
    if (ignite == null) {
      executeLocally(job);
    } else {
      executeRemotelyAsync(ignite, hostname, ignitePort, job).get();
    }
  }

  private static void executeLocally(IgniteRunnable job) {
    job.run();
  }

  public static IgniteFuture<Void> executeRemotelyAsync(Ignite ignite, String hostname, int ignitePort, IgniteRunnable job) {
    IgniteClientHelper.checkAgentHealth(ignite, hostname, ignitePort);
    logger.debug("Executing job on {}", getNodeName(hostname, ignitePort));
    IgniteCommonHelper.displayCluster(ignite);

    ClusterGroup location = ignite.cluster().forAttribute("nodename", getNodeName(hostname, ignitePort));
    return ignite.compute(location).runAsync(job);
  }

  public static <R> R executeRemotely(Ignite ignite, String hostname, int ignitePort, IgniteCallable<R> job) {
    if (ignite == null) {
      return executeLocally(job);
    } else {
      return executeRemotelyAsync(ignite, hostname, ignitePort, job).get();
    }
  }

  private static <R> R executeLocally(IgniteCallable<R> job) {
    try {
      return job.call();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  public static <R> IgniteFuture<R> executeRemotelyAsync(Ignite ignite, String hostname, int ignitePort, IgniteCallable<R> job) {
    IgniteClientHelper.checkAgentHealth(ignite, hostname, ignitePort);
    logger.debug("Executing job on {}", getNodeName(hostname, ignitePort));
    ClusterGroup location = ignite.cluster().forAttribute("nodename", getNodeName(hostname, ignitePort));
    return ignite.compute(location).callAsync(job);
  }

  private static void checkAgentHealth(Ignite ignite, String hostname, int ignitePort) {
    if (ignite == null) {
      throw new IllegalStateException("No ignite instance");
    }
    final String nodeName = getNodeName(hostname, ignitePort);
    ClusterGroup location = ignite.cluster().forAttribute("nodename", nodeName);
    IgniteFuture<Collection<Map<String, ?>>> future = ignite.compute(location)
        .broadcastAsync((IgniteCallable<Map<String, ?>>) () -> Agent.controller.getNodeAttributes());
    try {
      Collection<Map<String, ?>> attributeMaps = future.get(60, TimeUnit.SECONDS);
      if (attributeMaps.size() != 1) {
        throw new IllegalStateException("Detected " + attributeMaps.size() + " agents with node name [" + nodeName + "] while expected exactly one");
      }
      Map<String, ?> attributeMap = attributeMaps.iterator().next();
      if (!nodeName.equals(attributeMap.get("nodename"))) {
        throw new IllegalStateException("Agent " + nodeName + " mistakenly identifies itself as " + attributeMap.get("nodename"));
      }
      if (!AngelaVersion.getAngelaVersion().equals(attributeMap.get("angela.version"))) {
        throw new IllegalStateException("Agent " + nodeName + " is running version [" + attributeMap.get("angela.version") + "]" +
            " but the expected version is [" + AngelaVersion.getAngelaVersion() + "]");
      }
    } catch (IgniteException e) {
      e.printStackTrace();
      throw new IllegalStateException("Node with name '" + nodeName + "' not found in the cluster", e);
    }
  }

  private static String getNodeName(String nodeName, int ignitePort) {
    return nodeName + ":" + ignitePort;
  }

  public static void uploadKit(Ignite ignite, String hostname, int ignitePort, InstanceId instanceId, Distribution distribution,
                               String kitInstallationName, File kitInstallationPath) throws IOException, InterruptedException {
    IgniteFuture<Void> remoteDownloadFuture = executeRemotelyAsync(
        ignite,
        hostname,
        ignitePort,
        () -> {
          RemoteKitManager remoteKitManager = new RemoteKitManager(instanceId, distribution, kitInstallationName);
          File installDir = remoteKitManager.getKitInstallationPath().getParent().toFile();
          Agent.controller.downloadFiles(instanceId, installDir);
        }
    );

    uploadFiles(ignite, instanceId, Collections.singletonList(kitInstallationPath), remoteDownloadFuture);
  }

  public static void uploadClientJars(Ignite ignite, String hostname, int ignitePort, InstanceId instanceId, List<File> filesToUpload) throws IOException, InterruptedException {
    IgniteFuture<Void> remoteDownloadFuture = executeRemotelyAsync(ignite, hostname,
        ignitePort, () -> Agent.controller.downloadFiles(instanceId, new RemoteClientManager(instanceId).getClientClasspathRoot()));

    uploadFiles(ignite, instanceId, filesToUpload, remoteDownloadFuture);
  }

  private static void uploadFiles(Ignite ignite, InstanceId instanceId, List<File> files, IgniteFuture<Void> remoteDownloadFuture) throws InterruptedException, IOException {
    try {
      BlockingQueue<Object> queue = IgniteCommonHelper.fileTransferQueue(ignite, instanceId);
      for (File file : files) {
        uploadFile(remoteDownloadFuture, queue, file, null);
      }
      queue.put(Boolean.TRUE); // end of upload marker
    } finally {
      remoteDownloadFuture.get();
    }
  }

  private static void uploadFile(IgniteFuture<Void> remoteDownloadFuture, BlockingQueue<Object> queue, File file, String path) throws InterruptedException, IOException {
    if (remoteDownloadFuture.isDone()) {
      throw new RuntimeException("Download process failed, cancelling upload");
    }
    FileMetadata fileMetadata = new FileMetadata(path, file);
    if (!file.exists()) {
      logger.debug("skipping upload of non-existent classpath entry {}", fileMetadata);
      return;
    }
    queue.put(fileMetadata);
    logger.debug("uploading {}", fileMetadata);

    if (file.isDirectory()) {
      File[] files = file.listFiles();
      for (File _file : files) {
        String parentPath = path == null ? "" : path + "/";
        uploadFile(remoteDownloadFuture, queue, _file, parentPath + file.getName());
      }
    } else {
      byte[] buffer = new byte[64 * 1024];
      try (FileInputStream fis = new FileInputStream(file)) {
        while (true) {
          int read = fis.read(buffer);
          if (read < 0) {
            break;
          }
          byte[] toSend;
          if (read != buffer.length) {
            toSend = new byte[read];
            System.arraycopy(buffer, 0, toSend, 0, read);
          } else {
            toSend = buffer;
          }
          queue.put(toSend);
        }
      }
      logger.debug("uploaded {}", fileMetadata);
    }
  }

}
