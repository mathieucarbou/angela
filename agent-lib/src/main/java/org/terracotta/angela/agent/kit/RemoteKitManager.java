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

package org.terracotta.angela.agent.kit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.angela.agent.Agent;
import org.terracotta.angela.common.distribution.Distribution;
import org.terracotta.angela.common.tcconfig.License;
import org.terracotta.angela.common.topology.InstanceId;
import org.terracotta.angela.common.util.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static org.terracotta.angela.common.AngelaProperties.KIT_COPY;
import static org.terracotta.angela.common.AngelaProperties.SKIP_KIT_COPY_LOCALHOST;
import static org.terracotta.angela.common.util.IpUtils.areAllLocal;
import static org.terracotta.utilities.io.Files.ExtendedOption.RECURSIVE;

/**
 * Download the kit tarball from Kratos
 *
 * @author Aurelien Broszniowski
 */
public class RemoteKitManager extends KitManager {
  private static final Logger logger = LoggerFactory.getLogger(RemoteKitManager.class);

  private final Path workingDir; // The location containing server logs

  public RemoteKitManager(InstanceId instanceId, Distribution distribution, String kitInstallationName) {
    super(distribution);
    this.kitInstallationPath = rootInstallationPath.resolve(kitInstallationName);
    Path workingDir = Agent.WORK_DIR.resolve(instanceId.toString());
    logger.info("Working directory is: {}", workingDir);
    this.workingDir = workingDir;
  }

  // Returns the location to be used for kit - could be the source kit path itself, or a new location based on if or not
  // the kit was copied
  public File installKit(License license, Collection<String> serversHostnames) {
    try {
      Files.createDirectories(workingDir);

      logger.info("should copy a separate kit install ? {}", KIT_COPY.getBooleanValue());
      if (areAllLocal(serversHostnames) && SKIP_KIT_COPY_LOCALHOST.getBooleanValue() && !KIT_COPY.getBooleanValue()) {
        logger.info("Skipped copying kit from {} to {}", kitInstallationPath.toAbsolutePath(), workingDir);
        if (license != null) {
          license.writeToFile(kitInstallationPath.toFile());
        }
        return kitInstallationPath.toFile();
      } else {
        logger.info("Copying {} to {}", kitInstallationPath.toAbsolutePath(), workingDir);
        FileUtils.copy(kitInstallationPath, workingDir, REPLACE_EXISTING, RECURSIVE);
        if (license != null) {
          license.writeToFile(workingDir.toFile());
        }
        return workingDir.toFile();
      }
    } catch (IOException e) {
      throw new RuntimeException("Can not create working install", e);
    }
  }

  public Path getWorkingDir() {
    return workingDir;
  }

  public boolean isKitAvailable() {
    logger.debug("verifying if the extracted kit is already available locally to setup an install");
    if (!Files.isDirectory(kitInstallationPath)) {
      logger.debug("Local kit installation is not available");
      return false;
    }
    return true;
  }

  public void deleteInstall(File installLocation) {
    logger.info("Deleting installation in {}", installLocation.getAbsolutePath());
    FileUtils.deleteTree(installLocation.toPath());
  }
}
